package cn.huohuas001.huhobotPenguin.spigot.inventory

import org.bukkit.Bukkit
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 从服务端已有的皮肤资料获取贴图；没有可用资料时由渲染器使用内置皮肤。
 */
object SkinFetcher {

    private const val USER_AGENT = "HuHoBot-Penguin/1.3.0"
    private const val CONNECT_TIMEOUT = 5000
    private const val READ_TIMEOUT = 10000
    private const val MAX_SKIN_BYTES = 1024 * 1024
    private const val FAILURE_CACHE_TTL_MS = 60_000L

    private val skinCache = ConcurrentHashMap<String, PlayerSkin>()
    private val failureCache = ConcurrentHashMap<String, Long>()

    /** SkinsRestorer 可用性：null=未检测, true=可用, false=不可用 */
    private var srAvailable: Boolean? = null
    private var srApi: Any? = null

    fun fetchSkin(
        playerName: String,
        uuid: UUID? = null,
        serverProfile: ServerSkinProfile? = null
    ): PlayerSkin? {
        val key = playerName.lowercase(Locale.ROOT) + "|" + (serverProfile?.textureUrl ?: "")
        val cached = skinCache[key]
        if (cached != null) return cached

        val failedAt = failureCache[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_CACHE_TTL_MS) return null

        val skin = try {
            serverProfile?.let { fetchFromServerProfile(it) }
                ?: fetchViaSkinsRestorer(playerName, uuid)
        } catch (_: Exception) {
            null
        }

        if (skin != null) {
            skinCache[key] = skin
            failureCache.remove(key)
        } else {
            failureCache[key] = System.currentTimeMillis()
        }
        return skin
    }

    private fun fetchFromServerProfile(profile: ServerSkinProfile): PlayerSkin? {
        val image = downloadImage(URL(profile.textureUrl)) ?: return null
        return PlayerSkin(image, PlayerSkin.safeKey(sha256(profile.textureUrl)), "SERVER_PROFILE", profile.slim)
    }

    // ==================== SkinsRestorer (纯反射) ====================

    private fun fetchViaSkinsRestorer(playerName: String, uuid: UUID?): PlayerSkin? {
        if (srAvailable == false) return null
        try {
            if (srApi == null) {
                // SkinsRestorerProvider.get()
                val providerClass = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider")
                val getMethod = providerClass.getMethod("get")
                srApi = getMethod.invoke(null)
                srAvailable = true
            }
            val api = srApi ?: return null

            // api.getPlayerStorage().getSkinForPlayer(uuid, name)
            val storageMethod = api.javaClass.getMethod("getPlayerStorage")
            val storage = storageMethod.invoke(api)

            val skinProperty = if (uuid != null) {
                val method = storage.javaClass.getMethod("getSkinForPlayer", UUID::class.java, String::class.java)
                val optional = method.invoke(storage, uuid, playerName) as? Optional<*> ?: return null
                optional.orElse(null)
            } else {
                val method = storage.javaClass.getMethod("getSkinForPlayer", String::class.java)
                val optional = method.invoke(storage, playerName) as? Optional<*> ?: return null
                optional.orElse(null)
            } ?: return null

            val propertyUtilsClass = Class.forName("net.skinsrestorer.api.PropertyUtils")
            val getTextureUrlMethod = propertyUtilsClass.getMethod("getSkinTextureUrl", (skinProperty as Any).javaClass)
            val textureUrl = getTextureUrlMethod.invoke(null, skinProperty) as? String ?: return null

            // 下载皮肤
            val url = URL(textureUrl)
            val image = downloadImage(url) ?: return null

            val isSlim = try {
                val getValueMethod = (skinProperty as Any).javaClass.getMethod("getValue")
                val value = getValueMethod.invoke(skinProperty) as? String
                if (value != null) {
                    val decoded = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
                    decoded.contains("\"model\"\\s*:\\s*\"slim\"".toRegex())
                } else false
            } catch (_: Exception) {
                false
            }

            val cacheKey = PlayerSkin.safeKey(
                if (uuid != null) uuid.toString().replace("-", "")
                else sha256(playerName)
            )
            return PlayerSkin(image, cacheKey, "SKINSRESTORER", isSlim)
        } catch (_: NoClassDefFoundError) {
            srAvailable = false
            return null
        } catch (_: ClassNotFoundException) {
            srAvailable = false
            return null
        } catch (_: Exception) {
            return null
        }
    }

    // ==================== 公共下载 ====================

    private fun downloadImage(url: URL): BufferedImage? {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.connect()
            if (conn.responseCode != 200) return null
            val input = conn.inputStream
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                total += read
                if (total > MAX_SKIN_BYTES) return null
                output.write(buffer, 0, read)
            }
            val image = ImageIO.read(ByteArrayInputStream(output.toByteArray())) ?: return null
            if (image.width != 64 || (image.height != 64 && image.height != 32)) return null
            return image
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(value: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }
}
