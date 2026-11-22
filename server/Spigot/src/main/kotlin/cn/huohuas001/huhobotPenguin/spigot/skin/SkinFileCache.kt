package cn.huohuas001.huhobotPenguin.spigot.skin

import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 皮肤贴图文件缓存，供「查在线」与「背包查看」共用。
 *
 * 原行为：两处各自在内存里缓存皮肤，一旦重启、内存 LRU 淘汰或设备不同，
 * 每次查询都会重新去 textures.minecraft.net 下载同一张贴图。
 *
 * 现在：下载成功后把原始皮肤 PNG 写到 `plugins/HuHoBotPenguin/skin-cache/`，
 * 并记录抓取日期。同一天内的后续请求直接读本地文件，不再发网络请求；
 * 日期变化（第二天）后才允许重新下载一次，并覆盖旧文件。
 *
 * 下载失败但本地已有旧文件时，继续用旧文件顶一天（同时把日期记为今天），
 * 避免在网络故障时反复重试同一张贴图。
 */
object SkinFileCache {
    private const val CACHE_DIR_NAME = "skin-cache"

    @Volatile
    private var cacheDir: File? = null

    /** 按 key 加锁，避免同一张皮肤被并发重复下载。 */
    private val locks = ConcurrentHashMap<String, Any>()

    /** 在插件启动时调用；未配置时退化为「每次都下载」的旧行为。 */
    fun configure(dataFolder: File) {
        val dir = File(dataFolder, CACHE_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            System.err.println("[HuHoBotPenguin] 无法创建皮肤缓存目录: ${dir.absolutePath}")
            cacheDir = null
            return
        }
        cacheDir = dir
    }

    /**
     * 取皮肤贴图。命中当天的缓存时 [download] 不会被调用。
     *
     * @param key      稳定标识（贴图 URL、UUID 等），相同 key 共用同一份缓存文件
     * @param url      图片地址，仅在需要下载时使用（日志/异常定位）
     * @param download 实际下载逻辑，由调用方提供（保留各自的超时与校验规则）
     */
    fun get(key: String, url: String, download: () -> BufferedImage?): BufferedImage? {
        val dir = cacheDir ?: return runCatching(download).getOrNull()
        val hash = sha256(key)
        val image = File(dir, "$hash.png")
        val stamp = File(dir, "$hash.date")
        val today = LocalDate.now().toString()
        val lock = locks.computeIfAbsent(hash) { Any() }

        synchronized(lock) {
            if (stamp.isFile && image.isFile && readStamp(stamp) == today) {
                readImage(image)?.let { return it }
            }

            val fresh = runCatching(download).getOrNull()
            if (fresh != null) {
                writeImage(image, fresh)
                writeStamp(stamp, today)
                return fresh
            }

            // 刷新失败：用昨天的文件顶一天，今天不再重复请求同一张贴图。
            val stale = readImage(image)
            if (stale != null) writeStamp(stamp, today)
            return stale
        }
    }

    private fun readStamp(file: File): String? =
        runCatching { file.readText(StandardCharsets.UTF_8).trim() }.getOrNull()

    private fun writeStamp(file: File, value: String) {
        writeAtomic(file) { temp ->
            Files.write(temp.toPath(), value.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun writeImage(file: File, image: BufferedImage) {
        writeAtomic(file) { temp ->
            if (!ImageIO.write(image, "png", temp)) {
                error("没有可用的 PNG 写入器")
            }
        }
    }

    private fun writeAtomic(target: File, write: (File) -> Unit) {
        var temp: File? = null
        try {
            temp = Files.createTempFile(target.parentFile.toPath(), "skin-", ".tmp").toFile()
            write(temp)
            try {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            System.err.println("[HuHoBotPenguin] 写入皮肤缓存失败 ${target.name}: ${error.message}")
        } finally {
            temp?.delete()
        }
    }

    private fun readImage(file: File): BufferedImage? =
        runCatching { ImageIO.read(file)?.takeIf { it.width > 0 && it.height > 0 } }.getOrNull()

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }
}
