package agent.ai

import agent.HuHoBot
import agent.InteractionOutcome
import agent.QClient
import agent.events.commands.CommandBlacklistGuard
import agent.state.CommandRepositories
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
    private const val EXECUTE_TIMEOUT_SECONDS = 15L
    private const val APPROVAL_TIMEOUT_SECONDS = 30L

    private val approvalScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Penguin-Agent-Approval").apply { isDaemon = true }
    }

    /** 对话上下文窗口：始终保留首条 system 与最近 MAX_CONTEXT_MESSAGES - 1 条消息，防止历史无限增长超出模型上下文。 */
    private const val MAX_CONTEXT_MESSAGES = 40

    /** QQ 群 Markdown 单条消息的安全长度上限，超出时按行拆分多条发送，保证内容完整不截断。 */
    private const val MAX_MESSAGE_CHARS = 3000

    private val sessions = ConcurrentHashMap<String, AgentSession>()

    /**
     * 群成员信息查询接口是 QQ 白名单能力，普通 bot 调用会报 11253。
     * 一旦确认不可用就不再重试，避免每次审批回调都白等一次网络请求。
     */
    @Volatile
    private var platformRoleApiDisabled = false

    // ---------------------------------------------------------------- 命令入口

    /** 处理 /agent 命令，启动一个 AI 会话。 */
    fun startAgent(plugin: HuHoBot, event: GroupMessageEvent, task: String) {
        val config = plugin.getAgentConfig()
        if (config == null || !config.usable) {
            sendGroupMessage(event, "AI Agent 未启用或未配置，请检查配置中的 agent 部分（enabled、base-url、api-key、model）")
            return
        }
        if (task.isBlank()) {
            sendGroupMessage(event, "用法：/agent <任务描述>")
            return
        }

        val groupOpenId = event.metadata?.getString("group_openid")
            ?: event.groupOpenId
            ?: event.groupId
        val requestUserId = event.sender?.openid ?: event.sender?.id ?: ""

        // 提取 @提及的 member_openid → 用户名映射：喂给 AI 用 openid，显示用用户名
        val mentionMap = buildMentionMap(event)
        val fullTask = if (mentionMap.isNotEmpty()) {
            "[提及用户映射 - 直接使用下面的 member_openid 调用工具，不要调用 get_group_members]\n$mentionMap\n任务：$task"
        } else {
            task
        }.trim()

        // 查找同一用户在本群现有的会话，复用上下文（包括已完成的会话，直到用户手动 /newsession 清除）。
        // 必须按群隔离：bot 绑多个群时，跨群复用会把任务卡片发到另一个群，
        // 点审批时又会被"群不匹配"拦下，同时上下文也串到了别的群。
        val existing = sessions.values.firstOrNull {
            it.requestUserId == requestUserId && it.groupOpenId == groupOpenId && it.awaitingApproval == null
        }

        // 会话正在执行时不能复用：往同一个 messages 里追加消息会和正在跑的循环抢列表。
        // 这里明确拒绝，而不是悄悄再开一个会话，避免同一个群里出现两份并行的 AI 对话。
        if (existing != null && (existing.isRunning || existing.waiting)) {
            sendGroupMessage(event, "上一个任务还在执行中，请等它结束，或用 /stop 停止后再发起新任务")
            return
        }

        val session: AgentSession
        val isNewSession: Boolean

        if (existing != null) {
            session = existing
            isNewSession = false
            session.finished = false
            session.stepsUsed = 0
            // /stop 只置位 stopped，会话本身保留（对话历史不清除）。
            // 复用前必须复位，否则新任务会在 runLoop 入口被立刻判定为"已停止"而直接结束。
            session.stopped = false
            session.memberNames.putAll(mentionMapValues(event))
            session.messages.add(userMessage("请帮我：" + AiContentGuard.maskInbound(plugin, fullTask)))
        } else {
            session = AgentSession(UUID.randomUUID().toString(), groupOpenId, requestUserId, fullTask)
            session.memberNames.putAll(mentionMapValues(event))
            sessions[session.sessionId] = session
            isNewSession = true

            val guide = loadCommandGuide()
            session.messages.add(systemMessage(
                config.systemPrompt.ifBlank { "你是部署在 Minecraft 服务器管理机器人中的 AI 助手。你通过调用工具帮助管理员完成服务器管理任务。" } + "\n" +
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
                    "6. 所有回复使用中文，简洁友好。\n" +
                    "7. QQ 群管理操作（禁言、审批、查询等）：所有工具已自动绑定当前群的 group_openid，无需用户提供。\n" +
                    "   - 任务开头如果有 [提及用户映射]，你必须直接使用其中给出的 member_openid 调用对应工具，绝对不要调用 get_group_members。\n" +
                    "   - 例如任务说禁言某人，映射里有某人->member_openid: XXX，直接用XXX调用set_member_mute。\n" +
                    "   - 禁言前先调用 get_bot_state 确认机器人是否为群管理员（member_role 必须是 admin 或 owner），否则禁言会失败。\n" +
                    "   - 禁止询问用户 group_openid 或 member_openid。" +
                    (if (guide.isNotEmpty()) "\n\n以下是 Minecraft 命令语法速查（完整内容通过 load_skill 获取）：\n$guide" else "")
            ))
            session.messages.add(userMessage("请帮我：" + AiContentGuard.maskInbound(plugin, fullTask)))
        }

        sendGroupMessage(event, "已收到任务，AI 助手开始处理…")
        plugin.submitAsync { runLoop(plugin, session, config) }
    }

    /** 从事件原始 JSON 提取 @提及的 openid→用户名 映射（跳过机器人自己），返回多行文本。 */
    private fun buildMentionMap(event: GroupMessageEvent): String {
        val sb = StringBuilder()
        val mentions = event.metadata?.getJSONArray("mentions") ?: return ""
        for (i in mentions.indices) {
            val m = mentions.getJSONObject(i) ?: continue
            if (m.getBoolean("is_you") == true) continue
            val name = m.getString("username") ?: continue
            val openId = m.getString("member_openid") ?: continue
            sb.appendLine("$name → member_openid: $openId")
        }
        return sb.toString().trim()
    }

    /** 返回 openid → 用户名 的映射（跳过机器人自己）。 */
    private fun mentionMapValues(event: GroupMessageEvent): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val mentions = event.metadata?.getJSONArray("mentions") ?: return result
        for (i in mentions.indices) {
            val m = mentions.getJSONObject(i) ?: continue
            if (m.getBoolean("is_you") == true) continue
            val name = m.getString("username") ?: continue
            val openId = m.getString("member_openid") ?: continue
            result[openId] = name
        }
        return result
    }

    // ---------------------------------------------------------------- AI 循环

    private fun runLoop(plugin: HuHoBot, session: AgentSession, config: AgentConfig) {
        if (session.finished) return
        // 同一会话只允许一个 runLoop 在跑：/agent 与审批回调都会触发，
        // 重复进入会让两个循环并发写 messages，裁出孤立 tool 消息或重复执行命令。
        if (!session.tryBeginRun()) {
            plugin.log_warning("Agent 会话已在执行中，忽略重复的 runLoop：${session.sessionId}")
            return
        }
        try {
            session.waiting = false
            session.waitResult?.let { session.messages.add(it); session.waitResult = null }
            runLoopBody(plugin, session, config)
        } finally {
            if (session.stopped) cancelOutstandingCalls(session)
            session.endRun()
            drainResume(plugin, session, config)
        }
    }

    private fun drainResume(plugin: HuHoBot, session: AgentSession, config: AgentConfig) {
        if (session.isRunning || session.stopped || session.finished) return
        if (session.resumeRequested.compareAndSet(true, false)) {
            plugin.submitAsync { runLoop(plugin, session, config) }
        }
    }

    private fun runLoopBody(plugin: HuHoBot, session: AgentSession, config: AgentConfig) {
        // 只有开了深度思考、且没开思考引导时才向接口附加思考参数；
        // 深度思考与思考引导互斥，开启思考引导后深度思考相关设置一律不生效
        val client = if (config.effectiveThink) {
            AgentApiClient(config.baseUrl, config.apiKey, config.model, config.thinkLevel, config.thinkParam,
                maxTokens = config.maxTokens, timeoutSeconds = config.timeoutSeconds, extraHeaders = config.extraHeaders)
        } else {
            AgentApiClient(config.baseUrl, config.apiKey, config.model,
                maxTokens = config.maxTokens, timeoutSeconds = config.timeoutSeconds, extraHeaders = config.extraHeaders)
        }
        val tools = filterAllowedTools(AgentTools.buildTools(), config.allowedTools)

        while (true) {
            if (session.stopped) {
                sendToGroup(plugin, session, "⏹️ 任务已紧急停止")
                finish(plugin, session)
                return
            }
            if (session.pendingToolCalls.isEmpty()) {
                if (++session.stepsUsed > config.maxSteps) {
                    sendToGroup(plugin, session, AgentMessageFormatter.error("已达到本次任务的 ${config.maxSteps} 步上限，任务已中止"))
                    finish(plugin, session)
                    return
                }

                val result = try {
                    client.chat(prepareMessages(session, config), tools)
                } catch (error: Throwable) {
                    plugin.log_error("Agent AI 调用失败: ${error.message}")
                    sendToGroup(plugin, session, AgentMessageFormatter.error(error.message ?: "未知错误"))
                    finish(plugin, session)
                    return
                }
                client.thinkingWarning?.let { plugin.log_info("Agent 思考参数提示：$it") }
                result.usage?.let { session.lastPromptTokens = it.promptTokens }
                plugin.log_info(
                    "Agent 诊断: think=${config.think}, guide=${config.thinkGuide}, vis=${config.thinkVisibility}, " +
                        "promptLen=${config.effectiveThinkGuidePrompt.length}, " +
                        "rawLen=${client.lastRawContent?.length ?: -1}, " +
                        "rawHasThink=${client.lastRawContent?.contains("[思考]")}, " +
                        "reasoningLen=${result.reasoning?.length ?: -1}, tools=${result.toolCalls?.size ?: 0}"
                )

                // 思考过程往哪儿送由 think-visibility 决定：只写日志 / 只发群 / 都发。
                // 推理模型与思考引导（[思考]）产出的思考内容都走这一条路径。
                val reasoning = result.reasoning?.trim().orEmpty()
                if (reasoning.isNotEmpty()) {
                    when (config.thinkVisibility) {
                        AgentThinkVisibility.LOG ->
                            plugin.log_info("Agent 思考过程（仅日志）：\n$reasoning")

                        AgentThinkVisibility.GROUP ->
                            sendToGroup(plugin, session, AgentMessageFormatter.thinking(reasoning))

                        AgentThinkVisibility.BOTH -> {
                            plugin.log_info("Agent 思考过程（仅日志）：\n$reasoning")
                            sendToGroup(plugin, session, AgentMessageFormatter.thinking(reasoning))
                        }
                    }
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

                for (index in 0 until toolCalls.size) {
                    toolCalls.getJSONObject(index)?.let { session.pendingToolCalls.addLast(it) }
                }
            }

            // The queue survives pauses; no call in a multi-call response is silently discarded.
            while (!session.pendingToolCalls.isEmpty()) {
                if (session.stopped) return
                val toolCall = session.pendingToolCalls.removeFirst()
                val toolCallId = toolCall.getString("id") ?: "call_${UUID.randomUUID()}"
                val function = toolCall.getJSONObject("function") ?: continue
                val functionName = function.getString("name") ?: continue
                val query = AgentTools.parseArguments(function.getString("arguments"))

                if (config.allowedTools.isNotEmpty() && functionName !in config.allowedTools) {
                    session.messages.add(toolMessage(toolCallId, "此工具不在管理员配置的 agent.tools 允许列表内。"))
                    continue
                }

                when (functionName) {
                    AgentTools.TOOL_CALCULATE -> session.messages.add(toolMessage(toolCallId, AgentTools.calculate(query).aiText))
                    AgentTools.TOOL_RANDOM_CHOICE -> session.messages.add(toolMessage(toolCallId, AgentTools.randomChoice(query).aiText))
                    AgentTools.TOOL_WAIT -> {
                        val seconds = try { AgentTools.waitSeconds(query, config.maxWaitSeconds) }
                        catch (error: IllegalArgumentException) {
                            session.messages.add(toolMessage(toolCallId, "等待参数无效：${error.message}"))
                            continue
                        }
                        session.waiting = true
                        session.pendingWait = approvalScheduler.schedule({
                            session.pendingWait = null
                            if (!session.stopped && !session.finished) {
                                session.waitResult = toolMessage(toolCallId, "已异步等待 $seconds 秒，继续处理。")
                                session.resumeRequested.set(true)
                                drainResume(plugin, session, config)
                            }
                        }, seconds.toLong(), TimeUnit.SECONDS)
                        return
                    }
                    AgentTools.TOOL_GET_PLUGIN_LIST -> {
                        val result0 = AgentTools.getPluginList(plugin)
                        session.messages.add(toolMessage(toolCallId, result0.aiText))
                        showFetchResult(plugin, session, result0)
                    }

                    AgentTools.TOOL_GET_COMMAND_HELP -> {
                        val result0 = AgentTools.getCommandHelp(plugin, query)
                        session.messages.add(toolMessage(toolCallId, result0.aiText))
                        showFetchResult(plugin, session, result0)
                    }

                    AgentTools.TOOL_READ_SERVER_LOGS -> {
                        val result0 = AgentTools.getServerLogs(plugin, query)
                        session.messages.add(toolMessage(toolCallId, result0.aiText))
                        showFetchResult(plugin, session, result0)
                    }

                    AgentTools.TOOL_RUN_COMMAND -> {
                        val command = query.getString("command")?.trim().orEmpty()
                        if (command.isBlank()) {
                            session.messages.add(toolMessage(toolCallId, "命令为空，无法执行。"))
                            continue
                        }
                        // 命令黑名单检查：前缀、命名空间、别名都在 guard 里统一规范化后再比较
                        if (CommandBlacklistGuard.isBlocked(plugin, command)) {
                            session.messages.add(toolMessage(toolCallId, "此命令已被管理员禁止执行（黑名单）：$command"))
                            sendToGroup(plugin, session, AgentMessageFormatter.error("命令 $command 在黑名单中，无法执行"))
                            continue
                        }
                        when (executeWithMode(plugin, session, config, toolCallId, command)) {
                            "awaiting" -> return  // 等待审批，审批完成后再继续循环
                            else -> Unit // 已完成（执行/拒绝/回填），继续下一轮
                        }
                    }

                    AgentTools.TOOL_LOAD_SKILL -> {
                        val skillName = query.getString("skill")?.trim().orEmpty()
                        sendToGroup(plugin, session, AgentMessageFormatter.skillLoading(skillName))
                        val result0 = AgentTools.loadSkill(query)
                        session.messages.add(toolMessage(toolCallId, result0.aiText))
                    }

                    AgentTools.TOOL_GET_SERVER_STATUS -> {
                        val text = AgentTools.getServerStatusText(plugin)
                        session.messages.add(toolMessage(toolCallId, text))
                        showFetchResult(plugin, session, AgentTools.ToolResult("服务器实时状态", text))
                    }

                    AgentTools.TOOL_GET_ONLINE_LIST -> {
                        val online = plugin.getOnlineList()
                        val text = "在线 ${online.size} 人：${online.joinToString("、").ifBlank { "无" }}"
                        session.messages.add(toolMessage(toolCallId, text))
                        showFetchResult(plugin, session, AgentTools.ToolResult("在线玩家", text))
                    }

                    AgentTools.TOOL_GET_PLAYER_INFO -> {
                        val player = query.getString("player")?.trim().orEmpty()
                        val text = if (player.isEmpty()) "缺少 player 参数"
                            else plugin.getPlayerStatsText(player) ?: "玩家 $player 不在线或平台未支持"
                        session.messages.add(toolMessage(toolCallId, text))
                        showFetchResult(plugin, session, AgentTools.ToolResult("玩家数据", text))
                    }

                    AgentTools.TOOL_GET_PLAYER_INVENTORY -> {
                        val player = query.getString("player")?.trim().orEmpty()
                        val text = if (player.isEmpty()) "缺少 player 参数"
                            else plugin.getPlayerInventory(player) ?: "暂无 $player 的背包数据（需在线或已有离线快照）"
                        session.messages.add(toolMessage(toolCallId, text))
                        showFetchResult(plugin, session, AgentTools.ToolResult("背包内容(文本)", text))
                    }

                    AgentTools.TOOL_CREATE_TIMED_TASK,
                    AgentTools.TOOL_DELETE_TIMED_TASK -> {
                        val text = AgentTools.executeTimedTaskTool(functionName, query)
                        session.messages.add(toolMessage(toolCallId, text))
                        showFetchResult(plugin, session, AgentTools.ToolResult("定时任务", text))
                    }

                    AgentTools.TOOL_WEB_SEARCH -> {
                        val query0 = query.getString("query")?.trim().orEmpty()
                        val text = if (config.searchProvider.isBlank() || config.searchProvider == "none") {
                            "未启用搜索：请在配置中设置 agent.search.provider（exa/brave/jina/pmeta/zhipu/doubao/deepseek/custom/js/none）"
                        } else {
                            val results = SearchProvider.search(
                                SearchProvider.Config(
                                    provider = config.searchProvider,
                                    apiUrl = config.searchApiUrl,
                                    apiKey = config.searchApiKey,
                                    resultCount = 5,
                                    fetchPages = config.searchFetchPages,
                                    jsCode = config.searchJsCode
                                ),
                                query0
                            )
                            SearchProvider.summarize(results)
                        }
                        session.messages.add(toolMessage(toolCallId, text))
                    }

                    AgentTools.TOOL_SAVE_MEMORY -> {
                        val content0 = query.getString("content")?.trim().orEmpty()
                        val ok = if (content0.isEmpty()) false else MemoryStore.add(session.groupOpenId, content0)
                        session.messages.add(toolMessage(toolCallId, if (ok) "已保存到本群长期记忆" else "保存失败（内容为空或已存在）"))
                    }

                    AgentTools.TOOL_RECALL_MEMORY -> {
                        val kw = query.getString("keyword")?.trim().orEmpty()
                        val items = if (kw.isEmpty()) MemoryStore.list(session.groupOpenId).takeLast(20)
                            else MemoryStore.search(session.groupOpenId, kw).takeLast(20)
                        val text = if (items.isEmpty()) "没有匹配的长期记忆"
                        else items.mapIndexed { i, e -> "${i + 1}. ${e.text}" }.joinToString("\n")
                        session.messages.add(toolMessage(toolCallId, text))
                    }

                    AgentTools.TOOL_LIST_MEMORY -> {
                        val items = MemoryStore.list(session.groupOpenId).takeLast(30)
                        val text = if (items.isEmpty()) "本群暂无长期记忆"
                        else items.mapIndexed { i, e -> "${i + 1}. ${e.text}" }.joinToString("\n")
                        session.messages.add(toolMessage(toolCallId, text))
                    }

                    TtsManager.TOOL_SPEAK_TTS -> {
                        val result = TtsManager.speak(
                            sessionKey = session.groupOpenId,
                            mode = query.getString("mode").orEmpty(),
                            text = query.getString("text").orEmpty(),
                            voice = query.getString("voice"),
                            description = query.getString("description"),
                            voiceFile = query.getString("voice_file"),
                            style = query.getString("style")
                        )
                        val text = if (!result.ok) result.message
                        else if (QClient.sendGroupVoice(session.groupOpenId, result.url)) "语音已发送到 QQ 群"
                        else "语音已合成但发送失败：请检查 tts.public-base-url 是否公网可达以及 QQ 富媒体权限"
                        session.messages.add(toolMessage(toolCallId, text))
                        showFetchResult(plugin, session, AgentTools.ToolResult("语音消息", text))
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
                        // 危险操作（禁言、审批、改策略）需要审批；查询类直接执行
                        val needsApproval = functionName in setOf(
                            AgentTools.TOOL_SET_MEMBER_MUTE,
                            AgentTools.TOOL_APPROVE_JOIN_REQUEST,
                            AgentTools.TOOL_CREATE_AUTO_APPROVE_POLICY,
                            AgentTools.TOOL_UPDATE_AUTO_APPROVE_POLICY,
                            AgentTools.TOOL_DELETE_AUTO_APPROVE_POLICY,
                            AgentTools.TOOL_EXECUTE_AUTO_APPROVE_POLICY,
                            AgentTools.TOOL_UPDATE_WHITELIST_USERS
                        )
                        if (needsApproval && config.commandMode == AgentCommandMode.MANUAL) {
                            requestToolApproval(plugin, session, toolCallId, functionName, query)
                            return  // 等待审批
                        }
                        executeGroupTool(plugin, session, toolCallId, functionName, query)
                    }

                    else -> {
                        session.messages.add(toolMessage(toolCallId, "未知工具：$functionName"))
                    }
                }
            }
        }
    }

    /** 获取类工具结果默认只在群里展示，配置关闭后不再回显。 */
    private fun showFetchResult(plugin: HuHoBot, session: AgentSession, result: AgentTools.ToolResult) {
        if (plugin.isAgentFetchResultHidden()) return
        if (result.displayTitle.isEmpty()) return
        sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(result.displayTitle, result.displayContent))
    }

    /** 发送执行审批卡片并挂起会话。 */
    private fun requestApproval(plugin: HuHoBot, session: AgentSession, toolCallId: String, command: String) {
        val approvalId = UUID.randomUUID().toString()
        val pending = AgentSession.PendingApproval(approvalId, toolCallId, command)
        session.awaitingApproval = pending
        pending.messageId = plugin.sendMarkdownToGroup(
            session.groupOpenId,
            AgentMessageFormatter.approvalCard(command),
            buildApprovalKeyboard(approvalId)
        )
        scheduleApprovalTimeout(plugin, session, approvalId)
    }

    /** 发送 QQ 群管理工具审批卡片并挂起会话。 */
    private fun requestToolApproval(
        plugin: HuHoBot,
        session: AgentSession,
        toolCallId: String,
        toolName: String,
        query: JSONObject
    ) {
        val approvalId = UUID.randomUUID().toString()
        val description = describeToolAction(session, toolName, query)
        val pending = AgentSession.PendingApproval(approvalId, toolCallId, description, toolName, query)
        session.awaitingApproval = pending
        pending.messageId = plugin.sendMarkdownToGroup(
            session.groupOpenId,
            AgentMessageFormatter.approvalCard(description),
            buildApprovalKeyboard(approvalId)
        )
        scheduleApprovalTimeout(plugin, session, approvalId)
    }

    /** 审批卡片 30 秒未处理则自动拒绝。 */
    private fun scheduleApprovalTimeout(plugin: HuHoBot, session: AgentSession, approvalId: String) {
        approvalScheduler.schedule({
            val pending = session.awaitingApproval ?: return@schedule
            if (pending.approvalId != approvalId) return@schedule
            if (session.stopped) {
                session.awaitingApproval = null
                pending.messageId?.let { QClient.recallMessage(session.groupOpenId, it) }
                return@schedule
            }
            session.awaitingApproval = null
            pending.messageId?.let { QClient.recallMessage(session.groupOpenId, it) }
            sendToGroup(plugin, session, AgentMessageFormatter.approvalTimeoutNotice())
            session.messages.add(toolMessage(pending.toolCallId, "审批超时未处理，操作已自动拒绝，请向用户说明无需执行。"))
            val config = plugin.getAgentConfig()
            if (config != null && config.usable) {
                plugin.submitAsync { runLoop(plugin, session, config) }
            }
        }, APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** 生成群管理工具的审批描述文本（用用户名显示，openid 仅给 AI）。 */
    private fun describeToolAction(session: AgentSession, toolName: String, query: JSONObject): String {
        return when (toolName) {
            AgentTools.TOOL_SET_MEMBER_MUTE -> {
                val op = query.getString("op") ?: ""
                val minutes = query.getInteger("minutes") ?: 1
                val member = session.displayName(query.getString("member_openid") ?: "?")
                if (op == "add") "禁言成员 ${member}（${minutes} 分钟）" else "解除禁言 $member"
            }
            AgentTools.TOOL_APPROVE_JOIN_REQUEST -> "审批入群申请：${session.displayName(query.getString("member_openid") ?: "?")}"
            AgentTools.TOOL_CREATE_AUTO_APPROVE_POLICY -> "创建入群自动审批策略"
            AgentTools.TOOL_UPDATE_AUTO_APPROVE_POLICY -> "修改入群自动审批策略 ${query.getString("strategy_id") ?: "?"}"
            AgentTools.TOOL_DELETE_AUTO_APPROVE_POLICY -> "删除入群自动审批策略 ${query.getString("strategy_id") ?: "?"}"
            AgentTools.TOOL_EXECUTE_AUTO_APPROVE_POLICY -> "执行入群自动审批策略 ${query.getString("strategy_id") ?: "?"}"
            AgentTools.TOOL_UPDATE_WHITELIST_USERS -> "修改自动审批白名单"
            else -> "执行群管理操作 $toolName"
        }
    }

    /** 执行 QQ 群管理工具并回填结果。 */
    private fun executeGroupTool(
        plugin: HuHoBot,
        session: AgentSession,
        toolCallId: String,
        functionName: String,
        query: JSONObject
    ) {
        val starter = QClient.getStarter()
        if (starter == null) {
            session.messages.add(toolMessage(toolCallId, "QQ Bot 未启动，无法调用群管理 API。"))
            return
        }
        val defaultGroupOpenId = session.groupOpenId
        // 强制使用会话所在群：防止 AI 传入伪造/占位的 group_openid，导致 API 请求失败
        query.put("group_openid", defaultGroupOpenId)
        val result0 = when (functionName) {
            AgentTools.TOOL_GET_GROUP_INFO -> AgentTools.getGroupInfo(starter, query, defaultGroupOpenId)
            AgentTools.TOOL_GET_BOT_STATE -> AgentTools.getBotState(starter, query, defaultGroupOpenId)
            AgentTools.TOOL_GET_JOIN_REQUESTS -> AgentTools.getJoinRequests(starter, query, defaultGroupOpenId)
            AgentTools.TOOL_APPROVE_JOIN_REQUEST -> AgentTools.approveJoinRequest(starter, query, defaultGroupOpenId)
            AgentTools.TOOL_GET_MUTE_STATUS -> AgentTools.getMuteStatus(starter, query, defaultGroupOpenId)
            AgentTools.TOOL_SET_MEMBER_MUTE -> AgentTools.setMemberMute(starter, query, defaultGroupOpenId)
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
            // 展示给 QQ 的内容：把已知 member_openid 替换为用户名（aiText 保持 openid 供 AI 使用）
            val displayContent = replaceOpenIdsWithNames(session, result0.displayContent)
            sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(result0.displayTitle, displayContent))
        }
    }

    /** 把展示文本中的已知 member_openid 替换为用户名。 */
    private fun replaceOpenIdsWithNames(session: AgentSession, text: String): String {
        if (session.memberNames.isEmpty()) return text
        var result = text
        for ((openId, name) in session.memberNames) {
            result = result.replace(openId, name)
        }
        return result
    }

    /** 工具白名单过滤：allowedTools 为空 = 全部可用。 */
    private fun filterAllowedTools(tools: JSONArray, allowed: List<String>): JSONArray {
        if (allowed.isEmpty()) return tools
        val allow = allowed.map { it.trim().lowercase() }.toSet()
        val out = JSONArray()
        for (i in 0 until tools.size) {
            val tool = tools.getJSONObject(i) ?: continue
            val function = tool.getJSONObject("function") ?: continue
            val name = function.getString("name")?.lowercase() ?: continue
            if (name in allow) out.add(tool)
        }
        return out
    }

    /** 执行命令（AUTO 直接执行 / AI 审批 / MANUAL 人工审批），返回 null 表示已挂起等待审批。 */
    private fun executeWithMode(
        plugin: HuHoBot,
        session: AgentSession,
        config: AgentConfig,
        toolCallId: String,
        command: String
    ): String? {
        when (config.commandMode) {
            AgentCommandMode.AUTO -> {
                val output = executeCommand(plugin, command)
                session.messages.add(toolMessage(toolCallId, "命令已执行，输出：\n$output"))
                sendToGroup(plugin, session, AgentMessageFormatter.executedCard(command, output))
                return null
            }
            AgentCommandMode.AI -> {
                // AI 审批：干净对话审查，拒绝则把理由回填给工作 Agent
                val contextSummary = AgentContextManager.renderTranscript(
                    session.messages.drop(maxOf(0, session.messages.size - 8))
                )
                val verdict = AiApprover.review(
                    config,
                    userTask = session.task.ifBlank { "（未记录）" },
                    contextSummary = contextSummary,
                    pendingAction = "执行服务器命令：$command",
                    searchEnabled = config.searchProvider.isNotBlank() && config.searchProvider != "none"
                )
                if (verdict.approved) {
                    val output = executeCommand(plugin, command)
                    session.messages.add(toolMessage(toolCallId, "命令已通过 AI 审批并执行，输出：\n$output"))
                    sendToGroup(plugin, session, AgentMessageFormatter.executedCard(command, output))
                } else {
                    session.messages.add(
                        toolMessage(toolCallId, "AI 审批拒绝了该命令。理由：${verdict.reason}\n请向用户说明，不要再次尝试相同命令。")
                    )
                    sendToGroup(
                        plugin, session,
                        AgentMessageFormatter.error("AI 审批拦截了命令 $command：${verdict.reason}")
                    )
                }
                return null
            }
            else -> {
                requestApproval(plugin, session, toolCallId, command)
                return "awaiting"
            }
        }
    }
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

    /**
     * 处理 QQ 按钮点击回调。
     *
     * 回应时机由本方法决定：[respond] 必须在做出判断后立刻调用，
     * 但要在执行服务器命令之前调用，否则按钮会一直 loading。
     * 真正处理成功才回 0，被拒绝的分支回非零，避免客户端弹出误导性的「操作成功」。
     *
     * @return 是否接管了这次互动（决定监听器是否还要兜底回应）
     */
    fun onInteraction(plugin: HuHoBot, event: InterActionEvent, respond: (Int) -> Unit): Boolean {
        val interaction = event.interAction
        val data = interaction?.data?.resolved?.button_data ?: return false
        if (!data.startsWith(ACTION_PREFIX)) return false

        val groupOpenId = interaction.groupOpenid ?: return true
        val memberOpenId = interaction.groupMemberOpenid ?: ""
        plugin.log_info("Agent 审批回调: group=$groupOpenId, member=$memberOpenId, data=$data")

        val parts = data.split(":")
        if (parts.size < 4) {
            respond(InteractionOutcome.SILENT.code)
            return true
        }
        val approvalId = parts[2]
        val decision = parts[3]

        val session = sessions.values.firstOrNull { it.awaitingApproval?.approvalId == approvalId }
        if (session == null) {
            // 已处理过或已超时撤回，属正常情况，不必打扰任何人
            respond(InteractionOutcome.SILENT.code)
            return true
        }
        val pending = session.awaitingApproval ?: run {
            respond(InteractionOutcome.SILENT.code)
            return true
        }

        if (session.stopped) {
            session.awaitingApproval = null
            respond(InteractionOutcome.SILENT.code)
            pending.messageId?.let { QClient.recallMessage(groupOpenId, it) }
            return true
        }

        if (session.groupOpenId != groupOpenId) {
            plugin.log_warning("Agent 审批群不匹配，已忽略 approvalId=$approvalId")
            respond(InteractionOutcome.SILENT.code)
            sendToGroup(plugin, session, AgentMessageFormatter.noPermissionNotice())
            return true
        }
        if (!isApprovalAuthorized(plugin, session, groupOpenId, memberOpenId)) {
            plugin.log_warning("Agent 审批被无权限成员点击：approvalId=$approvalId, member=$memberOpenId")
            respond(InteractionOutcome.SILENT.code)
            sendToGroup(plugin, session, AgentMessageFormatter.noPermissionNotice())
            return true
        }

        // 走到这里才算真的批或拒，客户端可以提示「操作成功」
        respond(InteractionOutcome.HANDLED.code)
        session.awaitingApproval = null
        pending.messageId?.let { QClient.recallMessage(groupOpenId, it) }

        if (decision == "yes") {
            sendToGroup(plugin, session, AgentMessageFormatter.approvedNotice(session.displayName(memberOpenId)))
            if (pending.isTool) {
                // QQ 群管理工具：批准后执行
                executeGroupTool(plugin, session, pending.toolCallId, pending.toolName, pending.query ?: JSONObject())
                session.messages.add(toolMessage(pending.toolCallId, "操作已由管理员批准执行。"))
            } else {
                val output = executeCommand(plugin, pending.command)
                session.messages.add(toolMessage(pending.toolCallId, "命令已由管理员批准执行，输出：\n$output"))
                sendToGroup(plugin, session, AgentMessageFormatter.executedCard(pending.command, output))
            }
        } else {
            sendToGroup(plugin, session, AgentMessageFormatter.rejectedNotice(session.displayName(memberOpenId)))
            session.messages.add(toolMessage(pending.toolCallId, "操作被管理员拒绝，请向用户说明无需执行。"))
        }

        val config = plugin.getAgentConfig()
        if (config != null && config.usable) {
            plugin.submitAsync { runLoop(plugin, session, config) }
        }
        return true
    }

    /**
     * 审批权限，按优先级依次判断：
     * 1. 插件自有管理员名单：任务发起者、bot 全局管理员、本群手动管理员（主要依据）；
     * 2. 被动缓存：群消息事件 author 自带的 member_role（QQ 已开放，成员@过机器人即可确认）；
     * 3. 主动查询：群成员信息接口（白名单能力，普通 bot 拿不到，仅作兜底）。
     *
     * 按钮互动事件（INTERACTION_CREATE）不带角色，所以 QQ 侧身份只能靠 2/3 两条路补。
     */
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
        // 主要：插件自有的管理员名单
        if (memberOpenId == session.requestUserId) return true
        if (memberOpenId in plugin.getAdminList()) return true
        if (CommandRepositories.administrators.contains(groupId, memberOpenId)) return true
        // 次要一：被动缓存（群消息 author.member_role）
        if (CommandRepositories.memberRoles.isGroupAdmin(groupId, memberOpenId)) {
            plugin.log_info("Agent 审批: $memberOpenId 命中被动角色缓存（${CommandRepositories.memberRoles.role(groupId, memberOpenId)}），放行")
            return true
        }
        // 次要二：主动查询群成员信息接口
        if (isGroupAdminByPlatform(plugin, groupId, memberOpenId)) return true
        plugin.log_warning("Agent 审批: $memberOpenId 非管理员名单成员，且无法确认为本群群主/群管理员")
        return false
    }

    /**
     * 主动查询该成员在本群的身份。
     *
     * 该接口是白名单能力，普通 bot 返回 11253；一旦失败就置位 [platformRoleApiDisabled] 不再重试。
     */
    private fun isGroupAdminByPlatform(plugin: HuHoBot, groupId: String, memberOpenId: String): Boolean {
        if (platformRoleApiDisabled) return false
        val starter = QClient.getStarter() ?: return false
        val role = GroupManagementApi.getMemberRole(starter, groupId, memberOpenId)
        if (role == null) {
            platformRoleApiDisabled = true
            plugin.log_info("Agent 审批: 群成员信息接口不可用（白名单能力），后续仅用被动缓存与管理员名单判定")
            return false
        }
        val admin = role == "owner" || role == "admin"
        plugin.log_info("Agent 审批: $memberOpenId 主动查询到本群身份=$role，${if (admin) "放行" else "拒绝"}")
        return admin
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

    private fun sendGroupMessage(event: GroupMessageEvent, message: String) {
        val groupOpenId = event.metadata?.getString("group_openid")
            ?: event.groupOpenId
            ?: event.groupId
        QClient.sendTextToGroup(groupOpenId, message)
    }

    private fun sendToGroup(plugin: HuHoBot, session: AgentSession, content: String) {
        if (session.stopped || content.isBlank()) return
        // 出站违禁词过滤：正文命中替换成 *原词* 后再发群（工具调用参数不经过此路径）。
        val (filtered, hits) = AiContentGuard.filterOutbound(plugin, content)
        if (hits.isNotEmpty()) plugin.log_info("Agent 回复命中违禁词已掩蔽：${hits.joinToString("、")}")
        splitMessages(filtered).forEach { chunk ->
            if (session.stopped) return
            plugin.sendMarkdownToGroup(session.groupOpenId, chunk)
        }
    }

    /** 将超长内容按行拆分为多条，保证 QQ 群展示完整；内容不超过上限时原样返回。 */
    internal fun splitMessages(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        if (content.length <= MAX_MESSAGE_CHARS) return listOf(content)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
                current.setLength(0)
            }
        }
        for (line in content.lineSequence()) {
            var rest = line
            while (true) {
                // 块内已有内容时为行间换行预留 1 字符
                val room = MAX_MESSAGE_CHARS - current.length - if (current.isEmpty()) 0 else 1
                if (rest.length <= room) {
                    // 单行超长被硬切后的剩余尾部可能为空，此时不再补换行，避免下一块以空行开头
                    if (current.isNotEmpty()) current.append('\n')
                    current.append(rest)
                    break
                }
                if (current.isNotEmpty()) current.append('\n')
                current.append(rest, 0, room)
                flush()
                rest = rest.substring(room)
            }
        }
        flush()
        return chunks
    }

    /** 上下文窗口裁剪：保留首条 system 与最近的消息，且不拆散 tool 调用组。 */
    private fun prepareMessages(session: AgentSession, config: AgentConfig): List<JSONObject> {
        val trimmed = AgentContextManager.trimByCount(session.messages, MAX_CONTEXT_MESSAGES)
        return if (config.thinkGuide) {
            AgentThinking.withGuide(trimmed, config.effectiveThinkGuidePrompt)
        } else {
            trimmed
        }
    }

    private fun finish(plugin: HuHoBot, session: AgentSession) {
        session.finished = true
        // 刻意不在这里移除会话：对话历史按设计长期保留，供同一用户后续追问，
        // 只有用户手动 /newsession 才会清空。会话数量因此随"用过的群 + 用户"增长，属预期行为。
    }

    /**
     * 查找指定群中某用户现有的会话，供上下文压缩一类的管理命令使用。
     *
     * 这里按群 + 用户一起匹配，和 clearSession/stopAgent 保持一致；
     * /agent 复用会话时只按用户匹配，跨群会复用到别的群的会话，那条路径不做改动。
     */
    fun findSession(groupOpenId: String, requestUserId: String): AgentSession? =
        sessions.values.firstOrNull {
            it.requestUserId == requestUserId && it.groupOpenId == groupOpenId
        }

    /** 手动清除同一用户在指定群的会话上下文。 */
    fun clearSession(plugin: HuHoBot, groupOpenId: String, requestUserId: String) {
        val removed = sessions.entries.removeIf { (_, session) ->
            val matches = session.requestUserId == requestUserId && session.groupOpenId == groupOpenId
            if (matches) cancelSession(session)
            matches
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
                cancelSession(session)
                count++
            }
        }
        plugin.log_info("Agent 紧急停止：group=$groupOpenId user=$requestUserId 停止了 $count 个会话")
    }

    private fun cancelSession(session: AgentSession) {
        session.stopped = true
        session.awaitingApproval = null
        session.pendingWait?.cancel(false)
        session.pendingWait = null
        session.waiting = false
        session.resumeRequested.set(false)
        if (session.tryBeginRun()) {
            try { cancelOutstandingCalls(session) } finally { session.endRun() }
        }
    }

    private fun cancelOutstandingCalls(session: AgentSession) {
        val answered = session.messages.filter { it.getString("role") == "tool" }.mapNotNull { it.getString("tool_call_id") }.toHashSet()
        val unresolved = session.messages.filter { it.getString("role") == "assistant" }.flatMap { message ->
            val calls = message.getJSONArray("tool_calls") ?: return@flatMap emptyList<String>()
            (0 until calls.size).mapNotNull { calls.getJSONObject(it)?.getString("id") }.filter { it !in answered }
        }
        unresolved.distinct().forEach { session.messages.add(toolMessage(it, "任务已停止：此调用未执行或已取消，不能声称成功。")) }
        session.pendingToolCalls.clear()
        session.waitResult = null
        session.finished = true
    }

    fun cancelAllSessions() { sessions.values.forEach(::cancelSession) }

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
