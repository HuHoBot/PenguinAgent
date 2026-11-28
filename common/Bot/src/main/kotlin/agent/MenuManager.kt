package agent

import agent.events.commands.RegisteredCommand
import agent.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** QQ 群指令面板同步：支持按群独立配置，复用受管面板避免重复创建。 */
object MenuManager {
    private const val API_BASE = "https://api.sgroup.qq.com"

    /** 受管面板 remark 前缀。QQ 官方对 remark 有长度限制，前缀务必保持精简。 */
    private const val PANEL_REMARK = "HuHoBot 指令面板"
    /** v1.21.0 曾使用过更长前缀；同步时一并识别，避免升级后重复创建。 */
    private const val LEGACY_PANEL_REMARK = "HuHoBot Penguin 指令面板"
    private const val MAX_ITEMS = 20
    private const val MAX_NAME_DISPLAY_WIDTH = 14
    private const val MAX_DESCRIPTION_DISPLAY_WIDTH = 30
    private const val MAX_TARGETS_PER_REQUEST = 20
    private const val MAX_DUPLICATE_DELETES_PER_SYNC = 10
    /** 官方文档：指令面板写操作限频 10 QPM。 */
    private const val PANEL_UPDATE_RETRY_DELAY_MS = 1_000L

    private data class PanelProfile(
        val signature: String,
        val remark: String,
        val items: List<JSONObject>,
        val groups: MutableList<String>,
        /** true = QQ 全局面板（target_type=all），覆盖所有群，包括未写进配置的群。 */
        val allTargets: Boolean
    )

