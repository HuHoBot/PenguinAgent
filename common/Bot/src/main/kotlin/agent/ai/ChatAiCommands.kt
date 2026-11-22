package agent.ai

import agent.HuHoBot
import agent.events.commands.CommandSupport
import agent.events.commands.Commands
import agent.messages.Messages
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 聊天 AI 的群管理命令。 */
class ChatAiCommands : CommandSupport() {
    @Commands(command = "AI", describe = "与群聊 AI 对话（只读）")
    fun chat(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if(!plugin.getChatAiEnabled()) { reply(plugin,event,"聊天 AI 未开启，请在 chat-ai.enabled 开启并配置接口"); return }
        if (params.isBlank()) { reply(plugin,event,"用法：/AI <你想说的话>"); return }
        // Acknowledge first: model calls can take seconds and a silent bot looks offline.
        agent.QClient.replyText(event, "聊天 AI 正在处理…")
        plugin.submitAsync {
            val handled = ChatAiManager.handle(plugin, groupId(event), event.sender?.openid ?: "",event.sender?.username ?: "群员",
                plugin.getChatAiTrigger()+" "+params) { answer -> agent.QClient.replyText(event,answer) }
            if (!handled) agent.QClient.replyText(event, "聊天 AI 未就绪：请检查 chat-ai 的接口地址、密钥与模型配置。")
        }
    }


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
            reply(plugin, event, statusText(false, plugin.getLanguage(), countOf(event)))
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
        reply(plugin,event,statusText(enabled,plugin.getLanguage(),countOf(event)))
    }

    companion object {
        internal fun statusText(enabled: Boolean, language: String, count: Int): String {
            val state=Messages.t(if(enabled) "chat-ai.enabled-on" else "chat-ai.enabled-off")
            // Accept both bundled named placeholders and older custom numbered translations.
            return Messages.named("chat-ai.status", mapOf("enabled" to state, "language" to language, "count" to count,
                "0" to state,"1" to language,"2" to count))
        }
    }

    private fun countOf(event: GroupMessageEvent): Int = ChatAiManager.sizeOf(groupId(event))
}
