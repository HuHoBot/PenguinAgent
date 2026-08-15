package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.events.commands.CommandSupport
import cn.huohuas001.bot.events.commands.Commands
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** AI Agent 指令：仅管理员可用，触发 AI 处理服务器管理任务。 */
class AgentCommands : CommandSupport() {

    @Commands("agent", "Agent")
    fun agent(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        AgentManager.startAgent(plugin, event, params.trim())
    }

    @Commands("newsession", "Newsession")
    fun newsession(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val groupOpenId = event.metadata?.getString("group_openid")
            ?: event.groupOpenId
            ?: event.groupId
        val userId = event.sender?.openid ?: event.sender?.id ?: ""
        AgentManager.clearSession(plugin, groupOpenId, userId)
        event.sendMessage("✅ 会话上下文已清除，下次 /agent 将开始全新对话。")
    }

    @Commands("stop", "Stop")
    fun stop(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val groupOpenId = event.metadata?.getString("group_openid")
            ?: event.groupOpenId
            ?: event.groupId
        val userId = event.sender?.openid ?: event.sender?.id ?: ""
        AgentManager.stopAgent(plugin, groupOpenId, userId)
        event.sendMessage("⏹️ 已紧急停止所有 AI 任务")
    }
}
