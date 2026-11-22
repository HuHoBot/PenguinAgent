package agent.spigot

import agent.HuHoBot
import agent.QClient
import agent.onlinelist.OnlineListBridge
import agent.addon.Addon
import agent.addon.AddonManager
import agent.ai.AgentCommandMode
import agent.events.commands.CustomCommandRegistry
import agent.events.commands.BaseCommand
import agent.events.commands.RegisteredCommand
import agent.provider.*
import agent.state.GroupCommandConfigStore
import agent.tools.Cancelable
import agent.spigot.commands.AtCommand
import agent.spigot.commands.QqBindCommand
import agent.spigot.commands.BukkitConsoleSender
import agent.spigot.commands.CommandOutputAppender
import agent.spigot.commands.HuHoBotCommand
import agent.spigot.commands.SendCommand
import agent.spigot.commands.HybridCommandExecutor
import agent.spigot.events.AccountLockGuard
import agent.spigot.manager.FoliaScheduler
import agent.spigot.events.ForceBindGuard
import agent.spigot.events.GameChat
import agent.spigot.events.OnBotCommand
import agent.spigot.events.OnBotRecvMsg
import agent.spigot.manager.ConfigManager
import agent.spigot.manager.QrLoginManager
import agent.spigot.manager.ServerNotifyManager
import agent.spigot.integration.PlaceholderApiSupport
import agent.spigot.inventory.InventoryRenderer
import agent.spigot.inventory.InventorySnapshot
import agent.spigot.inventory.OfflineInventorySnapshots
import agent.spigot.onlinelist.OnlineListManager
import agent.spigot.scripting.ScriptAddonLoader
import agent.adapter.api.MsgPack
import agent.adapter.api.toMsgPack
import agent.adapter.api.withCommand
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.PluginCommand
import agent.spigot.commands.GameCommandRoutes
import org.bukkit.plugin.java.JavaPlugin
import org.bstats.bukkit.Metrics
import java.io.File
import java.util.concurrent.Callable

class HuHoBotSpigot : JavaPlugin(), HuHoBot {
    companion object {
        private var instance: HuHoBotSpigot? = null
        fun getInstance(): HuHoBotSpigot? = instance
        // 与 ConfigManager 保持一致的面板隐藏默认值（仅用于 WebUI 展示）
        private val COMMANDS_HIDDEN_FROM_MENU_WEBUI =
            setOf("blockMotd", "unblockMotd", "压缩", "压缩token", "压缩保留", "删除信息", "锁定", "解除锁定")
    }

