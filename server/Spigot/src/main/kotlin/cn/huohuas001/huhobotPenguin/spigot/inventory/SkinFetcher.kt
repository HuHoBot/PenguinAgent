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
 * 皮肤获取：优先反射调用 SkinsRestorer API，回退 Mojang API。
 * 零编译时依赖，SkinsRestorer 未安装也能正常编译运行。
 */
object SkinFetcher {

    private const val USER_AGENT = "HuHoBot-Penguin/1.3.0"
    private const val CONNECT_TIMEOUT = 5000
    private const val READ_TIMEOUT = 10000
    private const val MAX_SKIN_BYTES = 1024 * 1024

    private val skinCache = ConcurrentHashMap<String, PlayerSkin?>()

    /** SkinsRestorer 可用性：null=未检测, true=可用, false=不可用 */
    private var srAvailable: Boolean? = null
    private var srApi: Any? = null

    fun fetchSkin(playerName: String, uuid: UUID? = null): PlayerSkin? {
        val cached = skinCache[playerName.lowercase()]
        if (cached != null) return cached

        val skin = try {
            fetchViaSkinsRestorer(playerName, uuid)
                ?: fetchViaMojang(playerName)
        } catch (_: Exception) {
            null
        }

        skinCache[playerName.lowercase()] = skin
        return skin
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

    // ==================== Mojang API ====================

    private fun fetchViaMojang(playerName: String): PlayerSkin? {
        val profileUrl = "https://api.mojang.com/users/profiles/minecraft/$playerName"
        val profileResponse = httpGet(profileUrl) ?: return null
        val uuid = com.alibaba.fastjson.JSONObject.parseObject(profileResponse)?.getString("id") ?: return null

        val sessionUrl = "https://sessionserver.mojang.com/session/minecraft/profile/$uuid"
        val sessionResponse = httpGet(sessionUrl) ?: return null
        val sessionObj = com.alibaba.fastjson.JSONObject.parseObject(sessionResponse)
        val properties = sessionObj?.getJSONArray("properties") ?: return null
        if (properties.size == 0) return null

        val value = properties.getJSONObject(0).getString("value") ?: return null
        val decoded = String(Base64.getDecoder().decode(value))
        val textureObj = com.alibaba.fastjson.JSONObject.parseObject(decoded)
        val textures = textureObj?.getJSONObject("textures") ?: return null
        val skinTexture = textures.getJSONObject("SKIN") ?: return null
        val textureUrl = skinTexture.getString("url") ?: return null
        val metadata = skinTexture.getJSONObject("metadata")
        val isSlim = metadata?.getString("model") == "slim"

        val image = downloadImage(URL(textureUrl)) ?: return null
        val cacheKey = PlayerSkin.safeKey(uuid)
        return PlayerSkin(image, cacheKey, "MOJANG", isSlim)
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

    private fun httpGet(urlStr: String): String? {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.connect()
            if (conn.responseCode != 200) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(value: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }
}
