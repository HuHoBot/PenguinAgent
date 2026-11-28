package agent.events.commands

import agent.HuHoBot
import agent.datapack.AdministratorAccessMode
import agent.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 需要群管理员权限的命令。 */
class AdministrationCommands : CommandSupport() {

    @Commands(command = "查管理", describe = "查询管理员状态", onlyAdmin = true)
    fun queryAdmin(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            sendMessage(event, "请指定要查询的管理员OpenId")
            return
        }

        val isAdmin = CommandRepositories.administrators.contains(groupId(event), params.trim())
        reply(plugin, event, if (isAdmin) "此人是管理员" else "此人不是管理员")
    }

    @Commands(command = "加管理", describe = "添加管理员", onlyAdmin = true)
    fun addAdmin(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            sendMessage(event, "请指定要添加的管理员OpenId")
            return
        }

        val target = params.trim()
        CommandRepositories.administrators.add(groupId(event), target)
        reply(plugin, event, "已为本群添加管理员:$target")
    }

    @Commands(command = "删管理", describe = "删除管理员", onlyAdmin = true)
    fun removeAdmin(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            sendMessage(event, "请指定要删除的管理员OpenId")
            return
        }

        val target = params.trim()
        CommandRepositories.administrators.remove(groupId(event), target)
        reply(plugin, event, "已为本群删除管理员:$target")
    }

    @Commands(command = "管理方式", describe = "设置管理员判定方式", onlyAdmin = true)
    fun adminMode(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return

        if (params.isBlank()) {
            reply(
                plugin,
                event,
                "当前管理员判定方式：${modeName(effectiveMode(plugin, event))}\n" +
                    "可选方式：QQ / 手动 / 双重"
            )
            return
        }

        val mode = when (params.trim()) {
            "QQ" -> AdministratorAccessMode.QQ
            "手动" -> AdministratorAccessMode.MANUAL
            "双重" -> AdministratorAccessMode.BOTH
            else -> null
        }
        if (mode == null) {
            sendMessage(event, "无效的判定方式。可选：QQ / 手动 / 双重")
            return
        }

        CommandRepositories.groupSettings.setAdministratorMode(groupId(event), mode)
        reply(plugin, event, "已将本群管理员判定方式设置为：${modeName(mode)}")
    }

    @Commands(command = "添加白名单", describe = "添加玩家白名单", onlyAdmin = true)
    fun addWhitelist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            sendMessage(event, "参数不正确")
            return
        }
        val command = plugin.applyPlaceholders(params, plugin.getWhiteList().addCommand.replace("{name}", params))
        executeGameCommand(plugin, event, command, direct = true)
    }

    @Commands(command = "删除白名单", describe = "删除玩家白名单", onlyAdmin = true)
    fun removeWhitelist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            sendMessage(event, "参数不正确")
            return
        }
        val command = plugin.applyPlaceholders(params, plugin.getWhiteList().delCommand.replace("{name}", params))
        executeGameCommand(plugin, event, command, direct = true)
    }

    @Commands(command = "查白名单", describe = "查看白名单列表", onlyAdmin = true)
    fun queryWhitelist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val suffix = params.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        executeGameCommand(plugin, event, "whitelist list$suffix", direct = true)
    }

    @Commands(command = "执行命令", describe = "执行服务器命令", onlyAdmin = true)
    fun runServerCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            sendMessage(event, "参数不正确")
            return
        }
        executeGameCommand(plugin, event, params, direct = true)
    }

    @Commands(command = "管理员执行", describe = "管理员执行自定义命令", onlyAdmin = true)
    fun runAdminCustomCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            sendMessage(event, "参数不正确")
            return
        }
        executeCustomCommand(plugin, event, params, admin = true)
    }

    @Commands(command = "全量", describe = "切换全量聊天转发", onlyAdmin = true)
    fun fullAmount(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return

        val groupId = groupId(event)
        val enabled = when (params.trim().lowercase()) {
            "开", "on", "true" -> true
            "关", "off", "false" -> false
            else -> CommandRepositories.groupSettings.fullForwarding(groupId, plugin.getFullAmount())
        }
        if (params.isNotBlank()) {
            CommandRepositories.groupSettings.setFullForwarding(groupId, enabled)
        }

        reply(plugin, event, "本群全量转发：${if (enabled) "已开启" else "已关闭"}")
    }

    @Commands(command = "blockMotd", describe = "屏蔽本群 MOTD 查询", onlyAdmin = true)
    fun blockMotd(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        CommandRepositories.groupSettings.setMotdBlocked(groupId(event), true)
        reply(plugin, event, "本群已设置为：屏蔽 MOTD 查询")
    }

    @Commands(command = "unblockMotd", describe = "解除本群 MOTD 屏蔽", onlyAdmin = true)
    fun unblockMotd(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        CommandRepositories.groupSettings.setMotdBlocked(groupId(event), false)
        reply(plugin, event, "本群已设置为：解除屏蔽 MOTD 查询")
    }

    private fun modeName(mode: AdministratorAccessMode): String = when (mode) {
        AdministratorAccessMode.QQ -> "QQ群主/管理员判定"
        AdministratorAccessMode.MANUAL -> "手动添加管理员判定"
        AdministratorAccessMode.BOTH -> "QQ或手动管理员判定"
    }

    private fun effectiveMode(plugin: HuHoBot, event: GroupMessageEvent): AdministratorAccessMode {
        val defaultMode = AdministratorAccessMode.fromConfig(plugin.getAdminMode())
        return CommandRepositories.groupSettings.administratorMode(groupId(event), defaultMode)
    }

    // ── 群管理（踢人 / 撤回消息）──
    // 需要 QQ 开放平台授予的群管理白名单权限（移除群成员 / 撤回消息），
    // 且操作者需是本群管理员或群主。

    @Commands(command = "踢人", describe = "移除群成员（需群管理权限）", onlyAdmin = true)
    fun kickMember(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val target = targetOpenId(event, params)
        if (target.isNullOrBlank()) {
            reply(plugin, event, "用法：/踢人 @某人（群里 @机器人 后直接发也可以）")
            return
        }
        val starter = agent.QClient.getStarter()
        if (starter == null) {
            reply(plugin, event, "QQ 机器人尚未就绪，稍后再试")
            return
        }
        val error = agent.ai.GroupManagementApi.removeMember(
            starter, groupId(event), target, rejectAddRequest = true
        )
        if (error == null) {
            reply(plugin, event, "已移除该成员，并拒绝其后续加群申请")
        } else {
            reply(plugin, event, "踢出失败：$error\n（请确认机器人已被授予「移除群成员」白名单权限，且你/机器人有群管理权限）")
        }
    }

    @Commands(command = "撤回", describe = "撤回群消息（需消息ID）", onlyAdmin = true)
    fun recallMessage(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val messageId = params.trim()
        if (messageId.isBlank()) {
            reply(plugin, event, "用法：撤回 <消息ID>（消息 ID 可在机器人日志或消息事件中查到）")
            return
        }
        val starter = agent.QClient.getStarter()
        if (starter == null) {
            reply(plugin, event, "QQ 机器人尚未就绪，稍后再试")
            return
        }
        val error = agent.ai.GroupManagementApi.recallMessage(
            starter, groupId(event), messageId
        )
        reply(plugin, event, if (error == null) "已撤回该消息" else "撤回失败：$error")
    }

    /** 取 @ 的成员 openid，没有 @ 时用参数文本（openid）。 */
    private fun targetOpenId(event: GroupMessageEvent, params: String): String? {
        // 群聊 v2 事件的 @ 在 metadata.mentions 中，rawMessage.mentions 常为空。
        val mentioned = runCatching {
            val list = event.metadata?.getJSONArray("mentions") ?: return@runCatching null
            (0 until list.size).mapNotNull { i ->
                val m = list.getJSONObject(i) ?: return@mapNotNull null
                if (m.getBoolean("is_you") == true) null else m.getString("member_openid")
            }.firstOrNull { !it.isNullOrBlank() }
        }.getOrNull()
        return mentioned?.takeIf(String::isNotBlank) ?: params.trim().takeIf(String::isNotBlank)
    }

}
