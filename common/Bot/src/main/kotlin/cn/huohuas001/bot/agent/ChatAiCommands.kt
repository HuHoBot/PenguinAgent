package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.events.commands.CommandSupport
import cn.huohuas001.bot.events.commands.Commands
import cn.huohuas001.bot.messages.Messages
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 聊天 AI 的群管理命令。 */
class ChatAiCommands : CommandSupport() {

    @Commands(command = "聊天清空", describe = "清除本群的聊天 AI 共享记录", onlyAdmin = true)
    fun clearChat(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        ChatAiManager.clear(groupId(event))
        sendMessage(event, Messages.t("chat-ai.cleared"))
    }

    @Commands(command = "聊天压缩", describe = "把本群较早的聊天 AI 记录压缩成摘要", onlyAdmin = true)
    fun compressChat(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val config = plugin.getChatAiEnabled()
        if (!config) {
            reply(plugin, event, Messages.t("chat-ai.status", "off", plugin.getLanguage(), "0"))
            return
        }
        reply(plugin, event, Messages.t("chat-ai.compressed") + "…")
        plugin.submitAsync {
            val result = ChatAiManager.compress(plugin, groupId(event))
            plugin.replyMarkdown(event, result)
        }
    }

    @Commands(command = "聊天状态", describe = "查看本群聊天 AI 状态")
    fun chatStatus(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val enabled = plugin.getChatAiEnabled()
        reply(
            plugin, event,
            Messages.t(
                "chat-ai.status",
                if (enabled) "on" else "off",
                plugin.getLanguage(),
                countOf(event).toString()
            )
        )
    }

    private fun countOf(event: GroupMessageEvent): Int = ChatAiManager.sizeOf(groupId(event))
}
