package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** QQ 群指令面板同步：复用受管面板，避免重启时不断创建新面板。 */
object MenuManager {
    private const val API_BASE = "https://api.sgroup.qq.com"
    private const val PANEL_REMARK = "HuHoBot Penguin 指令面板"
    private const val MAX_ITEMS = 20
    private const val MAX_NAME_DISPLAY_WIDTH = 14
    private const val MAX_DESCRIPTION_DISPLAY_WIDTH = 30
    private const val MAX_TARGETS_PER_REQUEST = 20
    private const val MAX_DUPLICATE_DELETES_PER_SYNC = 10
    private val LEGACY_REMARKS = setOf(PANEL_REMARK, "HuHoBot 指令面板")

    fun syncGroupPanels(
        starter: Starter,
        groupOpenIds: List<String>,
        builtInCommands: Collection<RegisteredCommand>,
        customCommands: Collection<RegisteredCommand> = emptyList(),
        priorities: Map<String, Int> = emptyMap(),
        showAdminCommands: Boolean = false
    ) {
        val plugin = BotShared.getPlugin()
        val groups = groupOpenIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (groups.isEmpty()) {
            plugin?.log_warning("面板同步跳过: 未配置群 OpenId")
            return
        }

        try {
            val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
            val token = start0.accessToken
            if (token == null) {
                plugin?.log_warning("面板同步跳过: accessToken 为空")
                return
            }
            val authHeader = "QQBot $token"
            val items = buildItems(builtInCommands, customCommands, priorities, showAdminCommands)
            if (items.isEmpty()) {
                plugin?.log_warning("没有已注册的 QQ 命令，跳过指令面板同步")
                return
            }

            val limitedItems = if (items.size > MAX_ITEMS) {
                plugin?.log_warning(
                    "已注册命令数 ${items.size} 超过单个面板上限 $MAX_ITEMS，超出部分仅在帮助中显示"
                )
                items.take(MAX_ITEMS)
            } else {
                items
            }
            plugin?.log_info("指令面板入选: ${limitedItems.joinToString { it.getString("name") }}")
            val panel = panelPayload(limitedItems)

            val allPanels = listPanels(authHeader, "group")
            val managed = allPanels.filter { it.remark in LEGACY_REMARKS }
            val primary = managed.firstOrNull()?.let { getPanel(authHeader, it.id) ?: it }

            if (primary != null) {
                // 原位更新不会消耗新的“面板数量”名额，是避免 30013 的关键。
                updatePanel(authHeader, primary.id, panel)
                syncPanelTargets(authHeader, primary, groups)

                val duplicates = managed.drop(1)
                duplicates.take(MAX_DUPLICATE_DELETES_PER_SYNC).forEach {
                    deletePanel(authHeader, it.id)
                }
                if (duplicates.size > MAX_DUPLICATE_DELETES_PER_SYNC) {
                    plugin?.log_warning(
                        "检测到 ${duplicates.size} 个历史重复指令面板，本次已清理 " +
                            "$MAX_DUPLICATE_DELETES_PER_SYNC 个；下次同步会继续清理"
                    )
                }
                plugin?.log_info(
                    "指令面板已更新 (panel_id=${primary.id}, commands=${limitedItems.size}, " +
                        "duplicates=${duplicates.size})"
                )
                return
            }

            val initialGroups = groups.take(MAX_TARGETS_PER_REQUEST)
            val panelBody = JSONObject().apply {
                put("scope", "group")
                put("target_type", "specific")
                put("group_openids", JSONArray(initialGroups))
                put("panel", panel)
            }
            val panelId = createPanel(authHeader, panelBody)
            if (panelId == null) {
                plugin?.log_warning("指令面板注册失败: 未获取到 panel_id")
                return
            }

            groups.drop(MAX_TARGETS_PER_REQUEST)
                .chunked(MAX_TARGETS_PER_REQUEST)
                .forEach { updateTargets(authHeader, panelId, "add", it) }
            plugin?.log_info("指令面板已创建 (panel_id=$panelId, commands=${limitedItems.size})")
        } catch (error: Exception) {
            plugin?.log_error("指令面板同步失败: ${error.message}")
        }
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
            if (name.isEmpty() || !seen.add(name.lowercase())) return@forEach
            if (displayWidth(name) > MAX_NAME_DISPLAY_WIDTH) {
                // 面板中的 name 就是实际插入输入框的命令，截断会生成不可用命令，因此跳过。
                plugin?.log_warning(
                    "命令面板跳过 '$name': 名称显示宽度超过 $MAX_NAME_DISPLAY_WIDTH"
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

    private fun panelPayload(items: List<JSONObject>): JSONObject = JSONObject().apply {
        put("remark", PANEL_REMARK)
        put("items", JSONArray(items))
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
        return PanelRecord(id, remark, targets)
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

    private fun updatePanel(authHeader: String, panelId: String, panel: JSONObject) {
        val response = request(
            method = "PUT",
            endpoint = "/v2/panels/$panelId",
            authHeader = authHeader,
            body = JSONObject().apply { put("panel", panel) }
        )
        response.requireSuccess("更新指令面板 $panelId")
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
        val groupOpenIds: List<String>
    )

    private data class ApiResponse(val status: Int, val body: String) {
        val success: Boolean get() = status in 200..299

        fun requireSuccess(action: String) {
            if (!success) {
                val hint = if (body.contains("30013")) {
                    "；请同时检查面板总数、单面板条目数、命令名（显示宽度 14）和描述（显示宽度 30）"
                } else {
                    ""
                }
                throw IllegalStateException("$action 失败，HTTP $status: $body$hint")
            }
        }
    }
}
