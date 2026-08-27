package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.events.commands.CommandSupport
import cn.huohuas001.bot.events.commands.Commands
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** AI Agent 指令：仅管理员可用，触发 AI 处理服务器管理任务。 */
class AgentCommands : CommandSupport() {

    @Commands(command = "agent", describe = "AI Agent 管理任务", onlyAdmin = true)
    fun agent(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        AgentManager.startAgent(plugin, event, params.trim())
    }

    @Commands(command = "newsession", describe = "清除 AI 会话上下文", onlyAdmin = true)
    fun newsession(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val groupOpenId = event.metadata?.getString("group_openid")
            ?: event.groupOpenId
            ?: event.groupId
        val userId = event.sender?.openid ?: event.sender?.id ?: ""
        AgentManager.clearSession(plugin, groupOpenId, userId)
        sendMessage(event, "✅ 会话上下文已清除，下次 /agent 将开始全新对话。")
    }

    @Commands(command = "stop", describe = "紧急停止 AI 任务", onlyAdmin = true)
    fun stop(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val groupOpenId = event.metadata?.getString("group_openid")
            ?: event.groupOpenId
            ?: event.groupId
        val userId = event.sender?.openid ?: event.sender?.id ?: ""
        AgentManager.stopAgent(plugin, groupOpenId, userId)
        sendMessage(event, "⏹️ 已紧急停止所有 AI 任务")
    }
}
