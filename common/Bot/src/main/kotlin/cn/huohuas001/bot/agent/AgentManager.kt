package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.state.CommandRepositories
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AI Agent 编排器：管理 /agent 会话、驱动 AI function calling 循环，
 * 并在手动审批模式下通过 QQ 按钮卡片请求管理员审批命令执行。
 */
object AgentManager {

    private const val ACTION_PREFIX = "huhobot:agent:"
    private const val MAX_STEPS = 15
    private const val EXECUTE_TIMEOUT_SECONDS = 15L

    /** 对话上下文窗口：始终保留首条 system 与最近 MAX_CONTEXT_MESSAGES - 1 条消息，防止历史无限增长超出模型上下文。 */
    private const val MAX_CONTEXT_MESSAGES = 40

    /** QQ 群 Markdown 单条消息的安全长度上限，超出时按行拆分多条发送，保证内容完整不截断。 */
    private const val MAX_MESSAGE_CHARS = 3000

    private val sessions = ConcurrentHashMap<String, AgentSession>()

    // ---------------------------------------------------------------- 命令入口

    /** 处理 /agent 命令，启动一个 AI 会话。 */
    fun startAgent(plugin: HuHoBot, event: GroupMessageEvent, task: String) {
        val config = plugin.getAgentConfig()
        if (config == null || !config.usable) {
            event.sendMessage("AI Agent 未启用或未配置，请检查配置中的 agent 部分（enabled、base-url、api-key、model）")
            return
        }
        if (task.isBlank()) {
            event.sendMessage("用法：/agent <任务描述>")
            return
        }
        event.sendMessage("已收到任务，AI 助手开始处理…")

        val groupOpenId = event.metadata?.getString("group_openid")
            ?: event.groupOpenId
            ?: event.groupId
        val requestUserId = event.sender?.openid ?: event.sender?.id ?: ""

        // 查找同一用户现有的会话，复用上下文（包括已完成的会话，直到用户手动 /newsession 清除）
        val existing = sessions.values.firstOrNull {
            it.requestUserId == requestUserId && it.awaitingApproval == null
        }

        val session: AgentSession
        val isNewSession: Boolean

        if (existing != null) {
            session = existing
            isNewSession = false
            session.finished = false
            session.messages.add(userMessage("请帮我：$task"))
        } else {
            session = AgentSession(UUID.randomUUID().toString(), groupOpenId, requestUserId, task)
            sessions[session.sessionId] = session
            isNewSession = true

            val guide = loadCommandGuide()
            session.messages.add(systemMessage(
                "你是部署在 Minecraft 服务器管理机器人中的 AI 助手。你通过调用工具帮助管理员完成服务器管理任务。\n" +
                    "服务器信息：\n" +
                    "- 平台：${plugin.getPlatform()}\n" +
                    "- 服务器版本：${plugin.getServerVersion()}\n" +
                    "- 机器人插件版本：${plugin.getPluginVersion()}\n" +
                    "规则：\n" +
                    "1. 只调用与当前任务相关的工具，不要滥用工具。做任务时不要调用无关工具。\n" +
                    "2. read_server_logs 仅在排查错误、异常或需要确认服务器状态时才调用，不要无故读取服务器日志。\n" +
                    "3. 需要执行服务器命令时必须使用 run_command 工具执行，禁止只把命令展示给用户而不调用 run_command。\n" +
                    "4. 生成涉及物品数据的命令前（如 /give、/item、/summon、/data、/loot、/clear），必须先调用 load_skill 加载对应的 SKILL 文档获取正确语法，再根据 SKILL 内容生成命令并调用 run_command 执行。\n" +
                    "   - 生成物品相关命令时，先加载 components（数据组件参考）+ 对应命令的 SKILL（give/item/summon/data/loot/clear）\n" +
                    "   - 例如：用户要给一把附魔钻石剑 → 先调用 load_skill(components) → 再调用 load_skill(give) → 再生成命令\n" +
                    "5. 命令帮助中的权限名（如 minecraft.command.give）仅用于说明，命令中无需使用。\n" +
                    "6. 所有回复使用中文，简洁友好。" +
                    (if (guide.isNotEmpty()) "\n\n以下是 Minecraft 命令语法速查（完整内容通过 load_skill 获取）：\n$guide" else "")
            ))
            session.messages.add(userMessage("请帮我：$task"))
        }

        plugin.submitAsync { runLoop(plugin, session, config) }
    }

