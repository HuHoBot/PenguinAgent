package cn.huohuas001.huhobotPenguin.spigot.onlinelist

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
    private val enabled: Boolean
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
            if (enabled && !player.skinUrl.isNullOrBlank()) {
                val p = player
                futures.add(
                    CompletableFuture.supplyAsync(
                        { p.uuid to load(p) },
                        downloadExecutor
                    )
                )
            }
        }
        for (future in futures) {
            try {
                val (uuid, avatar) = future.join()
                if (avatar != null) result[uuid] = avatar
            } catch (_: Throwable) {
                // 单条失败跳过
            }
        }
        return result
    }

    private fun load(player: OnlineListPlayer): BufferedImage? {
        val url = player.skinUrl ?: return null
        cache[url]?.let { return it }
        return try {
            val skin = download(url) ?: return null
            if (skin.width < 48 || skin.height < 16) return null
            val icon = cropHead(skin)
            cache[url] = icon
            icon
        } catch (_: Exception) {
            null
        }
    }

    private fun download(url: String): BufferedImage? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.useCaches = true
        connection.setRequestProperty("User-Agent", "HuHoBot/1.0")
        return try {
            val code = connection.responseCode
            if (code < 200 || code >= 300) return null
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            ImageIO.read(ByteArrayInputStream(bytes))
        } finally {
            connection.disconnect()
        }
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
            graphics.drawImage(skin, 0, 0, ICON_SIZE, ICON_SIZE, 8, 8, 16, 16, null)
            if (skin.height >= 64) {
                graphics.composite = AlphaComposite.SrcOver
                graphics.drawImage(skin, 0, 0, ICON_SIZE, ICON_SIZE, 40, 8, 48, 16, null)
            }
        } finally {
            graphics.dispose()
        }
        return icon
    }
}
