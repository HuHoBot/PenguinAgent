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

    // ------------------------------------------------------------ 上下文压缩

    @Commands(command = "压缩", describe = "把较早的 AI 对话压缩成摘要，保留最近若干条", onlyAdmin = true)
    fun compress(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val config = plugin.getAgentConfig()
        if (config == null || !config.usable) {
            reply(plugin, event, "AI Agent 未启用或未配置，无法压缩上下文")
            return
        }
        val session = AgentManager.findSession(groupId(event), userId(event))
        if (session == null) {
            reply(plugin, event, "你当前没有会话上下文，先用 /agent 发个任务再来压缩")
            return
        }
        if (session.awaitingApproval != null) {
            reply(plugin, event, "当前会话正在等待审批，先处理完再压缩")
            return
        }

        val beforeTokens = maxOf(
            session.lastPromptTokens,
            AgentContextManager.estimateTokens(session.messages)
        )
        reply(plugin, event, "⏳ 正在压缩上下文（当前约 $beforeTokens token）…")
        plugin.submitAsync {
            AgentContextManager.compress(plugin, session, config) { message ->
                plugin.replyMarkdown(event, if (message.startsWith("✅")) message else "⚠️ $message")
            }
            val index = if (session.messages.firstOrNull()?.getString("role") == "system") 1 else 0
            val summary = session.messages.getOrNull(index)?.getString("content").orEmpty()
            if (summary.startsWith(AgentContextManager.SUMMARY_MARK)) {
                plugin.replyMarkdown(event, AgentContextManager.summaryCard(summary))
            }
        }
    }

    @Commands(command = "压缩token", describe = "查看或设置上下文压缩的目标 token 数", onlyAdmin = true)
    fun compressToken(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val argument = params.trim()
        if (argument.isEmpty()) {
            reply(plugin, event, "当前压缩目标：${plugin.getAgentCompressTarget()} token\n用法：/压缩token <数值>")
            return
        }
        val value = argument.toIntOrNull()
        if (value == null || value < 200) {
            reply(plugin, event, "请填写不小于 200 的整数")
            return
        }
        if (!plugin.setAgentCompressTarget(value)) {
            reply(plugin, event, "写入配置失败，请查看控制台日志")
            return
        }
        reply(plugin, event, "压缩目标已设为 $value token")
        plugin.log_info("管理员 ${userId(event)} 设置压缩目标为 $value token")
    }

    @Commands(command = "压缩保留", describe = "查看或设置压缩时保留的最近消息条数", onlyAdmin = true)
    fun compressKeep(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val argument = params.trim()
        if (argument.isEmpty()) {
            reply(plugin, event, "当前压缩保留：${plugin.getAgentCompressKeep()} 条\n用法：/压缩保留 <条数>")
            return
        }
        val value = argument.toIntOrNull()
        if (value == null || value < 1) {
            reply(plugin, event, "请填写不小于 1 的整数")
            return
        }
        if (!plugin.setAgentCompressKeep(value)) {
            reply(plugin, event, "写入配置失败，请查看控制台日志")
            return
        }
        reply(plugin, event, "压缩时将保留最近 $value 条消息")
        plugin.log_info("管理员 ${userId(event)} 设置压缩保留 $value 条")
    }

    @Commands(command = "删除信息", describe = "删除 AI 上下文中最早的消息", onlyAdmin = true)
    fun deleteMessages(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val session = AgentManager.findSession(groupId(event), userId(event))
        if (session == null) {
            reply(plugin, event, "你当前没有会话上下文")
            return
        }
        val count = params.trim().toIntOrNull() ?: plugin.getAgentTruncateDrop()
        if (count < 1) {
            reply(plugin, event, "请填写不小于 1 的整数")
            return
        }
        val before = session.messages.size
        val beforeTokens = maxOf(
            session.lastPromptTokens,
            AgentContextManager.estimateTokens(session.messages)
        )
        val removed = AgentContextManager.dropOldest(session.messages, count)
        if (removed <= 0) {
            reply(plugin, event, "上下文里已经没有可删除的消息了")
            return
        }
        session.lastPromptTokens = 0
        val afterTokens = AgentContextManager.estimateTokens(session.messages)
        reply(
            plugin, event,
            "🗑️ 已删除最早的 $removed 条消息\n" +
                "- 消息条数：$before → ${session.messages.size}\n" +
                "- 估算 token：$beforeTokens → $afterTokens"
        )
        plugin.log_info(
            "管理员 ${userId(event)} 删除上下文消息：messages=$before->${session.messages.size}, " +
                "tokens≈$beforeTokens->$afterTokens"
        )
    }
}
