package agent.spigot.onlinelist

import agent.onlinelist.OnlineListHandler
import agent.onlinelist.OnlineListImageReply
import agent.spigot.HuHoBotSpigot
import agent.spigot.manager.ConfigManager
import org.bukkit.Bukkit
import java.util.concurrent.Callable
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在线列表渲染与发送管线。
 *
 * 把 `查在线` 命令的图片逻辑从 MOTD 外部 API 改为本地渲染：
 * 捕获玩家快照 → 下载皮肤头像 → 渲染 PNG → 通过 [reply] 回调发回 QQ。
 *
 * 在 [agent.onlinelist.OnlineListBridge] 中注册后，
 * `查在线` 命令即可走本地渲染；`online-list.enabled=false` 时自动回退到 MOTD 图片。
 */
class OnlineListManager(
    private val plugin: HuHoBotSpigot,
    private val config: ConfigManager
) : OnlineListHandler {
    override fun handle(groupOpenId: String, requestedPage: Int, reply: OnlineListImageReply): Boolean =
        handleQuery(groupOpenId, requestedPage, reply)
    private val inFlightGroups = ConcurrentHashMap.newKeySet<String>()
    private val lastRequestAt = ConcurrentHashMap<String, Long>()

    private lateinit var renderExecutor: java.util.concurrent.ExecutorService
    private lateinit var skinExecutor: java.util.concurrent.ExecutorService
    private var avatarCache: AvatarCache? = null
    private var renderer: OnlineListRenderer? = null
    private var statusThread: Thread? = null
    private var historyEvents: HistoryEvents? = null
    private val playerRows = ConcurrentHashMap<java.util.UUID, OnlineListPlayer>()
    private var sampler: agent.tools.Cancelable? = null
    @Volatile private var stopped = false
    private fun refreshPlayers(): java.util.concurrent.CompletableFuture<Void> {
        val online = plugin.server.onlinePlayers.toList()
        val present = online.map { it.uniqueId }.toSet()
        playerRows.keys.retainAll(present)
        val futures = online.map { player -> agent.spigot.manager.FoliaScheduler.entityFuture(plugin, player) {
            if (!stopped && player.isOnline) playerRows[player.uniqueId] = capturePlayer(player)
        } }
        return java.util.concurrent.CompletableFuture.allOf(*futures.toTypedArray())
    }

    fun start() {
        stopped = false
        OnlineHistoryStore.configureAsync(plugin.getDataDirectory())
        val threads = config.onlineListSkinDownloadThreads().coerceIn(1, 12)
        renderExecutor = Executors.newSingleThreadExecutor(namedThreads("HuHoBotOnlineList-Render"))
        skinExecutor = Executors.newFixedThreadPool(threads, namedThreads("HuHoBotOnlineList-Skin"))
        avatarCache = AvatarCache(
            downloadExecutor = skinExecutor,
            connectTimeoutMs = config.onlineListSkinTimeoutMs(),
            readTimeoutMs = config.onlineListSkinTimeoutMs(),
            cacheCapacity = config.onlineListSkinCacheSize(),
            // Offline servers have no authoritative textures; sites would only guess.
            enabled = SkinPolicy.remoteLookupsEnabled(config.onlineListSkinEnabled(), plugin.server.onlineMode, config.offlineDefaultSkins()),
            sources = config.onlineListSkinSources()
        )
        try {
            renderer = OnlineListRenderer(
                avatarCache = avatarCache!!,
                columns = config.onlineListRenderColumns(),
                configuredFont = config.onlineListRenderFontFamily(),
                footerText = config.onlineListRenderFooterText(),
                protocolSummary = plugin.getProtocolSummary(),
                maxRenderPlayers = config.onlineListMaxRenderPlayers(),
                customLines = config.onlineListCustomLines(),
                title = config.onlineListTitle()
            )
        } catch (error: Throwable) {
            // 底图资源缺失/损坏时不中断插件启动，仅关闭本地渲染能力
            plugin.log_warning("在线列表渲染器初始化失败，查在线将回退到 MOTD 图片: ${error.message}")
            return
        }
        historyEvents = HistoryEvents(plugin,
            { p -> agent.spigot.manager.FoliaScheduler.forEntity(plugin,p,Runnable { if(!stopped)playerRows[p.uniqueId]=capturePlayer(p) }) },
            { p -> playerRows.remove(p.uniqueId) }
        ).also { plugin.server.pluginManager.registerEvents(it,plugin) }
        refreshPlayers()
        sampler = agent.spigot.manager.FoliaScheduler.runTimer(plugin, 20, 40, Runnable { refreshPlayers() })
        OnlineHistoryStore.record(plugin.server.onlinePlayers.size)
        plugin.log_info("在线列表渲染器已就绪（亮色 · 玩家优先 · 侧栏）")
        // 后台刷新本服状态（Server List Ping → 真实 MOTD / 图标），30 秒一次
        statusThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching { SelfStatusPing.refresh(plugin.server.port) }
                try {
                    Thread.sleep(30_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "HuHoBot-StatusPing").apply { isDaemon = true; start() }
    }

    /** 重载时只发出取消信号，不等待网络/图像线程，以免把服务器 tick 卡在 join 上。 */
    fun stop() {
        stopped = true
        sampler?.cancel(); sampler = null
        playerRows.clear()
        statusThread?.interrupt()
        statusThread = null
        SelfStatusPing.cancelRefresh()
        historyEvents?.let { org.bukkit.event.HandlerList.unregisterAll(it) }; historyEvents = null
        avatarCache?.close(); avatarCache = null
        if (::renderExecutor.isInitialized) renderExecutor.shutdownNow()
        if (::skinExecutor.isInitialized) skinExecutor.shutdownNow()
        inFlightGroups.clear()
        lastRequestAt.clear()
    }

    /**
     * 查在线命令的图片回复入口。
     *
     * @param groupOpenId 请求群
     * @param requestedPage 页码（1-based）
     * @param reply 图片回复回调，[OnlineListImageReply] 封装了事件上下文
     * @return true 表示请求已受理；false 表示在线列表渲染未启用
     */
    fun handleQuery(
        groupOpenId: String,
        requestedPage: Int,
        reply: OnlineListImageReply
    ): Boolean {
        if (!config.onlineListEnabled()) return false

        val complete = captureSnapshotAsync() ?: run {
            reply.failed("无法获取在线玩家快照")
            return true
        }
        val totalPlayers = complete.totalOnlinePlayers
        val pageSize = config.onlineListPageSize()
        val pages = OnlineListPages.totalPages(totalPlayers, pageSize)
        if (requestedPage < 1 || requestedPage > pages) {
            reply.outOfRange(requestedPage, pages)
            return true
        }

        val now = System.currentTimeMillis()
        val cooldownMs = config.onlineListCooldownSeconds().coerceAtLeast(0) * 1000L
        val previous = lastRequestAt[groupOpenId]
        if (previous != null && now - previous < cooldownMs) {
            reply.busy()
            return true
        }
        if (!inFlightGroups.add(groupOpenId)) {
            reply.busy()
            return true
        }
        lastRequestAt[groupOpenId] = now

        val snapshot = OnlineListPages.page(complete, requestedPage, pageSize)
        val renderer = this.renderer
        if (renderer == null) {
            // 渲染器未就绪：放弃本次请求，让桥接返回 false，命令回退到 MOTD 图片
            inFlightGroups.remove(groupOpenId)
            return false
        }

        renderExecutor.execute {
            try {
                val png = renderer.render(snapshot)
                if (stopped) return@execute
                if (png.size > config.onlineListImageMaxBytes()) {
                    reply.failed("图片超出大小限制: ${png.size} bytes")
                    return@execute
                }
                reply.image(png)
                // 超出单图上限：图片只画前 max-render-players 位，其余用 Markdown 文字补全
                val maxRender = config.onlineListMaxRenderPlayers()
                if (snapshot.players.size > maxRender) {
                    val listed = snapshot.players.joinToString("\n") { "- ${it.name}" }
                    reply.text(
                        "**在线玩家 ${snapshot.totalOnlinePlayers} / ${snapshot.maxPlayers}**\n" +
                            "$listed\n\n（图中仅显示前 $maxRender 位）"
                    )
                }
            } catch (error: Throwable) {
                plugin.log_warning("在线列表渲染失败: ${error.message}")
                reply.failed("在线列表渲染失败: ${error.message}")
            } finally {
                inFlightGroups.remove(groupOpenId)
            }
        }
        return true
    }

    /**
     * 在主线程捕获在线玩家快照；不在主线程时阻塞等待主线程执行（最多 5 秒）。
     * 获取失败时返回 null。
     */
    fun captureSnapshotAsync(): ServerSnapshot? = try {
        if (agent.spigot.manager.FoliaScheduler.isFolia()) {
            val refreshing = refreshPlayers()
            if (!agent.spigot.manager.FoliaScheduler.isTickThread()) refreshing.get(5,TimeUnit.SECONDS)
            captureSnapshot()
        } else if (Bukkit.isPrimaryThread()) captureSnapshot()
        else agent.spigot.manager.FoliaScheduler.globalFuture(plugin) { captureSnapshot() }.get(5,TimeUnit.SECONDS)
    } catch (error: Throwable) { plugin.log_warning("在线列表快照获取失败: ${error.message}"); null }

    private fun readTps(): Double? = runCatching {
        val method = plugin.server.javaClass.methods.firstOrNull { it.name == "getTPS" } ?: return null
        val values = method.invoke(plugin.server) as? DoubleArray ?: return null
        values.firstOrNull()
    }.getOrNull()

    private fun readMspt(): Double? = runCatching {
        val method = plugin.server.javaClass.methods.firstOrNull {
            it.name == "getAverageTickTime" || it.name == "getAverageTickTimeNanos"
        } ?: return null
        val value = (method.invoke(plugin.server) as? Number)?.toDouble() ?: return null
        if (method.name.endsWith("Nanos")) value / 1_000_000.0 else value
    }.getOrNull()

    /** 在主线程捕获在线玩家快照。 */
    fun captureSnapshot(): ServerSnapshot {
        val players = if (agent.spigot.manager.FoliaScheduler.isFolia()) {
            plugin.server.onlinePlayers.forEach { p -> if (agent.spigot.manager.FoliaScheduler.owns(p)) playerRows[p.uniqueId]=capturePlayer(p) }
            val online = plugin.server.onlinePlayers.map { it.uniqueId }.toSet()
            playerRows.filterKeys { it in online }.values.toList()
        } else plugin.server.onlinePlayers.map(::capturePlayer)
        val sorted = OnlineListPages.sorted(players)
        OnlineHistoryStore.record(sorted.size)
        val runtime = Runtime.getRuntime()
        // 地址：优先 online-list.display.address（纯域名不带端口），其次 motd.server-ip；
        // 两者都未配置时不显示（不做自动探测，避免暴露公网 IP）。
        val address = config.onlineListAddress()
        return ServerSnapshot(
            serverName = config.serverName(),
            maxPlayers = plugin.server.maxPlayers,
            totalOnlinePlayers = sorted.size,
            players = sorted,
            motd = resolveMotd(),
            icon = loadServerIcon(),
            serverIp = address,
            serverPort = 0,
            brand = config.onlineListBrand().ifBlank { runCatching { plugin.server.name }.getOrDefault("") },
            coreVersion = runCatching { plugin.server.bukkitVersion }.getOrDefault(""),
            protocolVersion = SelfStatusPing.cached()?.protocol?.toString().orEmpty(),
            viaRange = runCatching { plugin.getProtocolSummary() }.getOrNull().orEmpty(),
            tps = readTps(),
            mspt = readMspt(),
            memoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L,
            memoryMaxMb = runtime.maxMemory() / 1_048_576L,
            ipAutoDetected = false,
            configuredTitle = config.onlineListTitle()
        )
    }

    private fun capturePlayer(player: org.bukkit.entity.Player): OnlineListPlayer {
        val adminPermission = config.onlineListAdminPermission()
        val adminGroups = config.onlineListAdminGroups()
        val showPrefix = config.onlineListShowPrefix()
        val showSuffix = config.onlineListShowSuffix()
            val administrator = player.isOp ||
                (adminPermission.isNotEmpty() && player.hasPermission(adminPermission)) ||
                (adminGroups.isNotEmpty() && LuckPermsBridge.inGroups(player.uniqueId, adminGroups))
            val metadata=NameMeta.read(player,config.onlineListPrefixTemplate(),config.onlineListSuffixTemplate())
            val prefix=if(showPrefix)metadata.prefix else ""
            val suffix=if(showSuffix)metadata.suffix else ""
            val skinUrl = try {
                SkinResolver.resolve(player)
            } catch (_: Exception) {
                null
            }
            // 拿不到皮肤纹理时（离线模式常见），改用头像 API 直链；
            // 完全失败时渲染器使用内置默认头像，永不留空。
            val headUrl = "" // 站点回退在受限的皮肤线程中按来源链完成
            return OnlineListPlayer(
                name = player.name,
                uuid = player.uniqueId.toString(),
                skinUrl = skinUrl,
                administrator = administrator,
                prefix = if (showPrefix) prefix else "",
                suffix = if (showSuffix) suffix else "",
                headUrl = headUrl,
                pingMs = (SkinResolver.call(player,"getPing") as? Number)?.toInt() ?: -1,
                displayName = if(metadata.explicit)prefix+player.name+suffix else NameFormat.resolve(player,prefix,suffix)
            )
        }
    /**
     * MOTD：优先 config.yml 的 motd.text；
     * 否则用 Server List Ping 拿到的真实 MOTD（能反映 MiniMOTD 等插件改写、
     * 彩色 hex 代码，与玩家在多人列表里看到的一致）；最后回退 server.properties。
     */
    private fun resolveMotd(): String {
        val configured = config.motdText()
        if (configured.isNotBlank()) return configured
        SelfStatusPing.cached()?.motd?.takeIf(String::isNotBlank)?.let { return it }
        return runCatching { plugin.server.motd }.getOrDefault("")
    }

    /**
     * 服务器图标：优先 Server List Ping 下发的 favicon（与多人列表一致，
     * 支持服务端插件/自定义图标），其次读服务端根目录 server-icon.png。
     */
    private fun loadServerIcon(): BufferedImage? = runCatching {
        SelfStatusPing.cached()?.icon?.let { return it }
        val candidates=listOf(
            java.io.File(plugin.server.worldContainer,"server-icon.png"),
            java.io.File(plugin.dataFolder.parentFile.parentFile,"server-icon.png"),
            java.io.File(plugin.server.worldContainer.parentFile ?: plugin.server.worldContainer,"server-icon.png")
        ).distinct()
        val file=candidates.firstOrNull { it.isFile } ?: return null
        javax.imageio.ImageIO.read(file)?.takeIf { it.width==64&&it.height==64 }
    }.getOrNull()

    private fun namedThreads(prefix: String): ThreadFactory {
        val index = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, prefix + "-" + index.incrementAndGet()).apply { isDaemon = true }
        }
    }
}
