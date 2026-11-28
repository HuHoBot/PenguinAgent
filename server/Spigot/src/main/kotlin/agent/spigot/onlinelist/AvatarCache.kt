package agent.spigot.onlinelist

import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.imageio.ImageIO

/**
 * 有界 LRU 皮肤头像缓存；并发下载皮肤后裁出 Minecraft 头部与帽子层。
 */
class AvatarCache(
    private val downloadExecutor: Executor,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val cacheCapacity: Int,
    private val enabled: Boolean,
    /** 内置默认头像（Steve / Alex），网络或皮肤不可用时兜底。 */
    private val defaultHeads: List<BufferedImage> = emptyList(),
    sources: List<Map<String,String>> = SkinSources.defaults(),
    private val fetcher: ((String)->ByteArray?)? = null
) {
    private companion object {
        const val ICON_SIZE = 128
        const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
    }

    private val cache: MutableMap<String, BufferedImage> = Collections.synchronizedMap(
        object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, BufferedImage>): Boolean =
                size > cacheCapacity
        }
    )

    /**
     * 并发下载所有玩家的皮肤，返回 uuid → 头像图的映射。
     * 不成功的玩家（无皮肤 / 下载失败）不在结果中，渲染时走回退占位。
     */
    fun loadAll(players: List<OnlineListPlayer>): Map<String, BufferedImage> {
        val result = LinkedHashMap<String, BufferedImage>()
        val futures = mutableListOf<CompletableFuture<Pair<String, BufferedImage?>>>()
        for (player in players) {
            // 有 skinUrl（皮肤图）或 headUrl（头像直链）才下载；否则用内置默认头像
            if (enabled) {
                val p = player
                futures.add(
                    CompletableFuture.supplyAsync(
                        { p.uuid to load(p) },
                        downloadExecutor
                    )
                )
            }
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(7)
        for (future in futures) {
            try {
                val (uuid, avatar) = future.get(maxOf(1L, deadline-System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS)
                if (avatar != null) result[uuid] = avatar
            } catch (_: Throwable) {
                future.cancel(true) // 单条失败/超时跳过，不阻塞整张图片
            }
        }
        return result
    }

    private val provider = SkinSources(sources,connectTimeoutMs,readTimeoutMs)
    fun close(){provider.close()}
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<ByteArray?>>()
    private fun load(player: OnlineListPlayer): BufferedImage? {
        val key = player.uuid + ":" + player.skinUrl.orEmpty() + ":" + player.headUrl
        cache[key]?.let { return it }
        if (Thread.currentThread().isInterrupted || (failed[key] ?: 0L) > System.currentTimeMillis()) return null
        fun skin(url: String?): BufferedImage? {
            if (url.isNullOrBlank()) return null
            return download(url)?.takeIf { it.width >= 64 && it.width % 64 == 0 }?.let(::cropHead)
        }
        val exact = skin(player.skinUrl)
            ?: runCatching { skin(SkinResolver.pluginTexture(java.util.UUID.fromString(player.uuid),player.name)) }.getOrNull()
        val fromSites = if (exact == null) { if(fetcher!=null)provider.find(player,fetcher)else provider.find(player) } else null
        val result = exact ?: fromSites?.let { if (it.second) it.first else cropHead(it.first) }
            ?: player.headUrl.takeIf(String::isNotBlank)?.let(::download)
        if (result != null) cache[key] = result else failed[key] = System.currentTimeMillis() + 60000L
        if (failed.size > 1000) failed.entries.removeIf { it.value < System.currentTimeMillis() }
        return result
    }

    /** 内置默认头像（Steve/Alex 按 uuid 哈希选择），保证任何情况都有头像。 */
    fun defaultHead(player: OnlineListPlayer): BufferedImage {
        val heads = defaultHeads
        if (heads.isEmpty()) return BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB)
        val index = (player.uuid.hashCode().toLong() and 0x7fffffff).mod(heads.size.toLong()).toInt()
        return heads[index]
    }

    /** 某玩家最终展示的头像（永不为 null：失败时返回内置默认）。 */
    fun headFor(player: OnlineListPlayer, downloaded: Map<String, BufferedImage>): BufferedImage? =
        downloaded[player.uuid] ?: if (enabled) defaultHead(player) else defaultHead(player)

    private fun download(url: String): BufferedImage? {
        SkinDiskCache.get(url)?.let { SkinSources.image(it) }?.let { return it }
        val future = inflight.computeIfAbsent(url) {
            java.util.concurrent.CompletableFuture<ByteArray?>().also { target ->
                java.util.concurrent.CompletableFuture.runAsync({
                    try { target.complete(if (fetcher != null) fetcher.invoke(url) else provider.fetchUrl(url)) }
                    catch (error: Throwable) { target.completeExceptionally(error) }
                    finally { inflight.remove(url) }
                })
            }
        }
        val bytes = runCatching { future.get(8, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() ?: return null
        SkinDiskCache.put(url, bytes)
        return SkinSources.image(bytes)
    }

    /**
     * 裁出 Minecraft 皮肤的头部（含帽子层），输出 128×128 图标。
     */
    fun cropHead(skin: BufferedImage): BufferedImage {
        val icon = BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = icon.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            )
            graphics.drawImage(skin, 0, 0, ICON_SIZE, ICON_SIZE, 8 * (skin.width / 64), 8 * (skin.width / 64), 16 * (skin.width / 64), 16 * (skin.width / 64), null)
            if (skin.width>=64) {
                graphics.composite = AlphaComposite.SrcOver
                graphics.drawImage(skin, 0, 0, ICON_SIZE, ICON_SIZE, 40 * (skin.width / 64), 8 * (skin.width / 64), 48 * (skin.width / 64), 16 * (skin.width / 64), null)
            }
        } finally {
            graphics.dispose()
        }
        return icon
    }
}
