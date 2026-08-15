package cn.huohuas001.bot.events

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.events.commands.AdministrationCommands
import cn.huohuas001.bot.events.commands.AuthenticationCommands
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.PublicCommands
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.impl.ListenerHost
import java.util.concurrent.CopyOnWriteArrayList

/** QQ 群消息事件入口，负责群限制、命令分发和全量聊天转发。 */
class GroupMessageHandler(
    private val plugin: HuHoBot
) : ListenerHost() {
    private val commands = CopyOnWriteArrayList<BaseCommand>()

    init {
        registerCommand(PublicCommands())
        registerCommand(AdministrationCommands())
        registerCommand(AuthenticationCommands())
    }

    fun registerCommand(command: BaseCommand) {
        commands.add(command)
    }

    /** 公域机器人只有在被 @ 时才会收到此事件。 */
    @EventReceiver
    fun onGroupMessage(event: GroupMessageEvent) {
        val groupId = event.groupOpenId ?: event.groupId
        val content = event.rawMessage.content ?: return

        if(!content.contains("查信息")){
            if (!isAllowedGroup(groupId)) return
        }
        if (dispatchCommand(event)) return
        forwardFullGroupMessage(groupId, event)
    }

    private fun isAllowedGroup(groupId: String): Boolean {
        val allowedGroups = plugin.getGroupOpenIdList()
        return allowedGroups.isEmpty() || groupId in allowedGroups
    }

    private fun dispatchCommand(event: GroupMessageEvent): Boolean {
        for (command in commands) {
            try {
                if (command.handleMessage(plugin, event)) return true
            } catch (error: Exception) {
                plugin.log_error("指令处理异常: ${error.message}")
            }
        }
        return false
    }

    private fun forwardFullGroupMessage(groupId: String, event: GroupMessageEvent) {
        val enabled = CommandRepositories.groupSettings
            .fullForwarding(groupId, plugin.getFullAmount())
        if (!enabled || !plugin.getChatFormat().postChat) return

        val message = event.rawMessage.content ?: return
        val senderName = event.sender?.username?: "unknown"
        plugin.broadcastMessage(plugin.formatGroupMessage(senderName, plugin.auditText(message)))
    }
}