    // ---------------------------------------------------------------- AI 循环

    private fun runLoop(plugin: HuHoBot, session: AgentSession, config: AgentConfig) {
        if (session.finished) return
        val client = AgentApiClient(config.baseUrl, config.apiKey, config.model)
        val tools = AgentTools.buildTools()
        var steps = 0

        while (true) {
            if (session.stopped) {
                sendToGroup(plugin, session, "⏹️ 任务已紧急停止")
                finish(plugin, session)
                return
            }
            if (++steps > MAX_STEPS) {
                sendToGroup(plugin, session, AgentMessageFormatter.error("处理步骤过多，任务已中止"))
                finish(plugin, session)
                return
            }

            val result = try {
                client.chat(trimContext(session.messages), tools)
            } catch (error: Throwable) {
                plugin.log_error("Agent AI 调用失败: ${error.message}")
                sendToGroup(plugin, session, AgentMessageFormatter.error(error.message ?: "未知错误"))
                finish(plugin, session)
                return
            }

            // 推理模型的思考过程：仅当模型支持 reasoning 时输出，否则不输出思考
            val reasoning = result.reasoning?.trim().orEmpty()
            if (reasoning.isNotEmpty()) {
                sendToGroup(plugin, session, AgentMessageFormatter.thinking(reasoning))
            }

            val toolCalls = result.toolCalls
            if (toolCalls == null || toolCalls.isEmpty()) {
                // 最终回复
                val content = result.content?.trim().orEmpty()
                if (content.isNotEmpty()) {
                    sendToGroup(plugin, session, AgentMessageFormatter.reply(content))
                }
                finish(plugin, session)
                return
            }

            // 记录 assistant 的调用请求
            session.messages.add(assistantMessage(result.content, toolCalls))

            // 逐个处理工具调用；run_command 手动模式会挂起等待审批
            for (index in 0 until toolCalls.size) {
                val toolCall = toolCalls.getJSONObject(index) ?: continue
                val toolCallId = toolCall.getString("id") ?: "call_$index"
                val function = toolCall.getJSONObject("function") ?: continue
                val functionName = function.getString("name") ?: continue
                val query = AgentTools.parseArguments(function.getString("arguments"))

                when (functionName) {
                    AgentTools.TOOL_GET_PLUGIN_LIST -> {
                        val result0 = AgentTools.getPluginList(plugin)
                        session.messages.add(toolMessage(toolCallId, result0.aiText))
                        sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(result0.displayTitle, result0.displayContent))
                    }

                    AgentTools.TOOL_GET_COMMAND_HELP -> {
                        val result0 = AgentTools.getCommandHelp(plugin, query)
                        session.messages.add(toolMessage(toolCallId, result0.aiText))
                        sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(result0.displayTitle, result0.displayContent))
                    }

                    AgentTools.TOOL_READ_SERVER_LOGS -> {
                        val result0 = AgentTools.getServerLogs(plugin, query)
                        session.messages.add(toolMessage(toolCallId, result0.aiText))
                        sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(result0.displayTitle, result0.displayContent))
                    }

                    AgentTools.TOOL_RUN_COMMAND -> {
                        val command = query.getString("command")?.trim().orEmpty()
                        if (command.isBlank()) {
                            session.messages.add(toolMessage(toolCallId, "命令为空，无法执行。"))
                            continue
                        }
                        if (config.commandMode == AgentCommandMode.AUTO) {
                            val output = executeCommand(plugin, command)
                            session.messages.add(toolMessage(toolCallId, "命令已执行，输出：\n$output"))
                            sendToGroup(plugin, session, AgentMessageFormatter.executedCard(command, output))
                        } else {
                            requestApproval(plugin, session, toolCallId, command)
                            return  // 等待审批，审批完成后再继续循环
                        }
                    }

                    AgentTools.TOOL_LOAD_SKILL -> {
                        val skillName = query.getString("skill")?.trim().orEmpty()
                        sendToGroup(plugin, session, AgentMessageFormatter.skillLoading(skillName))
                        val result0 = AgentTools.loadSkill(query)
                        session.messages.add(toolMessage(toolCallId, result0.aiText))
                    }

                    // ── QQ 群管理工具 ──
                    AgentTools.TOOL_GET_GROUP_INFO,
                    AgentTools.TOOL_GET_BOT_STATE,
                    AgentTools.TOOL_GET_JOIN_REQUESTS,
                    AgentTools.TOOL_APPROVE_JOIN_REQUEST,
                    AgentTools.TOOL_GET_MUTE_STATUS,
                    AgentTools.TOOL_SET_MEMBER_MUTE,
                    AgentTools.TOOL_LIST_AUTO_APPROVE_POLICIES,
                    AgentTools.TOOL_CREATE_AUTO_APPROVE_POLICY,
                    AgentTools.TOOL_UPDATE_AUTO_APPROVE_POLICY,
                    AgentTools.TOOL_DELETE_AUTO_APPROVE_POLICY,
                    AgentTools.TOOL_EXECUTE_AUTO_APPROVE_POLICY,
                    AgentTools.TOOL_UPDATE_WHITELIST_USERS -> {
                        val starter = QClient.getStarter()
                        if (starter == null) {
                            session.messages.add(toolMessage(toolCallId, "QQ Bot 未启动，无法调用群管理 API。"))
                            continue
                        }
                        val result0 = when (functionName) {
                            AgentTools.TOOL_GET_GROUP_INFO -> AgentTools.getGroupInfo(starter, query)
                            AgentTools.TOOL_GET_BOT_STATE -> AgentTools.getBotState(starter, query)
                            AgentTools.TOOL_GET_JOIN_REQUESTS -> AgentTools.getJoinRequests(starter, query)
                            AgentTools.TOOL_APPROVE_JOIN_REQUEST -> AgentTools.approveJoinRequest(starter, query)
                            AgentTools.TOOL_GET_MUTE_STATUS -> AgentTools.getMuteStatus(starter, query)
                            AgentTools.TOOL_SET_MEMBER_MUTE -> AgentTools.setMemberMute(starter, query)
                            AgentTools.TOOL_LIST_AUTO_APPROVE_POLICIES -> AgentTools.listAutoApprovePolicies(starter, query)
                            AgentTools.TOOL_CREATE_AUTO_APPROVE_POLICY -> AgentTools.createAutoApprovePolicy(starter, query)
                            AgentTools.TOOL_UPDATE_AUTO_APPROVE_POLICY -> AgentTools.updateAutoApprovePolicy(starter, query)
                            AgentTools.TOOL_DELETE_AUTO_APPROVE_POLICY -> AgentTools.deleteAutoApprovePolicy(starter, query)
                            AgentTools.TOOL_EXECUTE_AUTO_APPROVE_POLICY -> AgentTools.executeAutoApprovePolicy(starter, query)
                            AgentTools.TOOL_UPDATE_WHITELIST_USERS -> AgentTools.updateWhitelistUsers(starter, query)
                            else -> AgentTools.ToolResult("未知工具: $functionName")
                        }
                        session.messages.add(toolMessage(toolCallId, result0.aiText))
                        if (result0.displayTitle.isNotEmpty()) {
                            sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(result0.displayTitle, result0.displayContent))
                        }
                    }