    private val runtimeDataFolder: File get() = File(dataFolder, "data")
    private val reloadExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "HuHoBot-ReloadIO").apply { isDaemon = true }
    }
    private val assetExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "HuHoBot-RenderAssets").apply { isDaemon = true }
    }
    private val reloadBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reloadQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val fullRestartQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var stopping = false

    private lateinit var configManager: ConfigManager
    private lateinit var serverNotifyManager: ServerNotifyManager
    private lateinit var offlineInventorySnapshots: OfflineInventorySnapshots
    private var scriptAddonLoader: ScriptAddonLoader? = null
    private var onlineListManager: OnlineListManager? = null
    private var chatLogManager: agent.spigot.chatlog.ChatLogManager? = null

    fun getScriptAddonLoader(): ScriptAddonLoader? = scriptAddonLoader

    override fun onEnable() {
        instance = this
        if (readPluginYamlSwitch("foiladebug")) logger.fine("Scheduler diagnostics active")
        showBanner(false)
        agent.state.DataLayout.migrate(dataFolder) { message -> log_info(message) }
        runtimeDataFolder.mkdirs()
        agent.spigot.onlinelist.SkinDiskCache.configure(dataFolder)
        configManager = ConfigManager(this)
        configManager.initialize()
        agent.spigot.manager.FoliaScheduler.configure(configManager.serverModeOverride())
        val detectedMode = agent.spigot.manager.FoliaScheduler.mode()
        log_info("调度模式：${detectedMode.name.lowercase()}（plugin.yml 声明不参与判断；compatibility.server-mode=${pluginConfigMode()}）")
        if (!server.onlineMode && configManager.offlineDefaultSkins()) {
            log_info("检测到离线模式：查在线/背包默认使用内置皮肤，不向皮肤站请求贴图；安装皮肤插件后仍可显示其皮肤。")
        }
        warnIfUnsafeModerationMode()
        GroupCommandConfigStore.configureDataDir(runtimeDataFolder)
        submitAsync { GroupCommandConfigStore.syncFolders(configManager.groupOpenIds()) }
        PlaceholderApiSupport.setup(this)
        // 内置字体先行：所有图片渲染(查在线/背包/皮肤)统一从这里选字体
        agent.spigot.scripting.ModuleDownloads.beginLifecycle()
        agent.spigot.manager.FontManager.initializeAsync(
            runtimeDataFolder,
            { message -> log_info(message) },
            { message -> log_warning(message) }
        )
        // 全局网络代理 / 权限组 / 定时任务 / 入群自动审批
        agent.ai.ProxySupport.configure(
            configManager.proxyEnabled(),
            configManager.proxyType(),
            configManager.proxyHost(),
            configManager.proxyPort()
        )
        agent.messages.Messages.initialize(configManager.language())
        agent.state.PermissionGroupsStore.configure(runtimeDataFolder)
        // 命令映射/切换（启动与重载时刷新）
        agent.state.CommandMappingStore.refresh()
        // 使用教程（首次启动生成 CONFIG.md）
        runCatching { agent.spigot.manager.ConfigGuide.ensure(runtimeDataFolder) }
        // 在线人数历史（查在线图片的趋势折线图）
        agent.spigot.onlinelist.OnlineHistoryStore.configureAsync(runtimeDataFolder)
        // 性能预警（TPS/内存持续异常与恢复时通报 QQ 群）
        performanceWatcher.start()
        // 强制 OTA（应急漏洞修补；未配置清单地址时完全静默）
        otaUpdater.start()
        // 退群自动解绑（轮询群成员列表，需 QQ 白名单能力）
        agent.ai.GroupMemberVerifier.start()
        agent.tasks.TimedTaskManager.configure(runtimeDataFolder)
        agent.ai.JoinApproveManager.start()
        agent.spigot.manager.ProxyBridge.initialize(this, configManager.proxyBridgeEnabled())
        initializeInventoryRenderer()
        initializeOnlineListRenderer()
        restartChatLogRenderer()
        offlineInventorySnapshots = OfflineInventorySnapshots(this).also { it.start() }
        initializeRuntime()
        serverNotifyManager = ServerNotifyManager(this, configManager).also { it.onServerStarted() }
        scheduleForceBindCheck()
        logCommandExecutor()
        val command = HuHoBotCommand(this)
        getCommand("huhobot")?.apply {
            setExecutor(command)
            tabCompleter = command
        } ?: log_error("无法注册 /huhobot 命令，请检查 plugin.yml")
        server.pluginManager.registerEvents(GameChat(), this)
        server.pluginManager.registerEvents(ForceBindGuard(this), this)
        server.pluginManager.registerEvents(AccountLockGuard(this), this)
        val atCommand = AtCommand()
        getCommand("at")?.apply {
            setExecutor(atCommand)
            tabCompleter = atCommand
        }
        val qqBindCommand = QqBindCommand()
        getCommand("qqbind")?.apply {
            setExecutor(qqBindCommand)
        } ?: log_error("无法注册 /qqbind 命令，请检查 plugin.yml")
        val sendCommand = SendCommand(this)
        getCommand("send")?.apply {
            setExecutor(sendCommand)
            tabCompleter = sendCommand
        } ?: log_error("无法注册 /send 命令，请检查 plugin.yml")
        setupCommandConflictFallback()
        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun loaded(event: org.bukkit.event.server.ServerLoadEvent) { setupCommandConflictFallback() }
            @org.bukkit.event.EventHandler
            fun commands(event: org.bukkit.event.player.PlayerCommandSendEvent) {
                val player=event.player
                if (player.hasPermission("huhobot.command") || gameRoutes.handover.any {
                    player.hasPermission(HuHoBotCommand.permissionFor(it))
                }) return
                val map=runtimeCommandMap ?: return
                event.commands.removeIf { key ->
                    val command=map.getCommand(key)
                    command != null && (command === fallbackCommand || command === declaredCommand(map,"huhobot"))
                }
            }
        }, this)
        loadScriptAddons()
        log_info("HuHoBot Penguin 已加载")
        BStatsReporter.start(this)
    }

    /** Prepares trusted directory addons on a worker before activation. */
    private fun loadScriptAddons() {
        val loader = ScriptAddonLoader(this)
        scriptAddonLoader = loader
        loader.loadAllAsync().whenComplete { results,error ->
            if(!isEnabled)return@whenComplete
            if(error!=null)log_warning("扩展后台准备失败: ${error.message}")
            else log_info("脚本扩展后台准备完成：成功 ${results.count { it.success() }}，失败 ${results.count { !it.success()&&!it.skipped() }}，跳过 ${results.count { it.skipped() }}")
        }
    }

    override fun onDisable() {
        agent.spigot.manager.ProxyBridge.shutdown()
        runCatching { agent.spigot.scripting.AddonSources.closeAll() }
        runCatching { agent.spigot.scripting.ModuleDownloads.stop() }
        stopping = true
        runtimeCommandMap?.let(::removeFallbackCommand)
        runCatching { reloadExecutor.shutdownNow() }
        runCatching { assetExecutor.shutdownNow() }
        runCatching { agent.spigot.manager.FontManager.shutdown() }
        runCatching { performanceWatcher.stop() }
        runCatching { otaUpdater.stop() }
        // 必须赶在 shutdownRuntime() 关掉 QQ 连接之前发送，否则关机通知发不出去。
        if (::serverNotifyManager.isInitialized) serverNotifyManager.onServerStopping()
        val loader = scriptAddonLoader
        try {
            loader?.shutdownAsync()
        } catch (error: Throwable) {
            log_error("卸载脚本扩展失败: ${error.message}")
        }
        scriptAddonLoader = null
        chatLogManager?.stop()
        if (agent.chatlog.ChatLogBridge.handler === chatLogManager) agent.chatlog.ChatLogBridge.handler = null
        chatLogManager = null
        if (::offlineInventorySnapshots.isInitialized) offlineInventorySnapshots.close()
        agent.spigot.inventory.SkinFetcher.close()
        agent.ai.JoinApproveManager.stop()
        agent.ai.GroupMemberVerifier.stop()
        agent.tasks.TimedTaskManager.stop()
        agent.storage.SqliteStateStore.close()
        OnlineListBridge.handler = null
        onlineListManager?.stop()
        onlineListManager = null
        agent.spigot.onlinelist.OnlineHistoryStore.closeAsync()
        instance = null
        shutdownRuntime()
        CommandOutputAppender.removeInstance()
    }

    private fun showBanner(reloading: Boolean) {
        agent.provider.StartupBanner.lines(getPluginVersion(), server.name, reloading).forEach(::log_info)
    }

    private fun logCommandExecutor() {
        val useHybridExecutor = configManager.commandSender().equals("Hybrid", ignoreCase = true)
        if (useHybridExecutor) {
            log_info("已启用混合控制台命令执行器")
        } else {
            log_info("已启用模拟控制台命令执行器")
        }
    }

    override fun getConfig(): org.bukkit.configuration.file.FileConfiguration =
        if (::configManager.isInitialized) configManager.currentConfig() ?: super.getConfig() else super.getConfig()

    override fun reloadConfig() {
        if (::configManager.isInitialized && configManager.currentConfig() != null) reloadPluginConfig()
        else super.reloadConfig()
    }

    override fun saveConfig() {
        if (!::configManager.isInitialized || configManager.currentConfig() == null) { super.saveConfig(); return }
        val changes = configManager.pendingChanges()
        if (changes.isEmpty() || stopping) return
        reloadExecutor.execute {
            runCatching { configManager.updateFile(changes); requestConfigReload(false) }
                .onFailure { log_error("配置后台保存失败，原文件保留：${it.message}") }
        }
    }

    override fun reloadPluginConfig() = requestConfigReload(fullRestart = false)

    private fun requestConfigReload(fullRestart: Boolean) {
        if (stopping || !::configManager.isInitialized) return
        if (!reloadBusy.compareAndSet(false, true)) {
            reloadQueued.set(true)
            if (fullRestart) fullRestartQueued.set(true)
            log_info("配置重载已在后台进行；本次请求已合并到下一轮")
            return
        }
        if (FoliaScheduler.isGlobalThread()) showBanner(true) else submit { if (!stopping) showBanner(true) }
        log_info(if (fullRestart) "正在后台准备完整重载，不会等待图片/网络线程" else "正在后台准备配置重载")
        try {
            reloadExecutor.execute {
                val prepared = configManager.prepareReloadFile()
                if (!prepared || stopping) {
                    finishReloadQueue()
                    return@execute
                }
                FoliaScheduler.runNow(this, Runnable {
                    try {
                        if (stopping || !isEnabled) return@Runnable
                        configManager.activateReload()
                        warnIfUnsafeModerationMode()
                        val changed = configManager.changedOnLastActivation()
                        fun anyChanged(vararg prefixes: String): Boolean = changed.any { path ->
                            prefixes.any { prefix -> path == prefix || path.startsWith("$prefix.") }
                        }
                        val onlineChanged = fullRestart || anyChanged("serverName", "online-list", "motd")
                        val inventoryChanged = fullRestart || anyChanged("inventory", "online-list.skin")
                        val panelChanged = fullRestart || anyChanged("bot.groups", "commands", "command-panel", "custom-commands", "language")
                        val proxyChanged = fullRestart || anyChanged("proxy")
                        val timerConfigChanged = fullRestart || anyChanged("binding.member-check", "join-approve")
                        val performanceChanged = fullRestart || anyChanged("server-notify.performance")
                        PlaceholderApiSupport.setup(this)
                        if (proxyChanged) agent.spigot.manager.ProxyBridge.initialize(this, configManager.proxyBridgeEnabled())
                        GroupCommandConfigStore.configureDataDir(runtimeDataFolder)
                        agent.state.CommandMappingStore.refresh()
                        agent.state.PermissionGroupsStore.reload()
                        submitAsync { GroupCommandConfigStore.syncFolders(configManager.groupOpenIds()) }
                        setupCommandConflictFallback()
                        if (fullRestart) QrLoginManager.stop()
                        if (inventoryChanged) initializeInventoryRendererAsync()
                        if (onlineChanged) initializeOnlineListRenderer()
                        if (fullRestart || anyChanged("chat-log")) restartChatLogRenderer()
                        agent.events.commands.CustomCommandRegistry.replace(getCustomCommands())
                        if (timerConfigChanged) {
                            agent.ai.GroupMemberVerifier.stop(); agent.ai.GroupMemberVerifier.start()
                            agent.ai.JoinApproveManager.stop(); agent.ai.JoinApproveManager.start()
                        }
                        // Timed tasks are data-file based; reconfigure on every config reload so edits made
                        // while the server is running are picked up and the previous timer is cancelled.
                        agent.tasks.TimedTaskManager.configure(runtimeDataFolder)
                        if (performanceChanged) performanceWatcher.start()
                        submitAsync {
                            initializeMarkdownTemplates()
                            if (panelChanged) QClient.syncGroupPanels()
                        }
                        if (fullRestart) launchQqClient()
                        logCommandExecutor()
                        ensureForceBindAvailable()
                        if (fullRestart) {
                            submitAsync {
                                runCatching { agent.web.WebUiServer.stop(); agent.web.WebUiServer.start() }
                            }
                        }
                        log_info(if (fullRestart) "HuHoBot 完整重载已提交；图片资源继续在后台准备" else "HuHoBot 配置已重载；图片资源继续在后台准备")
                    } catch (error: Throwable) {
                        log_error("重载提交失败，保留当前运行状态：${error.message}")
                    } finally {
                        finishReloadQueue()
                    }
                })
            }
        } catch (error: Throwable) {
            reloadBusy.set(false)
            log_error("无法提交后台重载：${error.message}")
        }
    }

    private fun finishReloadQueue() {
        reloadBusy.set(false)
        if (stopping || !reloadQueued.compareAndSet(true, false)) return
        val full = fullRestartQueued.getAndSet(false)
        if (FoliaScheduler.isGlobalThread()) requestConfigReload(full)
        else runCatching { FoliaScheduler.runNow(this, Runnable { if (!stopping) requestConfigReload(full) }) }
    }

    private fun initializeInventoryRendererAsync() {
        agent.spigot.inventory.SkinFetcher.configure(
            configManager.onlineListSkinSources(), configManager.onlineListSkinTimeoutMs(),
            agent.spigot.onlinelist.SkinPolicy.remoteLookupsEnabled(
                configManager.onlineListSkinEnabled(), server.onlineMode, configManager.offlineDefaultSkins()
            ),
            configManager.onlineListSkinCacheSize()
        )
        val custom = configManager.customInventoryBackgroundEnabled()
        val inventory = configManager.customInventoryBackgroundFile()
        val ender = configManager.customEnderChestBackgroundFile()
        val fit = configManager.customInventoryBackgroundFit()
        try {
            assetExecutor.execute {
                if (stopping) return@execute
                try {
                    InventoryRenderer.init(runtimeDataFolder, custom, inventory, ender, fit)
                    if (!stopping) log_info("背包图片资源已在后台准备完成")
                } catch (error: Throwable) {
                    if (!stopping) log_warning("背包图片资源准备失败：${error.message}")
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            if (!stopping) log_warning("背包图片资源队列已关闭：${error.message}")
        }
    }

    /**
     * 覆写公共运行时的 QQ 启动逻辑：
     * 凭据为空时不再阻塞主线程，改为在后台开启扫码授权会话
     * （控制台与 WebUI 共用同一个会话），WebUI 照常启动。
     */
    override fun launchQqClient() {
        val appId = getBotAppId()
        val secret = getBotSecret()
        if (appId.isBlank() || secret.isBlank()) {
            log_info("未配置 bot.app-id / bot.secret，已开启扫码授权：扫描控制台二维码，或打开 WebUI「QQ 机器人」页面扫码")
            QrLoginManager.start(this)
            return
        }
        submitAsync {
            try {
                QClient.launchClient(appId, secret, getQqBotLogFilePattern())
            } catch (error: Exception) {
                log_error("QQ 机器人启动失败: ${error.message}")
            }
        }
    }

    /**
     * 启动后确认强制绑定是否可用。
     *
     * QQ 客户端是异步启动的，刚开启时通常还没连上，所以这里延迟一段时间再检查；
     * 连上则保持开启，未连上则由 [ensureForceBindAvailable] 自动关闭。
     */
    private fun scheduleForceBindCheck() {
        if (!configManager.isForceBindEnabled()) return
        // submitLater 的 delay 单位是 tick（20 tick = 1 秒）
        submitLater(FORCE_BIND_CHECK_DELAY_MS / 50L) {
            ensureForceBindAvailable()
        }
    }

    // ---------------------------------------------------------------- WebUI 扫码授权桥接

    override fun getQrAuthState(): agent.web.QrAuthState = QrLoginManager.state()

    override fun startQrAuth(): Boolean = QrLoginManager.start(this)

    override fun cancelQrAuth(): Boolean = QrLoginManager.stop()

    /**
     * 完整重启：注销 MC 命令 → 重新加载配置 → 重新注册命令 → 重启 QQ 客户端。
     */
    fun fullRestart() = requestConfigReload(fullRestart = true)

    override fun createCommandExecutor(): HExecution =
        if (configManager.commandSender().equals("Hybrid", ignoreCase = true)) {
            HybridCommandExecutor(this)
        } else {
            BukkitConsoleSender(this)
        }

    override fun broadcastMessage(msg: String) {
        server.onlinePlayers.forEach { player -> FoliaScheduler.forEntity(this, player, Runnable { player.sendMessage(msg) }) }
        server.consoleSender.sendMessage(msg)
    }

    override fun sendPlayerWarning(playerName: String, message: String): Boolean {
        val player = server.getPlayerExact(playerName) ?: return false
        FoliaScheduler.forEntity(this, player, Runnable {
            player.sendMessage("§c[消息审核] §f$message")
        })
        return true
    }

    override fun broadcastMessage(msg: String, highlightedPlayers: List<String>) {
        server.onlinePlayers.forEach { player -> FoliaScheduler.forEntity(this, player, Runnable {
            player.sendMessage(msg)
            if (player.name in highlightedPlayers) player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f)
        }) }
        server.consoleSender.sendMessage(msg)
    }

    override fun onBotReceivedGroupMessage(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence)
        val botEvent = OnBotRecvMsg(
            msgPack = msgPack,
            replyTextAction = { text ->
                QClient.replyText(msgPack.groupOpenId, msgPack.messageId, msgPack.messageSequence, text)
            },
            replyMarkdownAction = { markdown, keyboard ->
                QClient.replyMarkdown(
                    msgPack.groupOpenId,
                    msgPack.messageId,
                    msgPack.messageSequence,
                    markdown,
                    keyboard
                )
            }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    override fun onBotCommand(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence).withCommand(event.rawMessage.content.orEmpty())
        val botEvent = OnBotCommand(
            msgPack = msgPack,
            replyTextAction = { text ->
                QClient.replyText(msgPack.groupOpenId, msgPack.messageId, msgPack.messageSequence, text)
            },
            replyMarkdownAction = { markdown, keyboard ->
                QClient.replyMarkdown(
                    msgPack.groupOpenId,
                    msgPack.messageId,
                    msgPack.messageSequence,
                    markdown,
                    keyboard
                )
            }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    @Volatile private var gameRoutes = GameCommandRoutes()
    private var runtimeCommandMap: CommandMap? = null
    private var fallbackCommand: Command? = null
    private val commandNamespace get() = name.lowercase(java.util.Locale.ROOT)
    private val performanceWatcher = agent.spigot.monitor.PerformanceWatcher(this)
    private val otaUpdater = agent.spigot.monitor.OtaUpdater(this)

    fun isPerformanceNotifyEnabled(): Boolean = configManager.performanceNotifyEnabled()
    fun getPerformanceTpsThreshold(): Double = configManager.performanceTpsThreshold()
    fun getPerformanceMemoryThreshold(): Double = configManager.performanceMemoryThreshold()

    override fun getGameCommandPath(command: String): String = gameRoutes.path(command)
    fun getHandoverSubcommands(): List<String> = gameRoutes.handover.toList()

    /** The full risk agreement is deliberately not copied into config/data; this warning is emitted only when active. */
    private fun warnIfUnsafeModerationMode() {
        if (configManager.moderationAllowNone()) {
            log_warning("高风险审核设置已开启：moderation.sensitive-words.allow-none=true。内置基础词已停用，仅使用自定义词表；这不能绕过QQ/Minecraft平台审核，可能导致机器人或开发者账号受限，风险由服主自行承担。")
        }
    }

    private fun declaredCommand(map: CommandMap, label: String): PluginCommand? =
        (map.getCommand("$commandNamespace:$label") as? PluginCommand)?.takeIf { it.plugin === this }

    @Suppress("UNCHECKED_CAST")
    private fun knownCommands(map: CommandMap): MutableMap<String, Command>? {
        runCatching { map.javaClass.getMethod("getKnownCommands").invoke(map) as MutableMap<String, Command> }
            .getOrNull()?.let { return it }
        var type: Class<*>? = map.javaClass
        while(type != null) {
            val current = type
            val found=runCatching { current.getDeclaredField("knownCommands").apply { isAccessible=true } }
                .getOrNull()
            if (found!=null) return runCatching { found.get(map) as MutableMap<String, Command> }.getOrNull()
            type=current.superclass
        }
        return null
    }

    /** Reconcile only our fallback. Never unregister or overwrite a command another plugin owns. */
    private fun setupCommandConflictFallback() {
        val map = runtimeCommandMap ?: (resolveCommandMapObject() as? CommandMap ?: return).also { runtimeCommandMap=it }
        val known=knownCommands(map)
        for (label in GameCommandRoutes.movable + listOf("huhobot", "hb")) {
            if (map.getCommand(label)==null) {
                val declared=declaredCommand(map,if(label=="hb")"huhobot" else label) ?: continue
                known?.putIfAbsent(label,declared)
            }
        }
        fun owned(label: String): Boolean = (map.getCommand(label) as? PluginCommand)?.plugin===this
        if (owned("huhobot")) {
            removeFallbackCommand(map)
        } else if (fallbackCommand==null && known!=null) {
            val available=listOf("huhobotagent","hba").filter { map.getCommand(it)==null }
            val label=available.firstOrNull()
            if (label!=null) {
                val handler=HuHoBotCommand(this)
                val dynamic=object : Command(label,"HuHoBot 指令冲突回退","/$label help",available.drop(1)) {
                    override fun execute(sender: org.bukkit.command.CommandSender, commandLabel: String, args: Array<out String>): Boolean =
                        handler.onCommand(sender,this,commandLabel,args)
                    override fun tabComplete(sender: org.bukkit.command.CommandSender, alias: String, args: Array<out String>): MutableList<String> =
                        handler.onTabComplete(sender,this,alias,args).toMutableList()
                }
                map.register(commandNamespace,dynamic)
                fallbackCommand=dynamic
            }
        }
        val fallback=listOf("huhobotagent","hba").firstOrNull { fallbackCommand?.let { c -> map.getCommand(it) === c }==true }
            ?: "$commandNamespace:huhobot"
        val next=GameCommandRoutes.resolve(::owned,fallback)
        if (gameRoutes != next) {
            gameRoutes=next
            if (next.handover.isNotEmpty() || next.root!="huhobot") {
                log_warning("指令冲突：实际入口为 ${next.path("huhobot")}；${next.handover.joinToString("、") { next.path(it) }}")
            }
            // Existing clients may have an older command tree; refresh only on a changed route.
            server.onlinePlayers.forEach { player -> FoliaScheduler.forEntity(this,player,Runnable { player.updateCommands() }) }
        }
    }

    private fun removeFallbackCommand(map: CommandMap) {
        val command=fallbackCommand ?: return
        knownCommands(map)?.entries?.removeIf { it.value === command }
        command.unregister(map)
        fallbackCommand=null
    }

    private fun <T : org.bukkit.event.Event> callSyncEvent(event: T): T {
        if (FoliaScheduler.isTickThread()) server.pluginManager.callEvent(event)
        else FoliaScheduler.globalFuture(this) { server.pluginManager.callEvent(event) }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        return event
    }

    /**
     * 注册运行时自定义命令，并按 pushMenu 更新 QQ 命令面板。
     *
     * **旧规范**：仅传 key/command，命令不会归属到任何扩展。
     * 新代码应使用 [registerAddon] + [registerBotCommand] 四参数重载。
     */
    @JvmOverloads
    fun registerBotCommand(
        key: String,
        command: String,
        permission: Int = 0,
        pushMenu: Boolean = true
    ): Boolean {
        val registered = CustomCommandRegistry.register(
            CustomCommandDetail(key, command, permission, pushMenu)
        )
        if (!registered) return false
        submitAsync { QClient.syncGroupPanels() }
        return true
    }

    /**
     * 注册运行时自定义命令并关联到指定扩展。
     *
     * @param addonName 已通过 [registerAddon] 注册的扩展名称
     * @param key       命令 key（QQ 群中用 `/执行 <key>` 或 `/ <key>` 触发）
     * @param command   执行的服务器命令模板
     * @param permission 权限级别，0 = 公开
     * @param pushMenu  是否同步到 QQ 命令面板
     */
    @JvmOverloads
    fun registerBotCommand(
        addonName: String,
        key: String,
        command: String,
        permission: Int = 0,
        pushMenu: Boolean = true
    ): Boolean {
        if (addonName !in AddonManager) {
            log_error("registerBotCommand 失败：扩展 '$addonName' 未注册，请先调用 registerAddon")
            return false
        }
        val registered = CustomCommandRegistry.register(
            CustomCommandDetail(key, command, permission, pushMenu)
        )
        if (!registered) return false
        AddonManager.addCommand(
            addonName,
            RegisteredCommand(
                command = key,
                describe = command,
                onlyAdmin = permission > 0,
                source = addonName
            )
        )
        submitAsync { QClient.syncGroupPanels() }
        return true
    }

    /**
     * 注册当前插件为 HuHoBot 扩展。
     * 必须在 [registerBotCommand] 之前调用，否则命令无法归属到该扩展。
     *
     * @param name        扩展名称（建议与 plugin.yml 中 name 一致）
     * @param version     版本号
     * @param description 描述
     * @param author      作者
     * @return true 表示注册成功
     */
    @JvmOverloads
    fun registerAddon(
        name: String,
        version: String = "1.0.0",
        description: String = "",
        author: String = ""
    ): Boolean {
        if (name.isBlank()) {
            log_error("registerAddon 失败：扩展名称不能为空")
            return false
        }
        AddonManager.register(Addon(name, version, description, author))
        return true
    }

    /** 卸载扩展登记。QQ 命令要另外调用 [unregisterBotCommand]。 */
    fun unregisterAddon(name: String) {
        if (name.isBlank() || name !in AddonManager) return
        AddonManager.unregister(name)
    }

    /** 注销运行时自定义命令，并按需刷新 QQ 命令面板。 */
    fun unregisterBotCommand(key: String): Boolean {
        val removed = CustomCommandRegistry.unregister(key)
        if (removed) submitAsync { QClient.syncGroupPanels() }
        return removed
    }

    /** 向配置中的所有 QQ 群发送普通文本。 */
    fun sendBotText(text: String) = sendText(text)

    /** 主动向指定 QQ 群发送普通文本。 */
    fun sendBotText(groupOpenId: String, text: String): Boolean =
        QClient.sendTextToGroup(groupOpenId, text).let { it != null }

    /** 向配置中的所有 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(markdown: String, keyboard: Keyboard? = null) = sendMarkdown(markdown, keyboard)

    /** 主动向指定 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(
        groupOpenId: String,
        markdown: String,
        keyboard: Keyboard? = null
    ): Boolean = QClient.sendMarkdownToGroup(groupOpenId, markdown, keyboard).let { it != null }

    override fun getCommandMenuList(): Map<String, Boolean> = configManager.commandMenuSwitches()
    override fun getCommandMenuPriorities(): Map<String, Int> = configManager.commandMenuPriorities()
    override fun showAdminCommandsInMenu(): Boolean = configManager.showAdminCommandsInMenu()

    override fun submit(task: Runnable): Cancelable =
        FoliaScheduler.runNow(this, task)

    override fun submitAsync(task: Runnable): Cancelable =
        FoliaScheduler.runAsync(this, task)

    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        FoliaScheduler.runLater(this, delay, task)

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        FoliaScheduler.runTimer(this, delay, period, task)

    override fun getOnlineList(): List<String> = server.onlinePlayers.map { it.name }.toMutableList()

    /** Read a packaged descriptor boolean. */
    private fun readPluginYamlSwitch(key: String): Boolean = runCatching {
        val text = javaClass.classLoader.getResource("plugin.yml")?.readText() ?: return false
        val map = org.yaml.snakeyaml.Yaml().load(text) as? Map<*, *> ?: return false
        when (val v = map[key]) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: false
            else -> false
        }
    }.getOrDefault(false)

    override fun getPlayerInventory(playerName: String): String? {
        val snapshot = findInventorySnapshot(playerName) ?: return null
        val lines = mutableListOf<String>()

        // 护甲
        val armorNames = mapOf(
            "头盔" to snapshot.armor.getOrNull(0),
            "胸甲" to snapshot.armor.getOrNull(1),
            "护腿" to snapshot.armor.getOrNull(2),
            "靴子" to snapshot.armor.getOrNull(3)
        )
        val armorLine = armorNames.map { (slot, item) ->
            "$slot: ${item?.let { formatItem(it) } ?: "空"}"
        }.joinToString(" | ")
        lines.add("=== 护甲 ===")
        lines.add(armorLine)

        // 副手
        lines.add("=== 副手 ===")
        lines.add("副手: ${snapshot.offhand?.let { formatItem(it) } ?: "空"}")

        // 物品栏（3行9列）
        lines.add("=== 物品栏 ===")
        val storage = snapshot.storage
        for (row in 0 until 3) {
            val rowItems = (0 until 9).map { col ->
                val idx = row * 9 + col
                storage.getOrNull(idx)?.let { formatItem(it) } ?: "---"
            }
            lines.add(rowItems.joinToString(" | "))
        }

        // 快捷栏
        lines.add("=== 快捷栏 ===")
        val hotbar = (0 until 9).map { idx ->
            storage.getOrNull(idx)?.let { formatItem(it) } ?: "---"
        }
        lines.add(hotbar.joinToString(" | "))

        return lines.joinToString("\n")
    }

    override fun getPlayerInventoryImage(playerName: String): ByteArray? {
        val snapshot = findInventorySnapshot(playerName) ?: return null
        return try {
            InventoryRenderer.render(snapshot)
        } catch (e: Exception) {
            log_error("背包渲染失败: ${e.message}")
            null
        }
    }

    override fun getPlayerEnderChestImage(playerName: String): ByteArray? {
        val snapshot = findInventorySnapshot(playerName) ?: return null
        return try {
            InventoryRenderer.renderEnderChest(snapshot)
        } catch (e: Exception) {
            log_error("末影箱渲染失败: ${e.message}")
            null
        }
    }

    override fun getPlayerInventoryItemImage(playerName: String, slot: Int, enderChest: Boolean): ByteArray? {
        val snapshot = findInventorySnapshot(playerName) ?: return null
        val item = (if (enderChest) snapshot.enderChest.getOrNull(slot) else inventorySlot(snapshot, slot))
            ?: return null
        return InventoryRenderer.renderSingleItem(snapshot, item, slot, enderChest)
    }

    override fun getPlayerInventoryItemInfo(playerName: String, slot: Int, enderChest: Boolean): String? {
        val snapshot = findInventorySnapshot(playerName) ?: return null
        val item = (if (enderChest) snapshot.enderChest.getOrNull(slot) else inventorySlot(snapshot, slot)) ?: return null
        return describeItem(item, slot, enderChest)
    }

    private fun inventorySlot(snapshot: InventorySnapshot, slot: Int): org.bukkit.inventory.ItemStack? = when {
        slot in 0..35 -> snapshot.storage.getOrNull(slot)
        slot in 36..39 -> snapshot.armor.getOrNull(39-slot) // 36 feet, 39 head in Bukkit inventory IDs
        slot == 40 -> snapshot.offhand
        else -> null
    }

    private fun describeItem(item: org.bukkit.inventory.ItemStack, slot: Int, enderChest: Boolean): String = buildString {
        appendLine("槽位：${if (enderChest) "末影箱.$slot" else slot}")
        appendLine("物品：${formatItem(item)}")
        appendLine("Material：${item.type.key}")
        item.itemMeta?.let { meta ->
            if (meta.hasLore()) appendLine("描述：${meta.lore!!.joinToString(" / ") { it.replace(Regex("§."), "") }}")
            if (meta.hasEnchants()) appendLine("附魔：${meta.enchants.entries.joinToString("、") { "${it.key.key} ${it.value}" }}")
            meta.attributeModifiers?.let { modifiers ->
                if (!modifiers.isEmpty) appendLine("属性：${modifiers.entries().joinToString("、") { "${it.key.name} ${it.value.amount}" }}")
            }
            appendLine("NBT/组件：${serializeItemData(item)}")
        }
    }.trim()

    private fun serializeItemData(item: org.bukkit.inventory.ItemStack): String = runCatching {
        val cls = Class.forName("org.bukkit.craftbukkit.util.CraftMagicNumbers")
        val instance = cls.getField("INSTANCE").get(null)
        val jsonMethod = cls.methods.firstOrNull { it.name == "serializeItemAsJson" && it.parameterCount == 1 }
        if (jsonMethod != null) {
            jsonMethod.invoke(instance, item).toString().take(1800)
        } else {
            val bytes = cls.methods.first { it.name == "serializeItem" && it.parameterCount == 1 }
                .invoke(instance, item) as ByteArray
            "base64=" + java.util.Base64.getEncoder().encodeToString(bytes).take(1800)
        }
    }.getOrElse { "不可用（${it.javaClass.simpleName}）" }

    private fun findInventorySnapshot(playerName: String): InventorySnapshot? =
        offlineInventorySnapshots.find(playerName)

    private fun <T> onServerThread(action: () -> T): T? = try {
        if (FoliaScheduler.isTickThread()) action()
        else FoliaScheduler.globalFuture(this, action).get(5, java.util.concurrent.TimeUnit.SECONDS)
    } catch (error: Exception) { log_error("服务器操作失败: ${error.message}"); null }

    private fun pluginConfigMode(): String =
        if (::configManager.isInitialized) configManager.serverModeOverride()?.name?.lowercase() ?: "auto" else "auto"

    private fun restartChatLogRenderer() {
        chatLogManager?.stop()
        if (agent.chatlog.ChatLogBridge.handler === chatLogManager) agent.chatlog.ChatLogBridge.handler = null
        chatLogManager = null
        if (!configManager.chatLogEnabled()) {
            log_info("聊天记录图片已关闭（chat-log.enabled=false）")
            return
        }
        try {
            val manager = agent.spigot.chatlog.ChatLogManager(this, configManager).also { it.start() }
            chatLogManager = manager
            agent.chatlog.ChatLogBridge.handler = manager
        } catch (error: Throwable) {
            log_warning("聊天记录图片初始化失败：${error.message}")
        }
    }

    private fun initializeInventoryRenderer() = initializeInventoryRendererAsync()

    /**
     * 初始化（或重新加载）在线列表本地渲染器，并注册到 [OnlineListBridge]。
     * 失败时不中断插件，查在线命令自动回退到 MOTD 外部 API 图片。
     */
    private fun initializeOnlineListRenderer() {
        onlineListManager?.stop()
        if (OnlineListBridge.handler === onlineListManager) OnlineListBridge.handler = null
        onlineListManager = null
        if (!configManager.onlineListEnabled()) {
            log_info("在线列表本地渲染已关闭（online-list.enabled=false），查在线将使用 MOTD 外部 API 图片")
            return
        }
        try {
            val manager = OnlineListManager(this, configManager).also { it.start() }
            onlineListManager = manager
            OnlineListBridge.handler = manager
        } catch (error: Throwable) {
            log_warning("在线列表渲染器初始化失败，查在线将回退到 MOTD 外部 API 图片: ${error.message}")
            OnlineListBridge.handler = null
        }
    }

    private fun formatItem(item: org.bukkit.inventory.ItemStack): String {
        if (item.type.isAir) return "---"
        val meta = item.itemMeta
        val rawName = when {
            meta?.hasDisplayName() == true -> meta.displayName
            else -> MATERIAL_CN[item.type] ?: item.type.name.replace("_", " ").lowercase()
                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
        // 去除 Minecraft § 颜色/格式码
        val name = rawName.replace(Regex("§[0-9a-fk-or]"), "")
        val amount = item.amount
        val isDamageable = item.type.maxDurability > 0
        val durability = if (isDamageable) {
            val dmg = (meta as? org.bukkit.inventory.meta.Damageable)?.damage ?: 0
            if (dmg > 0) " (${item.type.maxDurability - dmg}/${item.type.maxDurability})" else ""
        } else ""
        val enchant = if (meta?.hasEnchants() == true) " *" else ""
        return when {
            amount > 1 && durability.isNotEmpty() -> "$name x$amount$durability$enchant"
            amount > 1 -> "$name x$amount$enchant"
            durability.isNotEmpty() -> "$name$durability$enchant"
            else -> "$name$enchant"
        }
    }

    private val MATERIAL_CN = mapOf(
        org.bukkit.Material.DIAMOND_SWORD to "钻石剑",
        org.bukkit.Material.DIAMOND_PICKAXE to "钻石镐",
        org.bukkit.Material.DIAMOND_AXE to "钻石斧",
        org.bukkit.Material.DIAMOND_SHOVEL to "钻石锹",
        org.bukkit.Material.DIAMOND_HOE to "钻石锄",
        org.bukkit.Material.IRON_SWORD to "铁剑",
        org.bukkit.Material.IRON_PICKAXE to "铁镐",
        org.bukkit.Material.IRON_AXE to "铁斧",
        org.bukkit.Material.IRON_SHOVEL to "铁锹",
        org.bukkit.Material.IRON_HOE to "铁锄",
        org.bukkit.Material.GOLDEN_SWORD to "金剑",
        org.bukkit.Material.GOLDEN_PICKAXE to "金镐",
        org.bukkit.Material.GOLDEN_AXE to "金斧",
        org.bukkit.Material.GOLDEN_SHOVEL to "金锹",
        org.bukkit.Material.GOLDEN_HOE to "金锄",
        org.bukkit.Material.STONE_SWORD to "石剑",
        org.bukkit.Material.STONE_PICKAXE to "石镐",
        org.bukkit.Material.STONE_AXE to "石斧",
        org.bukkit.Material.STONE_SHOVEL to "石锹",
        org.bukkit.Material.STONE_HOE to "石锄",
        org.bukkit.Material.WOODEN_SWORD to "木剑",
        org.bukkit.Material.WOODEN_PICKAXE to "木镐",
        org.bukkit.Material.WOODEN_AXE to "木斧",
        org.bukkit.Material.WOODEN_SHOVEL to "木锹",
        org.bukkit.Material.WOODEN_HOE to "木锄",
        org.bukkit.Material.NETHERITE_SWORD to "下界合金剑",
        org.bukkit.Material.NETHERITE_PICKAXE to "下界合金镐",
        org.bukkit.Material.NETHERITE_AXE to "下界合金斧",
        org.bukkit.Material.NETHERITE_SHOVEL to "下界合金锹",
        org.bukkit.Material.NETHERITE_HOE to "下界合金锄",
        org.bukkit.Material.BOW to "弓",
        org.bukkit.Material.CROSSBOW to "弩",
        org.bukkit.Material.TRIDENT to "三叉戟",
        org.bukkit.Material.SHIELD to "盾牌",
        org.bukkit.Material.FISHING_ROD to "钓鱼竿",
        org.bukkit.Material.FLINT_AND_STEEL to "打火石",
        org.bukkit.Material.SHEARS to "剪刀",
        org.bukkit.Material.END_ROD to "末地烛",
        org.bukkit.Material.ARROW to "箭矢",
        org.bukkit.Material.SPECTRAL_ARROW to "光灵箭",
        org.bukkit.Material.TIPPED_ARROW to "药水箭",
        org.bukkit.Material.BONE to "骨头",
        org.bukkit.Material.BONE_MEAL to "骨粉",
        org.bukkit.Material.DIAMOND to "钻石",
        org.bukkit.Material.EMERALD to "绿宝石",
        org.bukkit.Material.GOLD_INGOT to "金锭",
        org.bukkit.Material.IRON_INGOT to "铁锭",
        org.bukkit.Material.NETHERITE_INGOT to "下界合金锭",
        org.bukkit.Material.NETHERITE_SCRAP to "下界合金碎片",
        org.bukkit.Material.COAL to "煤炭",
        org.bukkit.Material.CHARCOAL to "木炭",
        org.bukkit.Material.REDSTONE to "红石",
        org.bukkit.Material.LAPIS_LAZULI to "青金石",
        org.bukkit.Material.DIAMOND_HELMET to "钻石头盔",
        org.bukkit.Material.DIAMOND_CHESTPLATE to "钻石胸甲",
        org.bukkit.Material.DIAMOND_LEGGINGS to "钻石护腿",
        org.bukkit.Material.DIAMOND_BOOTS to "钻石靴子",
        org.bukkit.Material.IRON_HELMET to "铁头盔",
        org.bukkit.Material.IRON_CHESTPLATE to "铁胸甲",
        org.bukkit.Material.IRON_LEGGINGS to "铁护腿",
        org.bukkit.Material.IRON_BOOTS to "铁靴子",
        org.bukkit.Material.GOLDEN_HELMET to "金头盔",
        org.bukkit.Material.GOLDEN_CHESTPLATE to "金胸甲",
        org.bukkit.Material.GOLDEN_LEGGINGS to "金护腿",
        org.bukkit.Material.GOLDEN_BOOTS to "金靴子",
        org.bukkit.Material.LEATHER_HELMET to "皮革头盔",
        org.bukkit.Material.LEATHER_CHESTPLATE to "皮革胸甲",
        org.bukkit.Material.LEATHER_LEGGINGS to "皮革护腿",
        org.bukkit.Material.LEATHER_BOOTS to "皮革靴子",
        org.bukkit.Material.CHAINMAIL_HELMET to "锁链头盔",
        org.bukkit.Material.CHAINMAIL_CHESTPLATE to "锁链胸甲",
        org.bukkit.Material.CHAINMAIL_LEGGINGS to "锁链护腿",
        org.bukkit.Material.CHAINMAIL_BOOTS to "锁链靴子",
        org.bukkit.Material.NETHERITE_HELMET to "下界合金头盔",
        org.bukkit.Material.NETHERITE_CHESTPLATE to "下界合金胸甲",
        org.bukkit.Material.NETHERITE_LEGGINGS to "下界合金护腿",
        org.bukkit.Material.NETHERITE_BOOTS to "下界合金靴子",
        org.bukkit.Material.TURTLE_HELMET to "海龟头盔",
        org.bukkit.Material.ELYTRA to "鞘翅",
        org.bukkit.Material.TOTEM_OF_UNDYING to "不死图腾",
        org.bukkit.Material.EXPERIENCE_BOTTLE to "经验瓶",
        org.bukkit.Material.ENDER_PEARL to "末影珍珠",
        org.bukkit.Material.ENDER_EYE to "末影之眼",
        org.bukkit.Material.ENDER_CHEST to "末影箱",
        org.bukkit.Material.BREAD to "面包",
        org.bukkit.Material.COOKED_BEEF to "熟牛排",
        org.bukkit.Material.COOKED_PORKCHOP to "熟猪排",
        org.bukkit.Material.COOKED_MUTTON to "熟羊肉",
        org.bukkit.Material.COOKED_CHICKEN to "熟鸡肉",
        org.bukkit.Material.COOKED_RABBIT to "熟兔肉",
        org.bukkit.Material.COOKED_COD to "熟鳕鱼",
        org.bukkit.Material.COOKED_SALMON to "熟鲑鱼",
        org.bukkit.Material.GOLDEN_APPLE to "金苹果",
        org.bukkit.Material.ENCHANTED_GOLDEN_APPLE to "附魔金苹果",
        org.bukkit.Material.APPLE to "苹果",
        org.bukkit.Material.CARROT to "胡萝卜",
        org.bukkit.Material.GOLDEN_CARROT to "金胡萝卜",
        org.bukkit.Material.POTATO to "土豆",
        org.bukkit.Material.BAKED_POTATO to "烤土豆",
        org.bukkit.Material.MUSHROOM_STEW to "蘑菇煲",
        org.bukkit.Material.BEETROOT to "甜菜根",
        org.bukkit.Material.BEETROOT_SOUP to "甜菜汤",
        org.bukkit.Material.MELON_SLICE to "西瓜片",
        org.bukkit.Material.PUMPKIN_PIE to "南瓜派",
        org.bukkit.Material.COOKIE to "曲奇",
        org.bukkit.Material.CAKE to "蛋糕",
        org.bukkit.Material.WITHER_SKELETON_SPAWN_EGG to "凋灵骷髅刷怪蛋",
        org.bukkit.Material.ZOMBIE_SPAWN_EGG to "僵尸刷怪蛋",
        org.bukkit.Material.SKELETON_SPAWN_EGG to "骷髅刷怪蛋",
        org.bukkit.Material.CREEPER_SPAWN_EGG to "苦力怕刷怪蛋",
        org.bukkit.Material.SPIDER_SPAWN_EGG to "蜘蛛刷怪蛋",
        org.bukkit.Material.PIG_SPAWN_EGG to "猪刷怪蛋",
        org.bukkit.Material.COW_SPAWN_EGG to "牛刷怪蛋",
        org.bukkit.Material.CHICKEN_SPAWN_EGG to "鸡刷怪蛋",
        org.bukkit.Material.SHEEP_SPAWN_EGG to "羊刷怪蛋",
        org.bukkit.Material.PLAYER_HEAD to "玩家头颅",
        org.bukkit.Material.SKELETON_SKULL to "骷髅头颅",
        org.bukkit.Material.WITHER_SKELETON_SKULL to "凋灵骷髅头颅",
        org.bukkit.Material.ZOMBIE_HEAD to "僵尸头颅",
        org.bukkit.Material.CREEPER_HEAD to "苦力怕头颅",
        org.bukkit.Material.DRAGON_HEAD to "龙头",
        org.bukkit.Material.END_CRYSTAL to "末地水晶",
        org.bukkit.Material.BEDROCK to "基岩",
        org.bukkit.Material.BARRIER to "屏障",
        org.bukkit.Material.COMMAND_BLOCK to "命令方块",
        org.bukkit.Material.KNOWLEDGE_BOOK to "知识之书",
        org.bukkit.Material.ENCHANTED_BOOK to "附魔书",
        org.bukkit.Material.WRITTEN_BOOK to "成书"
    )
    override fun getConfigFile(): File = configManager.configFile
    override fun getDataDirectory(): File = runtimeDataFolder
    override fun getBotAppId(): String = configManager.botAppId()
    override fun getBotSecret(): String = configManager.botSecret()
    override fun getChatFormat(): ChatFormat = configManager.chatFormat()
    override fun getPlayerEventFormat(): PlayerEventFormat = configManager.playerEventFormat()
    override fun getMarkdownFiles(): Map<String, String> = configManager.markdownFiles()
    override fun getMotd(): Motd = configManager.motd()
    override fun getWhiteList(): WhiteList = configManager.whiteList()
    override fun getFilterRegexList(): List<String> = configManager.filterRegexList()
    override fun getAdminMode(): AdminMode = configManager.adminMode()
    override fun getAdminList(): List<String> = configManager.adminOpenIds()
    override fun getGroupOpenIdList(): List<String> = configManager.groupOpenIds()
    override fun isUpdateCheckEnabled(): Boolean = configManager.isUpdateCheckEnabled()
    override fun getUpdateCheckUrls(): String = configManager.updateCheckUrls()
    override fun isAgentFetchResultHidden(): Boolean = configManager.isAgentFetchResultHidden()
    override fun isPlaceholderApiEnabled(): Boolean = configManager.isPlaceholderApiEnabled()
    override fun applyPlaceholders(playerName: String?, text: String): String =
        PlaceholderApiSupport.apply(playerName, text)

    override fun addGroupOpenId(groupOpenId: String): Boolean {
        val openId = groupOpenId.trim()
        if (openId.isEmpty() || !configManager.isAutoAddGroupsEnabled()) return false
        if (configManager.groupOpenIds().contains(openId)) return false
        return try {
            val groups = configManager.groupOpenIds() + openId
            config.set("bot.groups", groups)
            saveConfig()
            GroupCommandConfigStore.syncFolders(groups)
            log_info("已自动把群 $openId 添加到 bot.groups")
            reloadRuntimeConfig()
            true
        } catch (error: Exception) {
            log_error("自动添加群 $openId 失败: ${error.message}")
            false
        }
    }

    override fun installAddon(fileName: String, bytes: ByteArray): Boolean = writeAddonFile(fileName) { it.writeBytes(bytes) }

    override fun removeAddon(fileName: String): Boolean {
        val safeName = fileName.trim()
        if (!isSafeAddonFileName(safeName)) {
            log_warning("拒绝删除非法的附属插件文件名: $fileName")
            return false
        }
        return try {
            val target = pluginsFolder().resolve(safeName)
            // 文件已不存在时同样视为删除成功，交由调用方清理安装记录
            !target.exists() || target.delete()
        } catch (error: Exception) {
            log_error("删除附属插件 $safeName 失败: ${error.message}")
            false
        }
    }

    private fun writeAddonFile(fileName: String, write: (File) -> Unit): Boolean {
        val safeName = fileName.trim()
        if (!isSafeAddonFileName(safeName)) {
            log_warning("拒绝写入非法的附属插件文件名: $fileName")
            return false
        }
        return try {
            val folder = pluginsFolder()
            if (!folder.isDirectory && !folder.mkdirs()) return false
            write(folder.resolve(safeName))
            true
        } catch (error: Exception) {
            log_error("写入附属插件 $safeName 失败: ${error.message}")
            false
        }
    }

    private fun pluginsFolder(): File = dataFolder.parentFile?.takeIf { it.isDirectory } ?: File("plugins")

    private fun isSafeAddonFileName(name: String): Boolean =
        name.isNotEmpty() && !name.contains("..") && !name.contains('/') && !name.contains('\\')

    override fun shouldSuppressQqBotConsoleOutput(): Boolean =
        configManager.suppressQqBotConsoleOutput()

    override fun getFullAmount(): Boolean = configManager.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = configManager.commandSwitches()
    override fun getAuditBaseUrl(): String? = configManager.auditBaseUrl()
    override fun getAuditApiKey(): String? = configManager.auditApiKey()
    override fun getAuditModel(): String? = configManager.auditModel()
    override fun isModerationAiReviewEnabled(): Boolean = configManager.moderationAiReviewEnabled()
    override fun isModerationAutoRecallEnabled(): Boolean = configManager.moderationAutoRecallEnabled()
    override fun isModerationAllowNone(): Boolean = configManager.moderationAllowNone()
    override fun getCustomCommands(): List<CustomCommandDetail> = configManager.customCommands()
    override fun getBotName(): String = configManager.botName()
    override fun getServerName(): String = configManager.serverName()
    override fun getPlatform(): String = "Spigot"
    override fun getPluginVersion(): String = description.version
    override fun getServerVersion(): String = server.version

    override fun getAgentEnabled(): Boolean = configManager.agentEnabled()
    override fun getAgentBaseUrl(): String? = configManager.agentBaseUrl()
    override fun getAgentApiKey(): String? = configManager.agentApiKey()
    override fun getAgentModel(): String? = configManager.agentModel()
    override fun getAgentCommandMode(): AgentCommandMode = configManager.agentCommandMode()

    override fun getAgentThink(): Boolean = configManager.agentThink()
    override fun getAgentMaxTokens(): Int = configManager.agentMaxTokens()
    override fun getAgentTimeoutSeconds(): Int = configManager.agentTimeoutSeconds()
    override fun getAgentMaxSteps(): Int = configManager.agentMaxSteps()
    override fun getAgentMaxWaitSeconds(): Int = configManager.agentMaxWaitSeconds()
    override fun getAgentSystemPrompt(): String = configManager.agentSystemPrompt()
    override fun getAgentTools(): List<String> = configManager.agentTools()
    override fun getAgentSearchProvider(): String = configManager.agentSearchProvider()
    override fun getAgentSearchApiUrl(): String = configManager.agentSearchApiUrl()
    override fun getAgentSearchApiKey(): String = configManager.agentSearchApiKey()

    override fun getStorageType(): String = configManager.storageType()
    override fun getLanguage(): String = configManager.language()
    override fun isDebugEnabled(): Boolean = configManager.debugEnabled()

    override fun getChatAiEnabled(): Boolean = configManager.chatAiEnabled()
    override fun getChatAiBaseUrl(): String? = configManager.chatAiBaseUrl()
    override fun getChatAiApiKey(): String? = configManager.chatAiApiKey()
    override fun getChatAiModel(): String? = configManager.chatAiModel()
    override fun getChatAiTrigger(): String = configManager.chatAiTrigger()
    override fun getChatAiThink(): Boolean = configManager.chatAiThink()
    override fun getChatAiThinkLevel(): String = configManager.chatAiThinkLevel()
    override fun getChatAiThinkParam(): String = configManager.chatAiThinkParam()
    override fun getChatAiSystemPrompt(): String = configManager.chatAiSystemPrompt()
    override fun getChatAiTools(): List<String> = configManager.chatAiTools()
    override fun getChatAiMaxContext(): Int = configManager.chatAiMaxContext()
    override fun getChatAiCompressTarget(): Int = configManager.chatAiCompressTarget()
    override fun getChatAiCompressKeep(): Int = configManager.chatAiCompressKeep()
    override fun getChatAiSearchProvider(): String = configManager.chatAiSearchProvider()
    override fun getChatAiSearchApiUrl(): String = configManager.chatAiSearchApiUrl()
    override fun getChatAiSearchApiKey(): String = configManager.chatAiSearchApiKey()

    override fun getSearchProvider(): String = configManager.searchProvider()
    override fun getSearchApiUrl(): String = configManager.searchApiUrl()
    override fun getSearchApiKey(): String = configManager.searchApiKey()
    override fun getSearchResultCount(): Int = configManager.searchResultCount()

    override fun getProxyEnabled(): Boolean = configManager.proxyEnabled()

    override fun isTtsEnabled(): Boolean = configManager.ttsEnabled()
    override fun getTtsBaseUrl(): String = configManager.ttsBaseUrl()
    override fun getTtsApiKey(): String = configManager.ttsApiKey()
    override fun getTtsPublicBaseUrl(): String = configManager.ttsPublicBaseUrl()
    override fun getTtsFormat(): String = configManager.ttsFormat()
    override fun getTtsPresetModel(): String = configManager.ttsPresetModel()
    override fun getTtsNaturalModel(): String = configManager.ttsNaturalModel()
    override fun getTtsCloneModel(): String = configManager.ttsCloneModel()
    override fun getTtsPresetDefaultVoice(): String = configManager.ttsPresetDefaultVoice()
    override fun getTtsNaturalDefaultVoice(): String = configManager.ttsNaturalDefaultVoice()
    override fun getTtsCloneVoicesDir(): String = configManager.ttsCloneVoicesDir()
    override fun getTtsCloneDefaultVoice(): String = configManager.ttsCloneDefaultVoice()
    override fun getTtsMaxPerTurn(): Int = configManager.ttsMaxPerTurn()

    override fun getSendPolicy(): String = configManager.sendPolicy()
    override fun getGroupPassiveReplyLimit(groupOpenId: String): Int = configManager.groupPassiveReplyLimit(groupOpenId)

    override fun isMemberCheckEnabled(): Boolean = configManager.memberCheckEnabled()
    override fun getMemberCheckIntervalMinutes(): Int = configManager.memberCheckIntervalMinutes()
    override fun isMemberCheckNotifyGroup(): Boolean = configManager.memberCheckNotifyGroup()
    override fun getBlacklistOpenIds(): List<String> = configManager.blacklistOpenIds()
    override fun isBlacklistBlockMessages(): Boolean = configManager.blacklistBlockMessages()

    override fun getCommandAliasMap(): Map<String, String> = configManager.commandAliasMap()
    override fun getCommandAliasRename(): Map<String, String> = configManager.commandAliasRename()

    override fun getProxyType(): String = configManager.proxyType()
    override fun getProxyHost(): String = configManager.proxyHost()
    override fun getProxyPort(): Int = configManager.proxyPort()

    override fun isJoinApproveEnabled(): Boolean = configManager.joinApproveEnabled()
    override fun getJoinApproveIntervalSeconds(): Int = configManager.joinApproveIntervalSeconds()
    override fun getJoinApproveStrategies(): List<com.alibaba.fastjson.JSONObject> = configManager.joinApproveStrategies()

    override fun getServerDiagnostics(): String? = runCatching { buildServerDiagnostics() }.getOrNull()

    override fun getPlayerStatsText(playerName: String): String? =
        server.getPlayerExact(playerName)?.let { player ->
            if (FoliaScheduler.owns(player)) runCatching { buildPlayerStats(playerName) }.getOrNull()
            else if (FoliaScheduler.isTickThread()) null
            else runCatching { FoliaScheduler.entityFuture(this, player) { buildPlayerStats(playerName) }.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        }

    override fun getProtocolSummary(): String? = runCatching { buildProtocolSummary() }.getOrNull()

    /** 协议与跨版本支持摘要：本服协议 + ViaVersion 支持范围（防御式反射，失败返回 null）。 */
    private fun buildProtocolSummary(): String? = runCatching {
        val via = Class.forName("com.viaversion.viaversion.api.Via")
        val api = via.getMethod("getAPI").invoke(null)
        val supported=agent.spigot.onlinelist.SkinResolver.call(api,"getSupportedVersions") as? Collection<*> ?: return null
        val pv=Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion")
        val get=pv.getMethod("getProtocol",Int::class.javaPrimitiveType)
        val names=supported.mapNotNull { item ->
            val id=(item as? Number)?.toInt() ?: return@mapNotNull null
            val value=get.invoke(null,id)
            val name=agent.spigot.onlinelist.SkinResolver.call(value,"getName")?.toString() ?: return@mapNotNull null
            if(name.contains("snapshot",true)||name.contains("unknown",true))null else id to name
        }.sortedBy { it.first }
        if(names.isEmpty()) null else names.first().second+" ~ "+names.last().second+"（Via 转换范围）"
    }.getOrNull()

    /** 服务器实时诊断文本（TPS 反射 Paper/Spigot 双兼容，失败项自动跳过）。 */
    private fun buildServerDiagnostics(): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
        val maxMb = runtime.maxMemory() / 1048576
        val uptimeMin = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime / 60000
        val tps = try {
            val spigot = org.bukkit.Bukkit::class.java.getMethod("spigot").invoke(null)
            spigot.javaClass.getMethod("getTPS").invoke(spigot) as DoubleArray
        } catch (_: Throwable) {
            null
        }
        val mspt = try {
            org.bukkit.Bukkit.getServer().javaClass.getMethod("getAverageTickTime")
                .invoke(org.bukkit.Bukkit.getServer()) as? Double
        } catch (_: Throwable) {
            null
        }
        return buildString {
            appendLine("服务端: ${org.bukkit.Bukkit.getName()} ${org.bukkit.Bukkit.getVersion()}")
            if (tps != null && tps.size >= 3) {
                appendLine(String.format("TPS(1m/5m/15m): %.1f / %.1f / %.1f", tps[0], tps[1], tps[2]))
            }
            if (mspt != null) appendLine(String.format("平均 MSPT: %.1f ms", mspt))
            appendLine("内存: ${usedMb}MB 已用 / ${maxMb}MB 上限")
            appendLine("在线玩家: ${server.onlinePlayers.size}/${server.maxPlayers}")
            appendLine("运行时长: $uptimeMin 分钟")
        }.trim()
    }

    /** 玩家统计文本（在线玩家；统计枚举名跨版本防御）。 */
    private fun buildPlayerStats(playerName: String): String? {
        val player = server.getPlayerExact(playerName) ?: return null
        fun stat(name: String): Int = try {
            player.getStatistic(org.bukkit.Statistic.valueOf(name))
        } catch (_: Throwable) {
            -1
        }
        val kills = stat("PLAYER_KILLS")
        val mobKills = stat("MOB_KILLS")
        val deaths = stat("DEATHS")
        val playTicks = stat("TOTAL_WORLD_TIME").takeIf { it >= 0 } ?: stat("PLAY_ONE_MINUTE")
        val minutes = if (playTicks > 0) playTicks / (20 * 60) else 0
        val loc = player.location
        return buildString {
            appendLine("玩家 ${player.name}:")
            if (kills >= 0) appendLine("击杀(玩家/生物): $kills / $mobKills")
            if (deaths >= 0) appendLine("死亡: $deaths")
            if (minutes > 0) appendLine("游戏时长: 约 $minutes 分钟")
            appendLine("等级: ${player.level}(经验 $player.totalExperience)")
            appendLine("生命: ${"%.1f".format(player.health)}/20 饥饿: ${player.foodLevel}/20")
            appendLine("位置: ${loc.world?.name} @ ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}")
        }.trim()
    }
    override fun getAgentThinkVisibility(): String = configManager.agentThinkVisibility()
    override fun getAgentThinkLevel(): String = configManager.agentThinkLevel()
    override fun getAgentThinkParam(): String = configManager.agentThinkParam()
    override fun getAgentThinkGuide(): Boolean = configManager.agentThinkGuide()
    override fun getAgentThinkGuidePrompt(): String = configManager.agentThinkGuidePrompt()
    override fun getAgentCompressTarget(): Int = configManager.agentCompressTarget()
    override fun getAgentCompressKeep(): Int = configManager.agentCompressKeep()
    override fun getAgentCompressPrompt(): String = configManager.agentCompressPrompt()
    override fun getAgentTruncateDrop(): Int = configManager.agentTruncateDrop()

    override fun setAgentThink(enabled: Boolean): Boolean = saveAgentConfig("agent.think", enabled)

    override fun setAgentThinkVisibility(visibility: String): Boolean =
        saveAgentConfig("agent.think-visibility", visibility)

    override fun setAgentThinkLevel(level: String): Boolean = saveAgentConfig("agent.think-level", level)

    override fun setAgentThinkGuide(enabled: Boolean): Boolean = saveAgentConfig("agent.think-guide", enabled)

    override fun setAgentCompressTarget(target: Int): Boolean =
        saveAgentConfig("agent.context.compress.target", target)

    override fun setAgentCompressKeep(keep: Int): Boolean =
        saveAgentConfig("agent.context.compress.keep", keep)

    /**
     * 写入单个 Agent 配置项。
     *
     * 深度思考、上下文压缩等设置通过 WebUI 修改，效果必须立刻生效，
     * 因此统一走 [applyWebUiConfigChanges] 这条通道：写 config.yml、刷新配置缓存，不用重启。
     */
    private fun saveAgentConfig(path: String, value: Any): Boolean {
        val changes = JSONObject()
        changes[path] = value
        return applyWebUiConfigChanges(changes)
    }

    override fun getBindingRequireGameVerification(): Boolean = configManager.bindingRequireGameVerification()

    override fun getMaxBindings(): Int = configManager.maxBindings()

    override fun isForceBindEnabled(): Boolean = configManager.isForceBindEnabled()

    override fun getForceBindGroups(): List<String> = configManager.forceBindGroups()

    override fun getVerifyExemptPlayers(): List<String> = configManager.verifyExemptPlayers()

    /**
     * 写回免验证名单。
     *
     * 走 [applyWebUiConfigChanges] 这条通用写入通道，保证 config.yml 落盘后
     * 配置缓存同步刷新，不必重启即可生效。
     */    private fun saveVerifyExemptPlayers(players: List<String>): Boolean {
        val changes = JSONObject()
        changes["binding.verify-exempt"] = JSONArray(players)
        return applyWebUiConfigChanges(changes)
    }

    override fun addVerifyExemptPlayer(playerName: String): Boolean {
        if (playerName.isBlank()) return false
        if (isVerifyExempt(playerName)) return true
        return saveVerifyExemptPlayers(configManager.verifyExemptPlayers() + playerName)
    }

    override fun removeVerifyExemptPlayer(playerName: String): Boolean {
        if (playerName.isBlank()) return false
        val remaining = configManager.verifyExemptPlayers()
            .filterNot { it.equals(playerName, ignoreCase = true) }
        return saveVerifyExemptPlayers(remaining)
    }

    /**
     * 解绑后立即踢出在线玩家（仅强制绑定开启时由调用方触发）。
     *
     * 不这样做的话，玩家可以先绑定进服、再在 QQ 里解绑，之后继续留在服务器里，
     * 直到下次进服才被守卫拦下。
     */
    override fun kickUnboundPlayer(playerName: String): Boolean {
        // 免验证玩家解绑后照样能进服，踢出没有意义
        if (isVerifyExempt(playerName)) return false
        val target = server.getPlayerExact(playerName) ?: return false
        val separator = "${ChatColor.DARK_GRAY}${ChatColor.STRIKETHROUGH}${"─".repeat(30)}"
        val reason = buildString {
            appendLine(separator)
            appendLine("${ChatColor.RED}${ChatColor.BOLD}绑定已解除")
            appendLine(separator)
            appendLine("${ChatColor.GRAY}你的 QQ 绑定已被解除，本次游戏会话已结束。")
            appendLine("${ChatColor.GRAY}重新进入服务器时需要重新绑定。")
        }
        // 放到下一 tick，避免在消息回调里直接改变玩家状态（Folia 下必须走实体区域线程）
        FoliaScheduler.runForEntity(this, target, Runnable { target.kickPlayer(reason) })
        log_info("强制绑定: $playerName 已解除绑定，踢出在线会话")
        return true
    }

    /**
     * 账号被锁定后立即踢出在线玩家。
     *
     * 锁定通常发生在账号被盗或借出时，只拦截下次进服不够，必须把当前会话也断开。
     * 免验证名单同样不豁免：锁定的优先级高于免验证。
     */
    override fun kickLockedPlayer(playerName: String): Boolean {
        val target = server.getPlayerExact(playerName) ?: return false
        val separator = "${ChatColor.DARK_GRAY}${ChatColor.STRIKETHROUGH}${"─".repeat(30)}"
        val reason = buildString {
            appendLine(separator)
            appendLine("${ChatColor.RED}${ChatColor.BOLD}账号已被锁定")
            appendLine(separator)
            appendLine("${ChatColor.GRAY}该游戏账号已被绑定者锁定，本次游戏会话已结束。")
            appendLine("${ChatColor.GRAY}如需恢复，请在 QQ 群执行 /解除锁定 后再重新连接。")
        }
        // 放到下一 tick，避免在消息回调里直接改变玩家状态（Folia 下必须走实体区域线程）
        FoliaScheduler.runForEntity(this, target, Runnable { target.kickPlayer(reason) })
        log_info("账号锁定: $playerName 已被锁定，踢出在线会话")
        return true
    }

    /**
     * 强制绑定的前置条件是 QQ 机器人可用：玩家被踢出后要能在 QQ 群完成绑定。
     *
     * QQ 连接失败时若仍开着强制绑定，所有未绑定玩家都会被挡在门外且无法完成绑定，
     * 因此这里直接把开关改回 false 并落盘。
     *
     * @return 强制绑定最终是否处于开启状态
     */
    fun ensureForceBindAvailable(): Boolean {
        if (!configManager.isForceBindEnabled()) return false
        if (QClient.getStarter() != null) return true

        log_error("QQ 机器人未连接，无法完成强制绑定的验证码流程，已自动关闭 binding.force-bind")
        config.set("binding.force-bind", false)
        saveConfig()
        return false
    }

    override fun getCommandBlacklist(): List<String> = configManager.commandBlacklist()

    /**
     * 命令表（`CraftServer#getCommandMap()`）不在 Bukkit API 里，只能反射取一次。
     *
     * 取不到就返回 null，黑名单退化为纯文本匹配；取到后可以用它把 `/pl` 这类别名还原成主名，
     * 否则黑名单里写 `plugins`、实际执行 `pl` 就能绕过去。
     */
    private val commandMap: CommandMap? by lazy {
        runCatching {
            server.javaClass.methods
                .firstOrNull { it.name == "getCommandMap" && it.parameterCount == 0 }
                ?.invoke(server) as? CommandMap
        }.getOrNull()
    }

    override fun resolveCommandName(command: String): String? = runCatching {
        val name = command.trim().trimStart('/').substringAfterLast(':').trim()
        if (name.isEmpty()) return@runCatching null
        commandMap?.getCommand(name)?.name?.lowercase()
    }.getOrNull()

    override fun getWebUiPort(): Int = config.getInt("webui-port", 5678)

    override fun getWebUiBindAddress(): String =
        config.getString("webui-bind-address", "127.0.0.1")?.takeIf(String::isNotBlank) ?: "127.0.0.1"

    override fun getWebUiConfigValues(): Map<String, Any?> =
        config.getValues(true).filterValues { it !is org.bukkit.configuration.ConfigurationSection }.toMutableMap().apply {
            put("command-panel.show-admin-commands", configManager.showAdminCommandsInMenu())
            put("bot.auto-add-groups", configManager.isAutoAddGroupsEnabled())
            put("update-check.enabled", configManager.isUpdateCheckEnabled())
            put("update-check.url", configManager.updateCheckUrls())
            put("placeholder-api.enabled", configManager.isPlaceholderApiEnabled())
            put("agent.hide-fetch-results", configManager.isAgentFetchResultHidden())
            BaseCommand.allCommands().distinctBy { it.command }.forEach { command ->
                val name = command.command
                if (!containsKey("commands.$name") && !containsKey("commands.$name.enable")) {
                    put("commands.$name.enable", true)
                }
                if (!containsKey("commands.$name.pushMenu")) {
                    put("commands.$name.pushMenu", true)
                }
                if (!containsKey("commands.$name.priority")) {
                    put("commands.$name.priority", if (name == "agent") 0 else 100)
                }
            }
        }

    override fun applyWebUiConfigChanges(changes: JSONObject): Boolean {
        if (stopping) return false
        val snapshot = changes.entries.associate { it.key to convertJsonValue(it.value) }
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        return try {
            reloadExecutor.execute {
                try {
                    configManager.updateFile(snapshot)
                    requestConfigReload(false)
                    result.complete(true)
                } catch (error: Exception) {
                    log_error("WebUI 保存配置失败，原配置保留：${error.message}")
                    result.complete(false)
                }
            }
            // A Minecraft caller receives acceptance; HTTP/QR callers can await durable persistence off-thread.
            if (FoliaScheduler.isTickThread()) true else result.get(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (error: Exception) {
            log_error("无法提交配置后台保存：${error.message}")
            false
        }
    }

    override fun getWebUiGroupConfigValues(groupId: String): Map<String, Any?> {
        val global = getWebUiConfigValues().toMutableMap()
        // 在全局值基础上叠加该群的有效命令/面板配置
        val gid = groupId.trim()
        global["__groupId"] = gid
        global["__groupHasOverrides"] = GroupCommandConfigStore.hasOverrides(gid)
        global["command-panel.show-admin-commands"] = showAdminCommandsInMenu(gid)
        val cmdList = getCommandList(gid)
        val menuList = getCommandMenuList(gid)
        val priorities = getCommandMenuPriorities(gid)
        val names = (cmdList.keys + menuList.keys + priorities.keys +
            BaseCommand.allCommands().map { it.command }).distinct()
        names.forEach { name ->
            global["commands.$name.enable"] = cmdList[name] ?: true
            global["commands.$name.pushMenu"] = menuList[name] ?: (name !in COMMANDS_HIDDEN_FROM_MENU_WEBUI)
            global["commands.$name.priority"] = priorities[name] ?: if (name == "agent") 0 else 100
            // 兼容 boolean-map/旧读取形态
            global["commands.$name"] = global["commands.$name.enable"]
        }
        return global
    }

    override fun applyWebUiGroupConfigChanges(groupId: String, changes: JSONObject): Boolean {
        val gid = groupId.trim()
        if (gid.isEmpty()) return false
        return try {
            val filtered = linkedMapOf<String, Any?>()
            changes.forEach { (path, value) ->
                if (GroupCommandConfigStore.isOverridable(path)) {
                    filtered[path] = convertJsonValue(value)
                }
            }
            if (!GroupCommandConfigStore.applyChanges(gid, filtered)) return false
            GroupCommandConfigStore.clearCache(gid)
            QClient.syncGroupPanels()
            true
        } catch (error: Exception) {
            log_error("保存群配置失败 ($groupId): ${error.message}")
            false
        }
    }

    override fun resetWebUiGroupConfig(groupId: String): Boolean {
        val gid = groupId.trim()
        if (gid.isEmpty()) return false
        val ok = GroupCommandConfigStore.clear(gid)
        if (ok) {
            GroupCommandConfigStore.syncFolders(configManager.groupOpenIds())
            QClient.syncGroupPanels()
        }
        return ok
    }


    /** 将 fastjson 值转换为 Bukkit 配置可接受的 Java 类型。 */
    private fun convertJsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.entries.associate { (k, v) -> k to convertJsonValue(v) }
        is JSONArray -> value.map { convertJsonValue(it) }
        else -> value
    }

    override fun getServerPluginList(): List<String> =
        server.pluginManager.plugins.map { it.name }.sorted()

    override fun getServerCommandHelp(pluginName: String?, commandName: String?): String {
        return when {
            commandName != null -> formatSingleCommand(commandName)
            pluginName != null -> formatPluginCommands(pluginName)
            else -> formatAllCommands()
        }
    }

    private fun formatSingleCommand(commandName: String): String {
        val cmd = findCommand(commandName)
            ?: return "未找到命令: /$commandName"
        return formatCommandDetail(cmd)
    }

    /** 查找命令：优先插件命令，其次命令表（兼容原版命令与别名）。 */
    private fun findCommand(commandName: String): Command? {
        server.getPluginCommand(commandName)?.let { return it }
        val commandMap = resolveCommandMapObject() ?: return null
        try {
            (commandMap as? CommandMap)?.getCommand(commandName)?.let { return it }
        } catch (_: Exception) {
        }
        return readKnownCommands(commandMap)?.let { known ->
            known[commandName.lowercase()]
                ?: known.values.firstOrNull { it.name.equals(commandName, true) }
        }
    }

    /** 统一格式化命令详情，兼容插件命令与原版（默认）命令。 */
    private fun formatCommandDetail(cmd: Command): String = buildString {
        appendLine("命令：/${cmd.name}")
        cmd.aliases.takeIf { it.isNotEmpty() }?.let { appendLine("别名：${it.joinToString(", ")}") }
        if (!cmd.description.isNullOrBlank()) appendLine("描述：${cmd.description}")
        val usage = cmd.usage?.trim()
        if (!usage.isNullOrBlank() && usage != "/<command>") appendLine("用法：$usage")
        cmd.permission?.takeIf { it.isNotBlank() }?.let { appendLine("权限：$it") }
        val pluginCommand = cmd as? PluginCommand
        if (pluginCommand != null) {
            pluginCommand.plugin?.let { appendLine("所属插件：${it.name}") }
        } else {
            appendLine("类型：原版命令（服务器版本：${server.version}）")
            appendLine("提示：原版命令的具体参数与语法请按服务器版本使用，可在游戏内执行 /help <命令> 查看。")
        }
    }.trimEnd()

    private fun formatPluginCommands(pluginName: String): String {
        val plugin = server.pluginManager.getPlugin(pluginName)
            ?: return "未找到插件：$pluginName（可用插件：${getServerPluginList().joinToString(", ")}）"

        val commands = plugin.description.commands
        if (commands.isEmpty()) return "插件 ${plugin.name} 没有注册命令。"
        return buildString {
            appendLine("插件 ${plugin.name} 注册的命令：")
            commands.toSortedMap().forEach { (label, meta) ->
                val desc = meta["description"]?.toString()?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                appendLine("/$label$desc")
            }
        }.trimEnd()
    }

    private fun formatAllCommands(): String {
        val commandMap = resolveCommandMapObject()
        val known = commandMap?.let { readKnownCommands(it) }
        val perPlugin = sortedMapOf<String, MutableList<String>>()

        if (known != null) {
            val seen = mutableSetOf<String>()
            known.values.forEach { cmd ->
                val label = cmd.name ?: return@forEach
                if (!seen.add(label)) return@forEach
                val owner = (cmd as? PluginCommand)?.plugin?.name ?: "原版命令"
                perPlugin.getOrPut(owner) { mutableListOf() }.add(label)
            }
        } else {
            server.pluginManager.plugins.forEach { plugin ->
                val labels = plugin.description.commands.keys
                if (labels.isNotEmpty()) {
                    perPlugin.getOrPut(plugin.name) { mutableListOf() }.addAll(labels)
                }
            }
        }

        if (perPlugin.isEmpty()) return "服务器没有可查询的命令。"
        return buildString {
            appendLine("服务器命令概览：")
            perPlugin.forEach { (owner, labels) ->
                appendLine("- $owner：${labels.sorted().joinToString(", ")}")
            }
            if (known == null) appendLine("（仅列出插件命令，原版命令表访问失败）")
        }.trimEnd()
    }

    /** 从命令表对象中读取全部命令映射，兼容不同版本字段名。 */
    private fun readKnownCommands(commandMap: Any): Map<String, Command>? {
        for (fieldName in listOf("knownCommands", "commandMap")) {
            try {
                val field = commandMap.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val map = field.get(commandMap) as? Map<String, Command>
                if (map != null) return map
            } catch (error: Exception) {
                log_debug("Agent 读取 $fieldName 字段失败: ${error.message}")
            }
        }
        try {
            val method = commandMap.javaClass.getMethod("getKnownCommands")
            @Suppress("UNCHECKED_CAST")
            val map = method.invoke(commandMap) as? Map<String, Command>
            if (map != null) return map
        } catch (error: Exception) {
            log_debug("Agent 调用 getKnownCommands 失败: ${error.message}")
        }
        return null
    }

    private fun resolveCommandMapObject(): Any? {
        // 1) 直接调用 getCommandMap()
        try {
            val method = server.javaClass.getMethod("getCommandMap")
            return method.invoke(server) ?: run {
                log_debug("Agent getCommandMap() 返回 null")
                null
            }
        } catch (error: Exception) {
            log_debug("Agent 反射 getCommandMap 失败: ${error.message}")
        }
        // 2) 遍历方法，找返回 CommandMap 的无参方法
        try {
            for (method in server.javaClass.methods) {
                if (method.parameterCount == 0 && CommandMap::class.java.isAssignableFrom(method.returnType)) {
                    val result = method.invoke(server)
                    if (result != null) return result
                }
            }
        } catch (error: Exception) {
            log_debug("Agent 遍历命令表方法失败: ${error.message}")
        }
        log_warning("Agent 无法访问服务器命令表，AI 命令帮助功能将不可用")
        return null
    }

    override fun getServerLogs(lines: Int?, keyword: String?): String {
        val logFile = resolveLogFile() ?: return "无法定位服务端日志文件（logs/latest.log）"
        val allLines = try {
            logFile.readLines(Charsets.UTF_8)
        } catch (error: Throwable) {
            return "读取服务端日志失败：${error.message}"
        }

        val lineCount = (lines ?: 50).coerceIn(1, 500)
        val kw = keyword?.trim()?.takeIf(String::isNotEmpty)
        val context = 5

        val selected: List<String>
        val matchCount: Int?
        if (kw != null) {
            val indices = allLines.indices.filter { allLines[it].contains(kw, ignoreCase = true) }
            if (indices.isEmpty()) {
                return "未在服务端日志中找到包含「$kw」的内容。"
            }
            matchCount = indices.size
            val range = indices.first()..indices.last()
            val from = (range.first - context).coerceAtLeast(0)
            val to = (range.last + context).coerceAtMost(allLines.size - 1)
            selected = allLines.subList(from, to)
        } else {
            matchCount = null
            selected = allLines.takeLast(lineCount)
        }

        val content = selected.joinToString("\n").trimEnd()
        val tip = matchCount?.let { "\n\n(按关键词「$kw」过滤，共匹配 $it 处)" } ?: ""
        return content + tip
    }

    /** 定位服务端日志文件 logs/latest.log。 */
    private fun resolveLogFile(): File? {
        val candidates = listOfNotNull(
            server.getWorldContainer().resolve("logs").resolve("latest.log"),
            server.getWorldContainer().parentFile?.resolve("logs")?.resolve("latest.log"),
            File("logs/latest.log")
        )
        return candidates.firstOrNull { it.isFile }
    }

    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warning(msg)
    override fun log_error(msg: String) = logger.severe(msg)
}

private object BStatsReporter {
    private const val SERVICE_ID = 34268

    fun start(plugin: JavaPlugin) {
        Metrics(plugin, SERVICE_ID)
    }
}

/** 启动后延迟多久检查 QQ 是否连上（QQ 客户端为异步启动，留出连接时间）。 */
private const val FORCE_BIND_CHECK_DELAY_MS = 15_000L
