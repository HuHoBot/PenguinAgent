package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import cn.huohuas001.bot.onlinelist.OnlineListHandler
import cn.huohuas001.bot.onlinelist.OnlineListImageReply
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import org.bukkit.Bukkit
import java.util.concurrent.Callable
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
                footerText = config.onlineListRenderFooterText()
            )
        } catch (error: Throwable) {
            // 底图资源缺失/损坏时不中断插件启动，仅关闭本地渲染能力
            plugin.log_warning("在线列表渲染器初始化失败，查在线将回退到 MOTD 图片: ${error.message}")
            return
        }
        plugin.log_info("在线列表渲染器已就绪（雾蓝玻璃主题）")
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
            plugin.server.scheduler.callSyncMethod(plugin, Callable { captureSnapshot() })
                .get(5, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            plugin.log_warning("在线列表快照获取失败: ${error.message}")
            null
        }
    }

    /** 在主线程捕获在线玩家快照。 */
    fun captureSnapshot(): ServerSnapshot {
        val adminFirst = config.onlineListAdminFirst()
        val adminPermission = config.onlineListAdminPermission()
        val players = plugin.server.onlinePlayers.map { player ->
            val administrator = player.isOp ||
                (adminPermission.isNotEmpty() && player.hasPermission(adminPermission))
            OnlineListPlayer(
                name = player.name,
                uuid = player.uniqueId.toString(),
                skinUrl = try {
                    SkinResolver.resolve(player)
                } catch (_: Exception) {
                    null
                },
                administrator = administrator
            )
        }
        val sorted = OnlineListPages.sorted(players, adminFirst)
        return ServerSnapshot(
            serverName = config.serverName(),
            maxPlayers = plugin.server.maxPlayers,
            totalOnlinePlayers = sorted.size,
            players = sorted
        )
    }

    private fun namedThreads(prefix: String): ThreadFactory {
        val index = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, prefix + "-" + index.incrementAndGet()).apply { isDaemon = true }
        }
    }
}
