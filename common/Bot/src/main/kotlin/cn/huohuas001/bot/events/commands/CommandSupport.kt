package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.datapack.AdministratorAccessMode
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * 迁移命令共享的上下文工具。
 *
 * 这里统一处理群/用户标识、管理员鉴权、敏感词审核和游戏命令回报，
 * 具体命令类只保留业务流程。
 */
abstract class CommandSupport : BaseCommand() {

    protected fun groupId(event: GroupMessageEvent): String =
        event.groupOpenId ?: event.groupId

    protected fun userId(event: GroupMessageEvent): String =
        event.sender?.openid ?: event.sender?.id ?: ""

    protected fun reply(plugin: HuHoBot, event: GroupMessageEvent, message: String) {
        event.sendMessage(plugin.auditText(message))
    }

    protected fun replyWithImg(plugin: HuHoBot, event: GroupMessageEvent, message: String,imgUrl: String) {
        plugin.replyWithImg(event,message,imgUrl)
    }

    protected fun requireAdmin(plugin: HuHoBot, event: GroupMessageEvent): Boolean {
        val groupId = groupId(event)
        val userId = userId(event)
        val qqAdmin = memberRole(event) in setOf("owner", "admin")
        val manualAdmin = userId in plugin.getAdminList() ||
            CommandRepositories.administrators.contains(groupId, userId)

        val defaultMode = AdministratorAccessMode.fromConfig(plugin.getAdminMode())
        val configuredMode = CommandRepositories.groupSettings.administratorMode(groupId, defaultMode)
        val allowed = when (configuredMode) {
            AdministratorAccessMode.QQ -> qqAdmin
            AdministratorAccessMode.MANUAL -> manualAdmin
            AdministratorAccessMode.BOTH -> qqAdmin || manualAdmin
        }

        if (!allowed) {
            event.sendMessage("你没有执行此命令的管理员权限")
        }
        return allowed
    }

    protected fun executeGameCommand(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        command: String,
        direct: Boolean
    ) {
        val outgoingCommand = if (direct) {
            command
        } else {
            "huhobot run ${groupId(event)} ${userId(event)} $command"
        }

        plugin.sendCommand(outgoingCommand).whenComplete { result, error ->
            when {
                error != null || result == null -> event.sendMessage("游戏桥接未配置或执行失败")
                else -> reply(
                    plugin,
                    event,
                    result.getRawString().ifBlank { "已发送执行请求" }
                )
            }
        }
    }

    protected fun executeCustomCommand(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        params: String,
        admin: Boolean
    ) {
        val type = if (admin) "adminrun" else "run"
        executeGameCommand(
            plugin = plugin,
            event = event,
            command = "huhobot $type ${groupId(event)} ${userId(event)} $params",
            direct = true
        )
    }

    private fun memberRole(event: GroupMessageEvent): String = try {
        event.sender?.meta?.getString("member_role")?.lowercase() ?: ""
    } catch (_: Exception) {
        ""
    }
}