                    else -> {
                        session.messages.add(toolMessage(toolCallId, "未知工具：$functionName"))
                    }
                }
            }
        }
    }

    /** 发送执行审批卡片并挂起会话。 */
    private fun requestApproval(plugin: HuHoBot, session: AgentSession, toolCallId: String, command: String) {
        val approvalId = UUID.randomUUID().toString()
        session.awaitingApproval = AgentSession.PendingApproval(approvalId, toolCallId, command)
        plugin.sendMarkdownToGroup(
            session.groupOpenId,
            AgentMessageFormatter.approvalCard(command),
            buildApprovalKeyboard(approvalId)
        )
    }

    /** 执行服务器命令并返回输出文本。 */
    private fun executeCommand(plugin: HuHoBot, command: String): String {
        return try {
            val execution = plugin.dispatchCommand(command)
                .get(EXECUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            stripFormattingCodes(execution.getRawString()).ifBlank { "命令执行完成（无输出）" }
        } catch (error: java.util.concurrent.TimeoutException) {
            "命令执行超时"
        } catch (error: Throwable) {
            // 找到根本原因（Brigadier CommandSyntaxException 等），跳过 Bukkit 包装层
            var root: Throwable? = error
            while (root?.cause != null) root = root.cause
            val rootMsg = root?.toString()?.let { msg ->
                // 去掉 "org.bukkit.command.CommandException: Unhandled exception executing ... 前缀"
                val idx = msg.indexOf("com.mojang.brigadier")
                if (idx >= 0) msg.substring(idx) else msg
            } ?: error.toString()
            "命令执行失败：${stripFormattingCodes(rootMsg)}"
        }
    }

    /** 剥离所有终端格式化代码（§x、&x、ANSI 转义序列），避免在 QQ 群显示乱码。 */
    private fun stripFormattingCodes(text: String): String =
        text.replace(Regex("§."), "")
            .replace(Regex("&[0-9a-fk-orA-FK-OR]"), "")
            .replace(Regex("\u001b\\[[0-9;]*m"), "")

    /** 读取 1.20.5+ 命令语法参考（agent-command-guide.md 资源）。 */
    private fun loadCommandGuide(): String {
        return try {
            val stream = AgentManager::class.java.classLoader.getResourceAsStream("agent-command-guide.md") ?: return ""
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    // ---------------------------------------------------------------- 审批回调

    /** 处理 QQ 按钮点击回调。 */
    fun onInteraction(plugin: HuHoBot, event: InterActionEvent) {
        val interaction = event.interAction
        val data = interaction?.data?.resolved?.button_data ?: return
        if (!data.startsWith(ACTION_PREFIX)) return

        val groupOpenId = interaction.group_openid ?: return
        val memberOpenId = interaction.group_member_openid ?: ""
        plugin.log_info("Agent 审批回调: group=$groupOpenId, member=$memberOpenId, data=$data")

        // event.response(0) 调用 QQ API 确认交互，但该端点返回 405（jsoup 在抛异常前打日志），
        // 不影响审批功能，跳过调用以消除日志噪音。

        val parts = data.split(":")
        if (parts.size < 4) return
        val approvalId = parts[2]
        val decision = parts[3]

        val session = sessions.values.firstOrNull { it.awaitingApproval?.approvalId == approvalId } ?: return
        val pending = session.awaitingApproval ?: return

        if (session.stopped) {
            session.awaitingApproval = null
            return
        }

        if (session.groupOpenId != groupOpenId) {
            plugin.log_warning("Agent 审批群不匹配，已忽略 approvalId=$approvalId")
            sendToGroup(plugin, session, AgentMessageFormatter.noPermissionNotice())
            return
        }
        if (!isApprovalAuthorized(plugin, session, groupOpenId, memberOpenId)) {
            sendToGroup(plugin, session, AgentMessageFormatter.noPermissionNotice())
            return
        }

        session.awaitingApproval = null

        if (decision == "yes") {
            sendToGroup(plugin, session, AgentMessageFormatter.approvedNotice(memberOpenId))
            val output = executeCommand(plugin, pending.command)
            session.messages.add(toolMessage(pending.toolCallId, "命令已由管理员批准执行，输出：\n$output"))
            sendToGroup(plugin, session, AgentMessageFormatter.executedCard(pending.command, output))
        } else {
            sendToGroup(plugin, session, AgentMessageFormatter.rejectedNotice(memberOpenId))
            session.messages.add(toolMessage(pending.toolCallId, "命令执行被管理员拒绝，请向用户说明无需执行。"))
        }

        val config = plugin.getAgentConfig()
        if (config != null && config.usable) {
            plugin.submitAsync { runLoop(plugin, session, config) }
        }
    }

    /** 仅管理员（群主/群管理员由按钮权限平台保证）与发起者可审批。 */
    private fun isApprovalAuthorized(
        plugin: HuHoBot,
        session: AgentSession,
        groupId: String,
        memberOpenId: String
    ): Boolean {
        plugin.log_info("Agent 审批权限检查: memberOpenId=$memberOpenId, requestUserId=${session.requestUserId}, group=$groupId")
        if (memberOpenId.isBlank()) {
            plugin.log_warning("Agent 审批: group_member_openid 为空，无法验证权限")
            return false
        }
        if (memberOpenId == session.requestUserId) return true
        if (memberOpenId in plugin.getAdminList()) return true
        if (CommandRepositories.administrators.contains(groupId, memberOpenId)) return true
        plugin.log_warning("Agent 审批: $memberOpenId 不在管理员列表中")
        return false
    }

    // ---------------------------------------------------------------- 工具方法

    private fun buildApprovalKeyboard(approvalId: String): Keyboard {
        val builder = Keyboard.KeyboardBuilder.create()
        val row = builder.addRow()

        row.addButton()
            .setLabel("同意")
            .setVisitedLabel("已同意")
            .setStyle(1)
            .setActionType(1)
            .setActionData("${ACTION_PREFIX}${approvalId}:yes")
            .setPermissionType(1)
            .setUnSupportTips("请升级QQ客户端后再操作")
            .build()

        row.addButton()
            .setLabel("拒绝")
            .setVisitedLabel("已拒绝")
            .setStyle(0)
            .setActionType(1)
            .setActionData("${ACTION_PREFIX}${approvalId}:no")
            .setPermissionType(1)
            .setUnSupportTips("请升级QQ客户端后再操作")
            .build()

        row.build()
        return builder.build()
    }

    private fun sendToGroup(plugin: HuHoBot, session: AgentSession, content: String) {
        if (session.stopped || content.isBlank()) return
        splitMessages(content).forEach { chunk ->
            if (session.stopped) return
            plugin.sendMarkdownToGroup(session.groupOpenId, chunk)
        }
    }

    /** 将超长内容按行拆分为多条，保证 QQ 群展示完整；内容不超过上限时原样返回。 */
    private fun splitMessages(content: String): List<String> {
        if (content.length <= MAX_MESSAGE_CHARS) return listOf(content)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in content.lineSequence()) {
            if (current.isNotEmpty() && current.length + line.length + 1 > MAX_MESSAGE_CHARS) {
                chunks.add(current.toString())
                current.clear()
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    /** 上下文窗口裁剪：保留首条 system 与最近的消息，避免无限增长。 */
    private fun trimContext(messages: List<JSONObject>): List<JSONObject> {
        if (messages.size <= MAX_CONTEXT_MESSAGES) return messages
        return listOf(messages.first()) + messages.takeLast(MAX_CONTEXT_MESSAGES - 1)
    }

    private fun finish(plugin: HuHoBot, session: AgentSession) {
        session.finished = true
        // 不立即移除会话：保留上下文供同一用户后续追问，由 cleanupExpired() 按超时清理
    }

    /** 手动清除同一用户在指定群的会话上下文。 */
    fun clearSession(plugin: HuHoBot, groupOpenId: String, requestUserId: String) {
        val removed = sessions.entries.removeIf { (_, session) ->
            session.requestUserId == requestUserId
                && session.groupOpenId == groupOpenId
        }
        if (removed) {
            plugin.log_info("Agent 会话已手动清除：group=$groupOpenId user=$requestUserId")
        }
    }

    /** 紧急停止：立即终止指定用户的所有活跃会话，阻止后续输出和 AI 处理。 */
    fun stopAgent(plugin: HuHoBot, groupOpenId: String, requestUserId: String) {
        var count = 0
        sessions.values.forEach { session ->
            if (session.requestUserId == requestUserId && session.groupOpenId == groupOpenId && !session.finished) {
                session.stopped = true
                session.awaitingApproval = null
                count++
            }
        }
        plugin.log_info("Agent 紧急停止：group=$groupOpenId user=$requestUserId 停止了 $count 个会话")
    }

    private fun systemMessage(content: String): JSONObject =
        JSONObject().apply { put("role", "system"); put("content", content) }

    private fun userMessage(content: String): JSONObject =
        JSONObject().apply { put("role", "user"); put("content", content) }

    private fun assistantMessage(content: String?, toolCalls: JSONArray): JSONObject =
        JSONObject().apply {
            put("role", "assistant")
            put("content", content)
            put("tool_calls", toolCalls)
        }

    private fun toolMessage(toolCallId: String, content: String): JSONObject =
        JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("content", content)
        }
}