    fun syncGroupPanels(
        starter: Starter,
        groupOpenIds: List<String>,
        allBuiltInCommands: Collection<RegisteredCommand>,
        customCommands: Collection<RegisteredCommand> = emptyList()
    ) {
        val plugin = BotShared.getPlugin() ?: return
        val groups = groupOpenIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (groups.isEmpty()) {
            plugin.log_warning("面板同步跳过: 未配置群 OpenId")
            return
        }

        try {
            val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
            val token = start0.accessToken
            if (token == null) {
                plugin.log_warning("面板同步跳过: accessToken 为空")
                return
            }
            val authHeader = "QQBot $token"

            // 按群的有效配置分组：相同配置共享一个面板，避免不必要的面板数量
            val allMode = groups.all { !agent.state.GroupCommandConfigStore.hasOverrides(it) }
            if (allMode) {
                plugin.log_info(
                    "所有配置群均无单独面板覆盖：使用 QQ 全局面板（target_type=all），" +
                        "未写进配置的群也会显示默认面板"
                )
            }
            val profiles = linkedMapOf<String, PanelProfile>()
            for (groupId in groups) {
                var items = buildItemsForGroup(plugin, groupId, allBuiltInCommands, customCommands)
                if (items.isEmpty()) {
                    // 群覆盖把面板清空（所有命令 pushMenu=false）时，绝不让该群变成
                    // 「@机器人不弹面板」：回退到默认（全局）面板配置。
                    val fallback = buildItems(
                        allBuiltInCommands.filter { plugin.getCommandList()[it.command] != false }
                            .filter { plugin.getCommandMenuList()[it.command] != false },
                        customCommands,
                        plugin.getCommandMenuPriorities(),
                        plugin.showAdminCommandsInMenu()
                    )
                    if (fallback.isNotEmpty()) {
                        plugin.log_warning("群 $groupId 的自定义面板配置为空，已回退默认面板")
                        items = fallback
                    }
                }
                if (items.isEmpty()) continue
                val limited = if (items.size > MAX_ITEMS) items.take(MAX_ITEMS) else items
                val signature = panelSignature(limited, plugin.showAdminCommandsInMenu(groupId))
                val profile = profiles.getOrPut(signature) {
                    PanelProfile(signature, "$PANEL_REMARK #$signature", limited, mutableListOf(), allMode)
                }
                profile.groups += groupId
            }

            if (profiles.isEmpty()) {
                // 防御：绝不因为"这次算不出面板条目"而清理旧面板——
                // v1.21.0 曾在此处把 profiles 为空当成"没有受管面板在用"，
                // 随后的 stale 清理会删光所有面板，QQ 端 @机器人 不再弹出（issue #36）。
                plugin.log_warning(
                    "面板同步跳过: 所有群都没有可推送的命令条目（请检查 commands.<命令>.pushMenu 与 enable 配置），" +
                        "保留现有面板不动"
                )
                return
            }

            val allPanels = listPanels(authHeader, "group")
            val managed = allPanels.filter { isManagedRemark(it.remark) }
            val managedByRemark = managed.associateBy { it.remark }.toMutableMap()
            val usedPanelIds = mutableSetOf<String>()
            var succeededProfiles = 0

            for (profile in profiles.values) {
                // 逐个 profile 隔离：一个失败不拖垮其余面板，更不清空旧面板
                try {
                    // targetType 必须一致才复用：specific/all 模式切换时旧面板走
                    // 重新创建 + stale 清理，而不是把旧面板原位改写成新模式
                    // （all 面板不支持 targets 增删，specific 面板覆盖不到未配置群）。
                    val expectedType = if (profile.allTargets) "all" else "specific"
                    val existing = managedByRemark[profile.remark]
                        ?.takeIf { it.targetType == expectedType }
                        ?.let { getPanel(authHeader, it.id) ?: it }
                    val panelId = if (existing != null) {
                        updatePanelWithVersion(authHeader, existing, profile.items)
                        if (existing.targetType != "all") {
                            syncPanelTargets(authHeader, existing, profile.groups)
                        }
                        existing.id
                    } else {
                        createPanelForGroups(
                            authHeader,
                            profile.remark,
                            profile.items,
                            profile.groups,
                            profile.allTargets
                        )
                    }
                    if (panelId != null) {
                        usedPanelIds += panelId
                        succeededProfiles++
                        plugin.log_info(
                            "指令面板已同步 (${profile.remark}, panel_id=$panelId, commands=${profile.items.size}, groups=${profile.groups.size})"
                        )
                    } else {
                        plugin.log_warning("指令面板注册失败（${profile.remark}）: 未获取到 panel_id")
                        if (reuseOrphanPanel(authHeader, managed, usedPanelIds, profile)) succeededProfiles++
                    }
                } catch (error: Exception) {
                    plugin.log_error("指令面板 ${profile.remark} 同步失败: ${error.message}（保留现有面板）")
                    // 创建/更新抛异常（QQ 服务异常、限频、网络中断、配额用满 30013）时同样
                    // 降级复用旧面板，否则该 profile 的旧面板会因不在 usedPanelIds 里被
                    // stale 清理删掉，导致面板消失（issue #36 同类事故）。
                    runCatching {
                        if (reuseOrphanPanel(authHeader, managed, usedPanelIds, profile)) succeededProfiles++
                    }.onFailure { retryError ->
                        plugin.log_error("指令面板 ${profile.remark} 回退复用也失败: ${retryError.message}")
                    }
                }
                // QQ 写操作限频 10 QPM，多面板时主动留间隔
                if (profiles.size > 1) Thread.sleep(700)
            }

            if (succeededProfiles == 0) {
                // 全部 profile 都失败：保留旧面板，避免"升级后面板消失"
                plugin.log_warning("指令面板同步全部失败，保留现有面板不变（可稍后手动重载重试）")
                return
            }

            // 清理不再使用的受管面板（群配置变化后遗留的 profile）。
            // 只有本轮至少有一个面板成功落地时才清理。
            val stale = managed.filter { it.id !in usedPanelIds }
            stale.take(MAX_DUPLICATE_DELETES_PER_SYNC).forEach {
                runCatching { deletePanel(authHeader, it.id) }
                if (profiles.size + stale.size > 1) Thread.sleep(700)
            }
            if (stale.size > MAX_DUPLICATE_DELETES_PER_SYNC) {
                plugin.log_warning(
                    "检测到 ${stale.size} 个待清理的历史指令面板，本次已清理 " +
                        "$MAX_DUPLICATE_DELETES_PER_SYNC 个；下次同步会继续清理"
                )
            }
        } catch (error: Exception) {
            plugin.log_error("指令面板同步失败: ${error.message}（现有面板已保留，不会导致面板消失）")
        }
    }

