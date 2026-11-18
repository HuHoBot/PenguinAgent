package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import cn.huohuas001.bot.onlinelist.OnlineListHandler
import cn.huohuas001.bot.onlinelist.OnlineListImageReply
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import org.bukkit.Bukkit
import java.util.concurrent.Callable
import java.awt.image.BufferedImage
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
 * 在 [cn.huohuas001.bot.onlinelist.OnlineListBridge] 中注册后，
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

    fun start() {
        val threads = config.onlineListSkinDownloadThreads().coerceIn(1, 12)
        renderExecutor = Executors.newSingleThreadExecutor(namedThreads("HuHoBotOnlineList-Render"))
        skinExecutor = Executors.newFixedThreadPool(threads, namedThreads("HuHoBotOnlineList-Skin"))
        avatarCache = AvatarCache(
            downloadExecutor = skinExecutor,
            connectTimeoutMs = config.onlineListSkinConnectTimeoutMs(),
            readTimeoutMs = config.onlineListSkinReadTimeoutMs(),
            cacheCapacity = config.onlineListSkinCacheSize(),
            enabled = config.onlineListSkinEnabled()
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
                nameColor = config.onlineListNameColor(),
                themeName = config.onlineListTheme()
            )
        } catch (error: Throwable) {
            // 底图资源缺失/损坏时不中断插件启动，仅关闭本地渲染能力
            plugin.log_warning("在线列表渲染器初始化失败，查在线将回退到 MOTD 图片: ${error.message}")
            return
        }
        plugin.log_info("在线列表渲染器已就绪（深色科技风 · 趋势图 · 彩色 MOTD）")
        // 后台刷新本服状态（Server List Ping → 真实 MOTD / 图标），30 秒一次
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching { SelfStatusPing.refresh(plugin.server.port) }
                try {
                    Thread.sleep(30_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "HuHoBot-StatusPing").apply { isDaemon = true }.start()
    }

    fun stop() {
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
    fun captureSnapshotAsync(): ServerSnapshot? {
        if (Bukkit.isPrimaryThread()) return captureSnapshot()
        return try {
            val future = java.util.concurrent.CompletableFuture<ServerSnapshot?>()
            val scheduled = cn.huohuas001.huhobotPenguin.spigot.manager.FoliaScheduler.runNow(
                plugin,
                Runnable { runCatching { future.complete(captureSnapshot()) } }
            )
            if (scheduled != null) future.get(5, TimeUnit.SECONDS)
            else plugin.server.scheduler.callSyncMethod(plugin, Callable { captureSnapshot() })
                .get(5, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            plugin.log_warning("在线列表快照获取失败: ${error.message}")
            null
        }
    }

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
        val adminFirst = config.onlineListAdminFirst()
        val adminPermission = config.onlineListAdminPermission()
        val adminGroups = config.onlineListAdminGroups()
        val showPrefix = config.onlineListShowPrefix()
        val showSuffix = config.onlineListShowSuffix()
        val players = plugin.server.onlinePlayers.map { player ->
            val administrator = player.isOp ||
                (adminPermission.isNotEmpty() && player.hasPermission(adminPermission)) ||
                (adminGroups.isNotEmpty() && LuckPermsBridge.inGroups(player.uniqueId, adminGroups))
            val (prefix, suffix) = if (showPrefix || showSuffix) {
                LuckPermsBridge.prefixSuffix(player.uniqueId)
            } else {
                "" to ""
            }
            OnlineListPlayer(
                name = player.name,
                uuid = player.uniqueId.toString(),
                skinUrl = try {
                    SkinResolver.resolve(player)
                } catch (_: Exception) {
                    null
                },
                administrator = administrator,
                prefix = if (showPrefix) prefix else "",
                suffix = if (showSuffix) suffix else ""
            )
        }
        val sorted = OnlineListPages.sorted(players, adminFirst)
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
            brand = runCatching { plugin.server.name }.getOrDefault(""),
            coreVersion = runCatching { plugin.server.version }.getOrDefault(""),
            protocolVersion = runCatching { plugin.server.bukkitVersion }.getOrDefault(""),
            viaRange = runCatching { plugin.getProtocolSummary() }.getOrNull().orEmpty(),
            tps = readTps(),
            mspt = readMspt(),
            memoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L,
            memoryMaxMb = runtime.maxMemory() / 1_048_576L,
            ipAutoDetected = false
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
        val file = plugin.server.worldContainer.parentFile?.resolve("server-icon.png")
            ?: return null
        if (!file.isFile) return null
        javax.imageio.ImageIO.read(file)
    }.getOrNull()

    private fun namedThreads(prefix: String): ThreadFactory {
        val index = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, prefix + "-" + index.incrementAndGet()).apply { isDaemon = true }
        }
    }
}
