package agent.spigot.inventory

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

    private const val MAX_SKIN_BYTES = 1024 * 1024
    private const val FAILURE_CACHE_TTL_MS = 60_000L

    private data class Cached(val skin: PlayerSkin, val at: Long)
    private val skinCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, Cached>(16, 0.75f, true))
    private val failureCache = ConcurrentHashMap<String, Long>()
    @Volatile private var provider = agent.spigot.onlinelist.SkinSources()
    @Volatile private var networkEnabled = true
    @Volatile private var cacheCapacity = 200
    @Volatile private var configuration: List<Any>? = null
    private val epoch = java.util.concurrent.atomic.AtomicLong()

    /** Shares configured sources, SSRF checks and timeouts with online-list lookup; no network here. */
    @Synchronized fun configure(sources: List<Map<String, String>>, timeoutMs: Int, enabled: Boolean, capacity: Int) {
        val signature = listOf(sources, timeoutMs, enabled, capacity)
        if (configuration == signature) return
        val old = provider
        provider = agent.spigot.onlinelist.SkinSources(sources, timeoutMs, timeoutMs)
        networkEnabled = enabled
        cacheCapacity = capacity.coerceIn(8, 1024)
        configuration = signature
        epoch.incrementAndGet()
        skinCache.clear(); failureCache.clear()
        old.close()
    }

    @Synchronized fun close() {
        epoch.incrementAndGet(); provider.close(); skinCache.clear(); failureCache.clear(); configuration = null
    }

    /** SkinsRestorer 可用性：null=未检测, true=可用, false=不可用 */
    private var srAvailable: Boolean? = null
    private var srApi: Any? = null

    fun fetchSkin(
        playerName: String,
        uuid: UUID? = null,
        serverProfile: ServerSkinProfile? = null
    ): PlayerSkin? {
        if (!networkEnabled) return null
        val generation = epoch.get()
        val key = playerName.lowercase(Locale.ROOT) + "|" + uuid + "|" + (serverProfile?.textureUrl ?: "")
        val now = System.currentTimeMillis()
        skinCache[key]?.takeIf { now - it.at < 300_000L }?.let { return it.skin }
        if (failureCache[key]?.let { now - it < FAILURE_CACHE_TTL_MS } == true) return null

        val skin = try {
            serverProfile?.let { fetchFromServerProfile(it) }
                ?: uuid?.let { id ->
                    agent.spigot.onlinelist.SkinResolver.pluginTexture(id, playerName)?.let { address ->
                        downloadImage(URL(address))?.let { image -> PlayerSkin(image, sha256(address), "SERVER_PLUGIN", false) }
                    }
                }
                ?: fetchViaSkinsRestorer(playerName, uuid)
                ?: provider.findFullSkin(agent.spigot.onlinelist.OnlineListPlayer(
                    playerName, (uuid ?: UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).toByteArray(StandardCharsets.UTF_8))).toString(),
                    serverProfile?.textureUrl, false
                ))?.let { full -> PlayerSkin(full.image, sha256(full.address), "SHARED_SKIN_SOURCES", full.slim) }
        } catch (_: Exception) { null }

        if (generation != epoch.get()) return null
        if (skin != null) {
            // Content-derived keys also invalidate cached heads/models when a provider updates the same URL.
            val bytes = ByteArrayOutputStream().also { ImageIO.write(skin.image, "png", it) }.toByteArray()
            val contentKey = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val result = skin.copy(cacheKey = contentKey)
            synchronized(skinCache) {
                skinCache[key] = Cached(result, now)
                while (skinCache.size > cacheCapacity) skinCache.remove(skinCache.keys.first())
            }
            failureCache.remove(key)
            return result
        }
        failureCache[key] = now
        if (failureCache.size > 2048) {
            failureCache.entries.removeIf { now - it.value > FAILURE_CACHE_TTL_MS }
            if (failureCache.size > 2048) failureCache.clear()
        }
        return null
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
        val address = url.toString()
        agent.spigot.onlinelist.SkinDiskCache.get(address)?.let {
            agent.spigot.onlinelist.SkinSources.normaliseFullSkin(agent.spigot.onlinelist.SkinSources.image(it))?.let { image -> return image }
        }
        val bytes = provider.fetchUrl(address, MAX_SKIN_BYTES) ?: return null
        agent.spigot.onlinelist.SkinDiskCache.put(address, bytes)
        return agent.spigot.onlinelist.SkinSources.normaliseFullSkin(agent.spigot.onlinelist.SkinSources.image(bytes))
    }

    private fun sha256(value: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }
}