    private fun isManagedRemark(remark: String): Boolean =
        remark == PANEL_REMARK ||
            remark == LEGACY_PANEL_REMARK ||
            remark.startsWith("$LEGACY_PANEL_REMARK #") ||
            remark == "HuHoBot 指令面板" ||
            remark.startsWith("$PANEL_REMARK #")

    private fun panelSignature(items: List<JSONObject>, showAdminCommands: Boolean): String {
        val raw = buildString {
            append(if (showAdminCommands) '1' else '0')
            for (item in items) {
                append('|').append(item.getString("name"))
                append(':').append(item.getString("desc"))
                append(':').append(item.getBooleanValue("only_admin"))
            }
        }
        var h = 0x811c9dc5.toInt()
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xff)
            h *= 0x01000193
        }
        return (h.toLong() and 0xffffffffL).toString(16).padStart(8, '0')
    }

    /**
     * 创建失败的降级路径：从受管面板里挑一个本轮未被认领的，原位改成当前
     * profile 的内容、目标群并认领其 remark。宁可"旧面板换新内容"，也不能
     * 因为新建失败（QQ 服务异常、面板配额用满、网络抖动）就让群彻底失去面板。
     * 把 remark 覆写为当前 profile 的，下次同步即可被 managedByRemark 命中并收敛。
     */
    private fun reuseOrphanPanel(
        authHeader: String,
        managed: List<PanelRecord>,
        usedPanelIds: MutableSet<String>,
        profile: PanelProfile
    ): Boolean {
        val plugin = BotShared.getPlugin() ?: return false
        val expectedType = if (profile.allTargets) "all" else "specific"
        val orphan = managed.firstOrNull { it.id !in usedPanelIds && it.targetType == expectedType } ?: run {
            plugin.log_warning(
                "指令面板 ${profile.remark} 创建失败且没有可复用的受管面板；" +
                    "现有面板保持原样（如 QQ 端面板数达 20 上限，请到开放平台清理旧面板后重试）"
            )
            return false
        }
        val full = getPanel(authHeader, orphan.id) ?: orphan
        updatePanelWithVersion(authHeader, full, profile.items, remarkOverride = profile.remark)
        if (full.targetType != "all") {
            syncPanelTargets(authHeader, full, profile.groups)
        }
        usedPanelIds += full.id
        plugin.log_warning(
            "指令面板创建失败，已回退复用现有受管面板（panel_id=${full.id} → ${profile.remark}）"
        )
        return true
    }

    private fun createPanelForGroups(
        authHeader: String,
        remark: String,
        items: List<JSONObject>,
        groups: List<String>,
        allTargets: Boolean = false
    ): String? {
        // 全局面板（target_type=all）覆盖机器人所在的所有群，且官方不支持对它
        // 做关联对象的增删（错误码 40030021），因此创建时既不传群列表、之后也
        // 不做 targets 同步。
        val panelBody = JSONObject().apply {
            put("scope", "group")
            if (allTargets) {
                put("target_type", "all")
            } else {
                put("target_type", "specific")
                put("group_openids", JSONArray(groups.take(MAX_TARGETS_PER_REQUEST)))
            }
            put("panel", panelPayload(items, remark = remark))
        }
        val panelId = createPanel(authHeader, panelBody) ?: return null
        if (!allTargets) {
            groups.drop(MAX_TARGETS_PER_REQUEST)
                .chunked(MAX_TARGETS_PER_REQUEST)
                .forEach { updateTargets(authHeader, panelId, "add", it) }
        }
        return panelId
    }

    private fun buildItemsForGroup(
        plugin: agent.HuHoBot,
        groupId: String,
        allBuiltInCommands: Collection<RegisteredCommand>,
        customCommands: Collection<RegisteredCommand>
    ): List<JSONObject> {
        val commandList = plugin.getCommandList(groupId)
        val menuList = plugin.getCommandMenuList(groupId)
        val priorities = plugin.getCommandMenuPriorities(groupId)
        val showAdminCommands = plugin.showAdminCommandsInMenu(groupId)
        val builtIn = allBuiltInCommands
            .filter { commandList[it.command] != false }
            .filter { menuList[it.command] != false }
        return buildItems(builtIn, customCommands, priorities, showAdminCommands)
    }

    private fun buildItems(
        builtInCommands: Collection<RegisteredCommand>,
        customCommands: Collection<RegisteredCommand>,
        priorities: Map<String, Int>,
        showAdminCommands: Boolean
    ): List<JSONObject> {
        val plugin = BotShared.getPlugin()
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        // 数值优先级由 WebUI 配置；相同优先级时公开命令优先。
        // QQ 原生管理员与插件配置管理员不一定相同，可选只在面板显示层放开可见性。
        (builtInCommands + customCommands)
            .sortedWith(compareBy<RegisteredCommand> { priorities[it.command] ?: if (it.command == "agent") 0 else 100 }
                .thenBy { it.onlyAdmin }
                .thenBy { it.command })
            .forEach { command ->
            val name = command.command.trim()
            if (name.isEmpty()) return@forEach
            // 命令映射/切换：被切换掉的原名不再出现在面板，其余显示映射/切换后的新名
            if (agent.state.CommandMappingStore.isHidden(name)) return@forEach
            val displayName = agent.state.CommandMappingStore.displayName(name)
            if (!seen.add(displayName.lowercase())) return@forEach
            if (displayWidth(displayName) > MAX_NAME_DISPLAY_WIDTH) {
                // 面板中的 name 就是实际插入输入框的命令，截断会生成不可用命令，因此跳过。
                plugin?.log_warning(
                    "命令面板跳过 '$displayName': 名称显示宽度超过 $MAX_NAME_DISPLAY_WIDTH"
                )
                return@forEach
            }
            val originalDescription = command.describe.trim()
            val description = truncateDisplayWidth(originalDescription, MAX_DESCRIPTION_DISPLAY_WIDTH)
            if (description != originalDescription) {
                plugin?.log_warning(
                    "命令 '$name' 的面板描述超过显示宽度 $MAX_DESCRIPTION_DISPLAY_WIDTH，已自动截短"
                )
            }
            result += JSONObject().apply {
                put("type", "command")
                put("name", name)
                put("desc", description)
                put("only_admin", command.onlyAdmin && !showAdminCommands)
            }
        }
        return result
    }

    /** QQ 面板按显示宽度计数：ASCII 为 1，其他 Unicode 码点为 2。 */
    private fun displayWidth(value: String): Int {
        var width = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            width += if (codePoint <= 0x7f) 1 else 2
            offset += Character.charCount(codePoint)
        }
        return width
    }

    private fun truncateDisplayWidth(value: String, maximumWidth: Int): String {
        if (displayWidth(value) <= maximumWidth) return value
        val result = StringBuilder()
        var width = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val codePointWidth = if (codePoint <= 0x7f) 1 else 2
            if (width + codePointWidth > maximumWidth) break
            result.appendCodePoint(codePoint)
            width += codePointWidth
            offset += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun panelPayload(
        items: List<JSONObject>,
        version: Int? = null,
        remark: String = PANEL_REMARK
    ): JSONObject = JSONObject().apply {
        put("remark", remark)
        put("items", JSONArray(items))
        // 原位更新必须带上读到的当前版本号，否则 QQ 侧按乐观并发判定为
        // 30019「面板版本冲突，请重试」。
        if (version != null) put("version", version)
    }

    /** 完整分页读取；旧实现只看第一页，历史面板可能因此永远无法被清理。 */
    private fun listPanels(authHeader: String, scope: String): List<PanelRecord> {
        val result = mutableListOf<PanelRecord>()
        var cursor: String? = null
        var page = 0
        do {
            val cursorQuery = cursor?.takeIf(String::isNotBlank)?.let {
                "&cursor=${URLEncoder.encode(it, "UTF-8")}"
            }.orEmpty()
            val response = request(
                method = "GET",
                endpoint = "/v2/panels?scope=$scope&limit=50$cursorQuery",
                authHeader = authHeader
            )
            response.requireSuccess("查询指令面板")
            val root = JSON.parseObject(response.body) ?: JSONObject()
            val container = root.getJSONObject("data") ?: root
            val records = container.getJSONArray("records") ?: JSONArray()
            for (index in 0 until records.size) {
                val value = records[index]
                val objectValue = when (value) {
                    is JSONObject -> value
                    is Map<*, *> -> JSONObject(value.entries.associate { it.key.toString() to it.value })
                    else -> null
                } ?: continue
                parsePanelRecord(objectValue)?.let { record ->
                    // 部分接口版本的列表只返回 panel_id；取详情后才能识别受管面板。
                    result += if (record.remark.isBlank()) {
                        getPanel(authHeader, record.id) ?: record
                    } else {
                        record
                    }
                }
            }
            cursor = container.getString("next_cursor")?.takeIf(String::isNotBlank)
            val isEnd = container.getBoolean("is_end") ?: false
            page++
            if (isEnd || cursor == null || page >= 20) break
        } while (true)
        return result.distinctBy(PanelRecord::id)
    }

    private fun parsePanelRecord(value: JSONObject): PanelRecord? {
        val id = value.getString("panel_id") ?: return null
        val panel = value.getJSONObject("panel")
        val remark = panel?.getString("remark") ?: value.getString("remark").orEmpty()
        val targets = value.getJSONArray("group_openids")?.mapNotNull { it?.toString() }.orEmpty()
        // version 在详情响应里顶层和 panel 内各有一份，两个位置都认
        val version = value.getInteger("version") ?: panel?.getInteger("version")
        val targetType = value.getString("target_type")
            ?: panel?.getString("target_type")
            ?: "specific"
        return PanelRecord(id, remark, targets, version, targetType)
    }

    private fun getPanel(authHeader: String, panelId: String): PanelRecord? {
        val response = request(
            method = "GET",
            endpoint = "/v2/panels/$panelId",
            authHeader = authHeader
        )
        if (!response.success) return null
        val root = JSON.parseObject(response.body) ?: return null
        val value = root.getJSONObject("data") ?: root
        return parsePanelRecord(value)
    }

    private fun updatePanel(authHeader: String, panelId: String, panel: JSONObject): ApiResponse =
        request(
            method = "PUT",
            endpoint = "/v2/panels/$panelId",
            authHeader = authHeader,
            body = JSONObject().apply { put("panel", panel) }
        )

    /**
     * 原位更新面板，带上读到的版本号。
     *
     * 报 30019（面板版本冲突）或 40030009（面板操作进行中）时，重新读一次版本再试一次
     * ——两次调用之间面板可能被别处改过，这是官方让「请重试」的场景。
     */
    private fun updatePanelWithVersion(
        authHeader: String,
        record: PanelRecord,
        items: List<JSONObject>,
        remarkOverride: String? = null
    ): Boolean {
        val plugin = BotShared.getPlugin()
        val remark = remarkOverride ?: record.remark.ifBlank { PANEL_REMARK }
        val response = updatePanel(authHeader, record.id, panelPayload(items, record.version, remark))
        if (response.success) return true
        if (!response.isRetryableConflict) {
            response.requireSuccess("更新指令面板 ${record.id}")
            return false
        }
        plugin?.log_warning(
            "指令面板 ${record.id} 版本冲突（version=${record.version}），" +
                "重新读取版本后重试一次"
        )
        Thread.sleep(PANEL_UPDATE_RETRY_DELAY_MS)
        val fresh = getPanel(authHeader, record.id)
        val retried = updatePanel(authHeader, record.id, panelPayload(items, fresh?.version, remark))
        retried.requireSuccess("重试更新指令面板 ${record.id}")
        return true
    }

    private fun syncPanelTargets(
        authHeader: String,
        panel: PanelRecord,
        desiredGroups: List<String>
    ) {
        val current = panel.groupOpenIds.toSet()
        val desired = desiredGroups.toSet()
        (desired - current).chunked(MAX_TARGETS_PER_REQUEST).forEach {
            updateTargets(authHeader, panel.id, "add", it)
        }
        (current - desired).chunked(MAX_TARGETS_PER_REQUEST).forEach {
            updateTargets(authHeader, panel.id, "del", it)
        }
    }

    private fun updateTargets(
        authHeader: String,
        panelId: String,
        operation: String,
        groups: List<String>
    ) {
        if (groups.isEmpty()) return
        val response = request(
            method = "PUT",
            endpoint = "/v2/panels/$panelId/target",
            authHeader = authHeader,
            body = JSONObject().apply {
                put("op", operation)
                put("group_openids", JSONArray(groups))
            }
        )
        response.requireSuccess("更新指令面板关联群 $panelId")
    }

    private fun deletePanel(authHeader: String, panelId: String) {
        val response = request(
            method = "DELETE",
            endpoint = "/v2/panels/$panelId",
            authHeader = authHeader
        )
        response.requireSuccess("删除重复指令面板 $panelId")
    }

    private fun createPanel(authHeader: String, body: JSONObject): String? {
        val response = request(
            method = "POST",
            endpoint = "/v2/panels",
            authHeader = authHeader,
            body = body
        )
        if (!response.success) {
            val hint = if (response.body.contains("30013")) {
                "；机器人账号可能已有 20 个非 HuHoBot 面板，请在开放平台清理旧面板后重试"
            } else {
                ""
            }
            throw IllegalStateException(
                "创建指令面板失败，HTTP ${response.status}: ${response.body}$hint"
            )
        }
        val result = JSON.parseObject(response.body) ?: return null
        return result.getString("panel_id")
            ?: result.getJSONObject("data")?.getString("panel_id")
    }

    private fun request(
        method: String,
        endpoint: String,
        authHeader: String,
        body: JSONObject? = null
    ): ApiResponse {
        val connection = URL("$API_BASE$endpoint").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", authHeader)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                    it.write(body.toJSONString())
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            ApiResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private data class PanelRecord(
        val id: String,
        val remark: String,
        val groupOpenIds: List<String>,
        /** 面板当前版本号；原位更新时必须回传，null 表示接口没给。 */
        val version: Int? = null,
        /** QQ 面板 target_type：all = 全局面板（不能对它做 targets 增删），specific = 指定群。 */
        val targetType: String = "specific"
    )

    private data class ApiResponse(val status: Int, val body: String) {
        val success: Boolean get() = status in 200..299

        /**
         * 这两种都值得原样重试：
         * 30019 面板版本冲突（读到的 version 已过期）、40030009 面板操作进行中。
         * 官方对两者的排查建议都是「请稍后重试」。
         */
        val isRetryableConflict: Boolean
            get() = body.contains("30019") || body.contains("30009")

        fun requireSuccess(action: String) {
            if (!success) {
                val hint = when {
                    body.contains("30013") ->
                        "；请同时检查面板总数、单面板条目数、命令名（显示宽度 14）和描述（显示宽度 30）"
                    body.contains("30019") ->
                        "；面板版本冲突，请稍后重试"
                    body.contains("30009") ->
                        "；面板操作进行中，官方写操作限频 10 QPM，请稍后重试"
                    else -> ""
                }
                throw IllegalStateException("$action 失败，HTTP $status: $body$hint")
            }
        }
    }
}
