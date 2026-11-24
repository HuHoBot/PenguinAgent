package agent

import agent.NicknameManager
import agent.ai.AgentInteractionListener
import agent.ai.GroupManagementApi
import agent.addon.Addon
import agent.addon.AddonManager
import agent.events.GroupMessageHandler
import agent.events.commands.BaseCommand
import agent.events.commands.CustomCommandRegistry
import agent.events.commands.RegisteredCommand
import agent.provider.BotShared
import agent.state.CommandRepositories
import agent.state.GroupDirectory
import agent.tools.Cancelable
import agent.tools.QqBotConsoleOutputFilter
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.api.Intents
import io.github.kloping.qqbot.api.event.ConnectedEvent
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.entities.ex.Markdown
import io.github.kloping.qqbot.entities.ex.msg.MessageChain
import io.github.kloping.qqbot.entities.qqpd.Channel
import io.github.kloping.qqbot.http.data.V2MsgData
import io.github.kloping.qqbot.http.data.V2Result
import io.github.kloping.qqbot.impl.ListenerHost
import io.github.kloping.qqbot.utils.LoggerImpl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object QClient {
    private const val KEYBOARD_RECALL_DELAY_SECONDS = 30L
    private const val GROUP_NAME_RETRY_MILLIS = 30_000L
    private const val GROUP_PANELS_SYNC_RETRY_MILLIS = 1_000L
    private const val GROUP_PANELS_SYNC_MAX_ATTEMPTS = 120
    /** 写接口限频 10 QPM，两次同步至少隔 6 秒。 */
    private const val GROUP_PANELS_SYNC_MIN_INTERVAL_MS = 6_000L

    private val groupNameAttempts = ConcurrentHashMap<String, Long>()

    private val recallScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Penguin-Keyboard-Recall").apply { isDaemon = true }
    }
    private val pendingRecalls = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private lateinit var starter: Starter
    private lateinit var groupMessageHandler: GroupMessageHandler

    /**
     * WebSocket 网关是否已完成鉴权（收到 READY 帧）。
     *
     * `Starter.run()` 返回后 contextManager 已赋值，但 wssWorker 还在后台线程里连接网关；
     * 若此刻直接调用 `starter.bot.groupBaseV2.send(...)` 会 NPE。只有收到 READY 帧后才允许发送。
     */
    @Volatile
    private var connectedReady = false

    @Volatile
    private var groupPanelsSyncPending = false

    @Volatile
    private var groupPanelsSyncRetry: Cancelable? = null

    /** 面板同步串行化：同一时刻只跑一次，期间的请求收敛成一次补跑。 */
    private val groupPanelsSyncRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val groupPanelsSyncQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var groupPanelsSyncThrottled: Cancelable? = null

    @Volatile
    private var lastGroupPanelsSyncAt = 0L

    /** 获取 QQ Bot Starter 实例（供 Agent 群管理 API 使用）。 */
    fun getStarter(): Starter? = if (::starter.isInitialized) starter else null

    /** QQ 是否已完成鉴权：contextManager 只在鉴权成功后才会被赋值。 */
    private fun isQqAuthenticated(): Boolean = try {
        starter.APPLICATION.INSTANCE.contextManager != null
    } catch (_: Throwable) {
        false
    }

    /** QQ 是否已启动并完成鉴权，可用于判断现在发送消息是否会成功。 */
    fun isReady(): Boolean = ::starter.isInitialized && isQqAuthenticated() && connectedReady

    /** 鉴权未完成时按秒重试面板同步，超过上限就放弃并告警。 */
    private fun scheduleGroupPanelsSyncRetry() {
        if (groupPanelsSyncRetry != null) return
        var attempts = 0
        groupPanelsSyncRetry = BotShared.getPlugin().submitTimer(GROUP_PANELS_SYNC_RETRY_MILLIS, GROUP_PANELS_SYNC_RETRY_MILLIS) {
            if (!groupPanelsSyncPending) return@submitTimer
            attempts++
            if (isQqAuthenticated()) {
                syncGroupPanels()
            } else if (attempts >= GROUP_PANELS_SYNC_MAX_ATTEMPTS) {
                groupPanelsSyncPending = false
                groupPanelsSyncRetry?.cancel()
                groupPanelsSyncRetry = null
                BotShared.getPlugin().log_warning("面板同步: 等待 QQ 鉴权超时，已放弃本次同步")
            }
        }
    }

    /**
     * 注册指令处理器,收到群消息后会自动分发
     */
    fun registerCommand(command: BaseCommand) {
        check(::groupMessageHandler.isInitialized) {
            "QQ client has not been launched"
        }
        groupMessageHandler.registerCommand(command)
    }

    /**
     * 注册指令处理器并关联扩展元数据。
     * 命令会标记为该扩展的来源，可通过 [AddonManager] 查询。
     */
    fun registerCommand(addon: Addon, command: BaseCommand) {
        check(::groupMessageHandler.isInitialized) {
            "QQ client has not been launched"
        }
        groupMessageHandler.registerCommand(command, addon.name)
        // 将该扩展的命令信息注册到 AddonManager
        val addonCommands = command.registeredCommands().map {
            it.copy(source = addon.name)
        }
        AddonManager.register(addon, addonCommands)
    }

    /**
     * 面板同步入口。
     *
     * 写接口（PUT/POST/DELETE）官方限频 10 QPM，而每注册/注销一条 QQ 群命令都会触发一次
     * 同步——脚本插件批量加载时是突发调用。这里做两件事：
     * 1. 同一时刻只跑一次同步，期间再来请求只记一个「待补跑」标记，收敛成一次；
     * 2. 两次同步之间至少间隔 [GROUP_PANELS_SYNC_MIN_INTERVAL_MS]，避免突发撞限频
     *    （限频会返回 40030009）或自己和自己撞出 30019。
     */
    /** QQ HTTP panel synchronization must never execute in the server tick thread. */
    fun syncGroupPanels() {
        val plugin = BotShared.getPluginOrNull() ?: return
        runCatching { plugin.submitAsync { syncGroupPanelsAsyncWorker() } }
            .onFailure { plugin.log_warning("无法提交 QQ 指令面板后台同步：${it.message}") }
    }

    private fun syncGroupPanelsAsyncWorker() {
        if (!groupPanelsSyncRunning.compareAndSet(false, true)) {
            groupPanelsSyncQueued.set(true)
            return
        }
        try {
            val now = System.currentTimeMillis()
            val elapsed = now - lastGroupPanelsSyncAt
            if (lastGroupPanelsSyncAt > 0L && elapsed < GROUP_PANELS_SYNC_MIN_INTERVAL_MS) {
                // 太频繁，排到最小间隔之后补跑
                groupPanelsSyncQueued.set(true)
                scheduleGroupPanelsSyncThrottle(GROUP_PANELS_SYNC_MIN_INTERVAL_MS - elapsed)
                return
            }
            syncGroupPanelsOnce()
            lastGroupPanelsSyncAt = System.currentTimeMillis()
        } finally {
            groupPanelsSyncRunning.set(false)
        }
        if (groupPanelsSyncQueued.compareAndSet(true, false)) {
            scheduleGroupPanelsSyncThrottle(GROUP_PANELS_SYNC_MIN_INTERVAL_MS)
        }
    }

    /** 节流后的补跑；同时只会有一个待跑定时器。 */
    private fun scheduleGroupPanelsSyncThrottle(delayMs: Long) {
        if (groupPanelsSyncThrottled != null) return
        groupPanelsSyncThrottled = BotShared.getPlugin().submitLater(delayMs.coerceAtLeast(0L)) {
            groupPanelsSyncThrottled = null
            syncGroupPanels()
        }
    }

    private fun syncGroupPanelsOnce() {
        if (!::starter.isInitialized || !::groupMessageHandler.isInitialized) {
            groupPanelsSyncPending = true
            return
        }
        // 脚本在启动早期就会登记 QQ 群命令，那时会顺带触发面板同步；但
        // contextManager 只在 QQ 鉴权成功后才被赋值，提前访问必然 NPE。
        // 这里先挂起，等鉴权完成再补同步。
        if (!isQqAuthenticated()) {
            groupPanelsSyncPending = true
            scheduleGroupPanelsSyncRetry()
            return
        }
        groupPanelsSyncPending = false
        groupPanelsSyncRetry?.cancel()
        groupPanelsSyncRetry = null
        val plugin = BotShared.getPlugin()
        val allCommands = groupMessageHandler.registeredCommands()
        val customCommands = CustomCommandRegistry.snapshot().filter { it.pushMenu }.map {
            RegisteredCommand(
                command = it.key,
                describe = "自定义命令",
                onlyAdmin = it.permission > 0
            )
        }
        MenuManager.syncGroupPanels(
            starter = starter,
            groupOpenIds = plugin.getGroupOpenIdList(),
            allBuiltInCommands = allCommands,
            customCommands = customCommands
        )
    }

    /** Optional SDK hook: absent in older qqpd-bot-java builds, so resolve it reflectively. */
    private fun installSequenceAllocator(allocator: ((io.github.kloping.qqbot.api.v2.GroupMessageEvent) -> Int)?) {
        runCatching {
            val type = Class.forName("io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent")
            val method = type.getMethod("setSequenceAllocator", java.util.function.BiFunction::class.java)
            val fn = allocator?.let { block ->
                java.util.function.BiFunction<io.github.kloping.qqbot.api.v2.GroupMessageEvent, Int, Int> { event, fallback ->
                    runCatching { block(event) }.getOrDefault(fallback)
                }
            }
            method.invoke(null, fn)
        }
    }

    @Synchronized fun launchClient(appid: String, secret: String, logFilePattern: String? = null) {
        val plugin = BotShared.getPlugin()
        installSequenceAllocator { event ->
            val group = event.groupOpenId ?: event.groupId
            passiveReplies.take(group, plugin.getGroupPassiveReplyLimit(group), event.rawMessage.id)?.second
                ?: throw IllegalStateException("该群消息的被动回复窗口已过期或额度耗尽，请重新发送查询；不要重用 msg_seq")
        }
        val suppressConsoleOutput = plugin.shouldSuppressQqBotConsoleOutput()
        if (suppressConsoleOutput) {
            QqBotConsoleOutputFilter.install()
        } else {
            QqBotConsoleOutputFilter.uninstall()
        }

        // 新建 Starter 前释放旧 Starter 自己拥有的执行器，避免每次 reload 遗留三套线程池。
        if (::starter.isInitialized) {
            try {
                starter.shutdown()
            } catch (_: Exception) {}
        }

        try {
            groupMessageHandler = GroupMessageHandler(plugin)
            connectedReady = false
            starter = Starter(appid, secret)
            starter.config.code = Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS)
            LoggerImpl.INSTANCE.setLogLevel(1)
            logFilePattern?.let { LoggerImpl.INSTANCE.setOutFile(it) }
            starter.run()
            starter.registerListenerHost(groupMessageHandler)
            starter.registerListenerHost(AgentInteractionListener())
            starter.registerListenerHost(object : ListenerHost() {
                @EventReceiver
                fun onConnected(event: ConnectedEvent) {
                    connectedReady = true
                }
            })
            syncGroupPanels()
            // 加载本地昵称缓存
            NicknameManager.load()
            GroupDirectory.load()
            GroupDirectory.knownOpenIds().filter { it in plugin.getGroupOpenIdList() }.forEach {
                if (GroupDirectory.isStale(it)) refreshGroupNames(listOf(it))
            }
        } catch (error: Exception) {
            if (suppressConsoleOutput) {
                QqBotConsoleOutputFilter.uninstall()
            }
            throw error
        }
    }

    /** 将游戏聊天按配置格式发送到 bot.groups 中的 QQ 群。 */
    fun broadcastGameMessage(playerName: String, message: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        val format = plugin.getChatFormat()
        if (!format.postChat) return
        if (!message.startsWith(format.startWith)) return

        val messageWithoutPrefix = message.removePrefix(format.startWith)
        val moderation = plugin.audit(messageWithoutPrefix)
        if (moderation.blocked) {
            plugin.log_warning("已拦截玩家 $playerName 的游戏→QQ违规消息（命中=${moderation.matchedWords.size} 条${if (moderation.aiBlocked) "，AI复核" else ""}）")
            plugin.sendPlayerWarning(playerName, "你的消息包含违规内容，未转发到 QQ 群。")
            return
        }
        val filtered = moderation.sanitized

        // QQ markdown cannot render colour; strip it instead of leaking raw § codes.
        val plain = if (format.stripColourCodes) agent.tools.McColours.strip(filtered) else filtered

        // 检测 @昵称并转换为 QQ @格式 <@openid>
        val processed = resolveAtMentions(plain)

        // 绑定：查找发送者是否绑定到某个 QQ 用户
        val binding = findBindingByPlayerName(playerName)
        val qqSenderName = if (binding != null) {
            when (binding.binding.qqDisplayNameMode) {
                "QQ" -> binding.qqName
                else -> escapeMarkdown(playerName)
            }
        } else {
            escapeMarkdown(playerName)
        }

        val safeProcessed = processed.replace("_", "\\_")
        val content = plugin.applyPlaceholders(
            playerName,
            plugin.formatGameMessage(qqSenderName, safeProcessed)
        )
        val markdown = Markdown().setContent(content)
        val payload = V2MsgData()
            .setMsg_type(2)
            .setMarkdown(markdown)
        Thread {
            plugin.getGroupOpenIdList().forEach { groupId ->
                try {
                    attachPassive(payload, groupId)
                    starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
                } catch (e: Exception) {
                    plugin.log_error("向QQ群 $groupId 转发游戏聊天失败: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 根据 Minecraft 玩家名查找绑定信息（绑定全局共享，无需逐群搜索）。
     * 返回 BindingInfo + QQ 昵称。
     */
    private fun findBindingByPlayerName(playerName: String): BindingLookupResult? {
        val entry = CommandRepositories.bindings.findByPlayerName(playerName) ?: return null
        val qqName = entry.value.qqUsername.ifEmpty {
            NicknameManager.getNickname(entry.key)
                ?: NicknameManager.all().firstOrNull { it.second == entry.key }?.first
                ?: "QQ用户"
        }
        return BindingLookupResult(entry.value, qqName)
    }

    private data class BindingLookupResult(
        val binding: agent.datapack.BindingInfo,
        val qqName: String
    )

    /**
     * 将消息中的 @昵称 转换为 QQ 的 <@openid> 格式。
     * 支持：@张三、@张三 你好、@张三@李四
     * 也处理直接输入的 @openid（转为昵称显示）。
     * 如果 @的是绑定的 MC 玩家名，也会解析为对应的 QQ @。
     */
    private fun resolveAtMentions(text: String): String {
        var result = text

        // 匹配 @昵称 后面是空格、标点或字符串结尾
        val allMembers = NicknameManager.all()
        if (allMembers.isNotEmpty()) {
            val sorted = allMembers.sortedByDescending { it.first.length }
            for ((nickname, openId) in sorted) {
                val pattern = Regex("@${Regex.escape(nickname)}(?=\\s|[，。！？、；：,.!?;:]|\$)")
                result = result.replace(pattern) { match ->
                    "<@$openId>"
                }
            }
        }

        // 匹配绑定的 MC 玩家名：@PlayerName 或 PlayerName → <@openid>（绑定全局共享）
        for ((openId, info) in CommandRepositories.bindings.allBindings()) {
            for (mcName in info.playerNames) {
                val pattern = Regex("(?<![<a-zA-Z0-9])@?${Regex.escape(mcName)}(?![>a-zA-Z0-9])")
                result = result.replace(pattern) { _ -> "<@$openId>" }
            }
        }
        // 处理直接输入的 @openid：尝试转为昵称，找不到就去掉
        result = result.replace(Regex("@([0-9A-Fa-f]{20,})(?=\\s|\$)")) { match ->
            val oid = match.groupValues[1]
            val nick = NicknameManager.getNickname(oid)
            if (nick != null) "@$nick" else ""
        }
        return result
    }

    /** 按配置向所有 QQ 群发送玩家进服通知。 */
    fun broadcastPlayerJoin(playerName: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        if (!plugin.getPlayerEventFormat().joinEnabled) return
        sendTextToGroups(plugin.formatPlayerJoinMessage(escapeMarkdown(playerName)), "发送玩家进服通知")
    }

    /** 按配置向所有 QQ 群发送玩家退服通知。 */
    fun broadcastPlayerQuit(playerName: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        if (!plugin.getPlayerEventFormat().quitEnabled) return
        sendTextToGroups(plugin.formatPlayerQuitMessage(escapeMarkdown(playerName)), "发送玩家退服通知")
    }

    /** 按配置向所有 QQ 群发送玩家死亡播报。 */
    fun broadcastPlayerDeath(playerName: String, deathMessage: String?, killerName: String? = null, pluginDeathMessage: String? = null) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        val format = plugin.getPlayerEventFormat()
        if (!format.deathEnabled) return
        val chosen = if (format.usePluginMessage && !pluginDeathMessage.isNullOrBlank()) {
            agent.tools.McColours.strip(pluginDeathMessage)
        } else deathMessage
        val text = plugin.formatPlayerDeathMessage(
            escapeMarkdown(playerName),
            chosen?.let(::escapeMarkdown),
            killerName?.let(::escapeMarkdown)
        )
        sendTextToGroups(text, "发送玩家死亡播报")
    }

    /** Fixed 60-minute group window, as documented in the 2026-11-17 API changelog. */
    private val passiveReplies = agent.messaging.PassiveReplyLedger()

    fun noteInbound(groupOpenId: String, msgId: String) = passiveReplies.note(groupOpenId, msgId)

    private fun takePassiveSlot(groupOpenId: String, messageId: String? = null): Pair<String, Int>? {
        val plugin = BotShared.getPlugin()
        if (plugin.getSendPolicy() == "active") return null
        return passiveReplies.take(groupOpenId, plugin.getGroupPassiveReplyLimit(groupOpenId), messageId)
    }

    /** Every QClient transport uses the same sequence ledger, including Agent cards. */
    private fun attachPassive(payload: V2MsgData, groupOpenId: String, messageId: String? = null) {
        payload.setMsg_id(null).setMsg_seq(null)
        takePassiveSlot(groupOpenId, messageId)?.let { (id, seq) -> payload.setMsg_id(id).setMsg_seq(seq) }
    }

    fun sendTextToGroup(groupOpenId: String, content: String) {
        if (!::starter.isInitialized) return
        if (content.isBlank()) return
        val plugin = BotShared.getPlugin()
        val markdown = Markdown().setContent(content)
        val payload = V2MsgData().setMsg_type(2).setMarkdown(markdown)
        // 被动优先：节省每天 1000 条主动消息额度；超时/超量自动转主动
        val passive = if (plugin.getSendPolicy() != "active") takePassiveSlot(groupOpenId) else null
        if (passive != null) {
            payload.setMsg_id(passive.first).setMsg_seq(passive.second)
        }
        Thread {
            try {
                starter.bot.groupBaseV2.send(groupOpenId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            } catch (e: Exception) {
                if (passive != null) {
                    // 被动通道失败（窗口过期/seq 冲突）→ 回退一次主动发送
                    runCatching {
                        val activePayload = V2MsgData().setMsg_type(2).setMarkdown(markdown)
                        starter.bot.groupBaseV2.send(groupOpenId, JSON.toJSONString(activePayload), Channel.SEND_MESSAGE_HEADERS)
                        plugin.log_warning("被动回复失败已回退主动发送: ${e.message}")
                    }.onFailure { plugin.log_error("向QQ群 $groupOpenId 发送消息失败: ${it.message}") }
                } else {
                    plugin.log_error("向QQ群 $groupOpenId 发送消息失败: ${e.message}")
                }
            }
        }.start()
    }

    fun sendTextToGroups(content: String, action: String) {
        if (content.isBlank()) return
        val plugin = BotShared.getPlugin()
        val markdown = Markdown().setContent(content)
        val payload = V2MsgData().setMsg_type(2).setMarkdown(markdown)
        Thread {
            plugin.getGroupOpenIdList().forEach { groupId ->
                try {
                    attachPassive(payload, groupId)
                    starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
                } catch (e: Exception) {
                    plugin.log_error("向QQ群 $groupId ${action}失败: ${e.message}")
                }
            }
        }.start()
    }

    /** 向 bot.groups 中配置的所有 QQ 群发送自定义 Markdown。 */
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null) {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法发送 Markdown")
            return
        }

        val markdown = Markdown().setContent(markdownContent)
        // 官方要求：传了 markdown 后 content 必须为空，否则服务端按纯文本处理并丢弃 keyboard
        val payload = V2MsgData()
            .setMsg_type(2)
            .setMarkdown(markdown)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
            payload.setKeyboard(keyboard)
        }

        plugin.getGroupOpenIdList().forEach { groupId ->
            try {
                attachPassive(payload, groupId)
                val result = starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
                scheduleKeyboardRecall(groupId, keyboard, result)
            } catch (e: Exception) {
                plugin.log_error("向QQ群 $groupId 发送 Markdown 失败: ${e.message}")
            }
        }

    }

    /** 向指定 QQ 群发送自定义 Markdown，返回消息 ID。 */
    fun sendMarkdownToGroup(groupOpenId: String, markdownContent: String, keyboard: Keyboard? = null): String? {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法发送 Markdown")
            return null
        }
        if (markdownContent.isBlank()) return null

        val markdown = Markdown().setContent(markdownContent)
        // 官方要求：传了 markdown 后 content 必须为空，否则服务端按纯文本处理并丢弃 keyboard
        val payload = V2MsgData()
            .setMsg_type(2)
            .setMarkdown(markdown)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
            payload.setKeyboard(keyboard)
        }

        return try {
            attachPassive(payload, groupOpenId)
            val result = starter.bot.groupBaseV2.send(groupOpenId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            scheduleKeyboardRecall(groupOpenId, keyboard, result)
            result?.id
        } catch (error: Exception) {
            plugin.log_error("向QQ群 $groupOpenId 发送 Markdown 失败: ${error.message}")
            null
        }
    }

    /**
     * TTS 语音发送：QQ 富媒体 file_type=3（语音），url 需公网可拉取。
     * 注意：官方要求语音为 silk 格式；wav/mp3 在部分平台环境可用，若被拒请配置 silk 转码。
     */
    fun sendGroupVoice(groupOpenId: String, url: String): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized || !connectedReady) {
            plugin.log_warning("QQ 机器人未就绪，语音发送失败")
            return false
        }
        if (url.isBlank()) return false
        return try {
            val body = JSONObject().apply {
                put("file_type", 3)
                put("url", url)
                put("srv_send_msg", true)
            }
            starter.bot.groupBaseV2.sendFile(
                groupOpenId,
                JSON.toJSONString(body),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("发送语音到QQ群 $groupOpenId 失败: ${error.message}；请确认 tts.public-base-url 公网可达，且音频为 QQ 支持的语音格式")
            false
        }
    }

    /** 回复指定群消息并发送普通文本。 */
    fun replyText(event: GroupMessageEvent, text: String): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复文本")
            return false
        }
        if (text.isBlank()) return false
        val groupId = event.groupOpenId ?: event.groupId
        val messageId = event.rawMessage.id.orEmpty()
        val payload = V2MsgData()
            .setContent(text)
            .also { attachPassive(it, groupId, messageId) }
        return try {
            starter.bot.groupBaseV2.send(
                groupId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("回复文本失败: ${error.message}")
            false
        }
    }

    /** 使用消息快照字段回复普通文本。 */
    fun replyText(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        text: String
    ): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复文本")
            return false
        }
        if (text.isBlank()) return false
        val payload = V2MsgData()
            .setContent(text)
            .also { attachPassive(it, groupOpenId, messageId) }
        return try {
            starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("回复文本失败: ${error.message}")
            false
        }
    }

    /** 使用消息快照字段回复 Markdown。 */
    fun replyMarkdown(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复 Markdown")
            return false
        }
        if (markdownContent.isBlank()) return false

        val markdown = Markdown().setContent(markdownContent)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
        }

        // 官方要求：传了 markdown 后 content 必须为空，否则服务端按纯文本处理并丢弃 keyboard
        val payload = V2MsgData()
            .setMsg_type(2)
            .setMarkdown(markdown)
            .also { attachPassive(it, groupOpenId, messageId) }
        if (keyboard != null) {
            payload.setKeyboard(keyboard)
        }

        return try {
            val result = starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            scheduleKeyboardRecall(groupOpenId, keyboard, result)
            true
        } catch (error: Exception) {
            plugin.log_error("回复 Markdown 失败: ${error.message}")
            false
        }
    }

    /** 回复指定群消息并发送自定义 Markdown。 */
    fun replyMarkdown(
        event: GroupMessageEvent,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复 Markdown")
            return false
        }
        if (markdownContent.isBlank()) return false

        val markdown = Markdown().setContent(markdownContent)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
        }

        // 官方要求：传了 markdown 后 content 必须为空，否则服务端按纯文本处理并丢弃 keyboard
        val payload = V2MsgData()
            .setMsg_type(2)
            .setMarkdown(markdown)
            .also { attachPassive(it, event.groupOpenId ?: event.groupId, event.rawMessage.id) }
        if (keyboard != null) {
            payload.setKeyboard(keyboard)
        }

        return try {
            val groupId = event.groupOpenId ?: event.groupId
            val result = starter.bot.groupBaseV2.send(
                groupId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            scheduleKeyboardRecall(groupId, keyboard, result)
            true
        } catch (error: Exception) {
            plugin.log_error("回复 Markdown 失败: ${error.message}")
            false
        }
    }

    /** 键盘消息发送后统一安排 30 秒自动撤回。 */
    private fun scheduleKeyboardRecall(groupOpenId: String, keyboard: Keyboard?, result: V2Result?) {
        if (keyboard == null) return
        val messageId = result?.id?.takeIf { it.isNotBlank() } ?: return
        val future = recallScheduler.schedule({
            pendingRecalls.remove(messageId)
            recallMessageNow(groupOpenId, messageId)
        }, KEYBOARD_RECALL_DELAY_SECONDS, TimeUnit.SECONDS)
        pendingRecalls.put(messageId, future)?.cancel(false)
    }

    /** 立即撤回消息，并取消已安排的自动撤回。 */
    fun recallMessage(groupOpenId: String, messageId: String) {
        if (messageId.isBlank()) return
        pendingRecalls.remove(messageId)?.cancel(false)
        if (!::starter.isInitialized) return
        Thread { recallMessageNow(groupOpenId, messageId) }.start()
    }

    private fun recallMessageNow(groupOpenId: String, messageId: String) {
        val plugin = BotShared.getPlugin()
        try {
            val current = getStarter() ?: run {
                plugin.log_warning("QQ SDK 未就绪，无法撤回已缓存的入站消息")
                return
            }
            val error = GroupManagementApi.recallMessage(current, groupOpenId, messageId)
            if (error != null) plugin.log_warning("撤回QQ群消息失败：$error（机器人需为群管理员，消息不能超过2分钟）")
        } catch (error: Exception) {
            plugin.log_warning("撤回QQ群消息异常：${error.message}")
        }
    }

    /** Called by moderation after the inbound event supplied the ID; the operator never has to copy it. */
    fun autoRecallInbound(groupOpenId: String, messageId: String, reason: String = "违规内容") {
        if (groupOpenId.isBlank() || messageId.isBlank()) return
        if (!BotShared.getPlugin().isModerationAutoRecallEnabled()) return
        Thread {
            runCatching {
                val starter0 = getStarter() ?: return@runCatching
                val error = GroupManagementApi.recallMessage(starter0, groupOpenId, messageId)
                if (error != null) BotShared.getPlugin().log_warning("自动撤回失败（$reason）：$error")
                else BotShared.getPlugin().log_info("已自动撤回群消息（原因：$reason）")
            }.onFailure { BotShared.getPlugin().log_warning("自动撤回异常（$reason）：${it.message}") }
        }.start()
    }

    /** Reply with a real @ of the event sender; the inbound msg_id is taken from the event itself. */
    fun replyModerationWarning(event: GroupMessageEvent, text: String): Boolean {
        if (!::starter.isInitialized) return false
        val plugin = BotShared.getPlugin()
        val groupId = event.groupOpenId ?: event.groupId
        val member = event.sender?.openid ?: event.sender?.id ?: return false
        val payload = V2MsgData().setContent("<@$member> $text")
            .also { attachPassive(it, groupId, event.rawMessage.id) }
        return try {
            starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            true
        } catch (error: Exception) {
            plugin.log_warning("发送违禁词提醒失败：${error.message}")
            false
        }
    }

    /** 回复指定群消息，同时发送文本和网络图片。 */
    fun replyWithImg(event: GroupMessageEvent, text: String, imgUrl: String): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复图片消息")
            return false
        }
        if (imgUrl.isBlank()) {
            plugin.log_warning("图片 URL 为空，无法回复图片消息")
            return false
        }

        val message = MessageChain()
            .apply { if (text.isNotBlank()) text(text) }
            .image(imgUrl)

        return try {
            event.sendMessage(message)
            true
        } catch (error: Exception) {
            plugin.log_error("回复图片消息失败: ${error.message}")
            false
        }
    }

    /**
     * /at 命令专用：以 markdown 格式发送 @消息到 QQ 群，跳过 startWith 前缀检查。
     * markdown 格式的 <@openid> 才会触发 QQ 的 @ 通知。
     */
    fun sendAtToGroups(playerName: String, message: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        val format = plugin.getChatFormat()
        if (!format.postChat) return

        val moderation = plugin.audit(message)
        if (moderation.blocked) {
            plugin.log_warning("已拦截玩家 $playerName 的 /at 违规消息（命中=${moderation.matchedWords.size} 条）")
            plugin.sendPlayerWarning(playerName, "你的消息包含违规内容，未发送到 QQ 群。")
            return
        }
        val filtered = moderation.sanitized
        // markdown 下需转义玩家名中的 _ 等特殊字符，避免被识别为斜体
        val safeName = escapeMarkdown(playerName)
        val content = plugin.applyPlaceholders(playerName, plugin.formatGameMessage(safeName, filtered))
        val markdown = Markdown().setContent(content)
        val payload = V2MsgData()
            .setMsg_type(2)
            .setMarkdown(markdown)
        plugin.getGroupOpenIdList().forEach { groupId ->
            try {
                starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            } catch (e: Exception) {
                plugin.log_error("向QQ群 $groupId 转发@消息失败: ${e.message}")
            }
        }
    }

    /**
     * 刷新已配置群的群名称缓存（供 WebUI 展示）。
     *
     * @return 是否至少成功取得一个群名称
     */
    fun refreshGroupNames(groupOpenIds: List<String>): Boolean {
        if (!::starter.isInitialized) {
            BotShared.getPlugin().log_warning("群名刷新失败：QQ 机器人尚未启动")
            return false
        }
        val current = starter
        val plugin = BotShared.getPlugin()
        val now = System.currentTimeMillis()
        var any = false
        groupOpenIds.forEach { openId ->
            if (!GroupDirectory.isStale(openId)) {
                if (GroupDirectory.nameOf(openId) != null) any = true
                return@forEach
            }
            // 失败后短时间内不重复请求，避免刷屏和打爆接口
            val lastAttempt = groupNameAttempts[openId] ?: 0L
            if (now - lastAttempt < GROUP_NAME_RETRY_MILLIS) return@forEach
            groupNameAttempts[openId] = now

            val response = try {
                GroupManagementApi.getGroupInfo(current, openId)
            } catch (error: Exception) {
                plugin.log_warning("群名刷新失败 group=$openId 错误=${error.javaClass.simpleName}: ${error.message}")
                null
            }
            val name = response?.getString("group_name")
            if (response == null) {
                plugin.log_warning("群名刷新失败 group=$openId 响应为空")
            } else if (name.isNullOrBlank()) {
                plugin.log_warning("群名刷新失败 group=$openId 响应=${response.toJSONString().take(300)}")
            } else {
                GroupDirectory.put(openId, name)
                any = true
                plugin.log_info("群名已更新 group=$openId name=$name")
            }
        }
        if (any) GroupDirectory.save()
        return any
    }

    /** 转义 Markdown 特殊字符，防止玩家名被渲染为格式符号。 */
    internal fun escapeMarkdown(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace(">", "\\>")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace(".", "\\.")
            .replace("!", "\\!")
            .replace("|", "\\|")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("{", "\\{")
            .replace("}", "\\}")
    }

    @Synchronized fun shutdown() {
        try {
            if (::starter.isInitialized) starter.shutdown()
        } finally {
            installSequenceAllocator(null)
            passiveReplies.clear()
            QqBotConsoleOutputFilter.uninstall()
        }
    }
}
