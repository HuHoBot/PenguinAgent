package agent.spigot.onlinelist

import com.alibaba.fastjson.JSON
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import javax.imageio.ImageIO

/** 可选多来源皮肤客户端；请求有界并可取消。 */
class SkinSources(
    private val sources: List<Map<String, String>> = defaults(),
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 5000
) {
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false
    private val privateHosts=sources.filter { it["allow-private"].equals("true",true) }.flatMap { row ->
        listOfNotNull(row["url"],row["root"]).mapNotNull { value -> runCatching { URL(value.replace("{name}","Player").replace("{uuid}","0")).host }.getOrNull() }
    }.toSet()

    fun close() {
        closed = true
        val active = connections.toList()
        connections.clear()
        if (active.isNotEmpty()) Thread({ active.forEach { runCatching { it.disconnect() } } }, "HuHoBot-SkinDisconnect").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        fun defaults(): List<Map<String, String>> = listOf(
            mapOf("type" to "csl", "url" to "https://littleskin.cn/csl/{name}.json", "root" to "https://littleskin.cn"),
            mapOf("type" to "csl", "url" to "https://skin.prinzeugen.net/csl/{name}.json", "root" to "https://skin.prinzeugen.net"),
            mapOf("type" to "mojang", "url" to "https://api.mojang.com/users/profiles/minecraft/{name}"),
            mapOf("type" to "ely", "url" to "https://skinsystem.ely.by/textures/{name}"),
            mapOf("type" to "ashcon", "url" to "https://api.ashcon.app/mojang/v2/user/{name}"),
            mapOf("type" to "crafatar", "url" to "https://crafatar.com/avatars/{uuid}?size=64&overlay=true"),
            mapOf("type" to "avatar", "url" to "https://mc-heads.net/avatar/{name}/64")
        )

        fun textureIsSlim(value: String?): Boolean = runCatching {
            if (value.isNullOrBlank()) return false
            val raw = if (value.startsWith("{")) value else String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
            val json = JSON.parseObject(raw)
            val skin = json.getJSONObject("textures")?.getJSONObject("SKIN") ?: json.getJSONObject("SKIN")
            skin?.getJSONObject("metadata")?.getString("model").equals("slim", true)
        }.getOrDefault(false)

        /** The inventory renderer has a 64px UV map; retain 64x32 legacy skins, reduce HD safely. */
        fun normaliseFullSkin(image: BufferedImage?): BufferedImage? {
            if (image == null || image.width !in 64..2048 || image.width % 64 != 0 ||
                (image.height != image.width && image.height * 2 != image.width)) return null
            if (image.width == 64) return image
            val result = BufferedImage(64, if (image.height == image.width) 64 else 32, BufferedImage.TYPE_INT_ARGB)
            result.createGraphics().also { g ->
                try {
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                    g.drawImage(image, 0, 0, result.width, result.height, null)
                } finally { g.dispose() }
            }
            return result
        }

        fun image(bytes: ByteArray?): BufferedImage? = runCatching {
            if (bytes == null) return null
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = stream
                    if (reader.getWidth(0) !in 8..2048 || reader.getHeight(0) !in 8..2048) return null
                    reader.read(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }

    /** 每次重定向都重新校验，拒绝内网/回环地址，避免远程皮肤配置变成 SSRF。 */
    fun fetchUrl(address: String, limit: Int = 4 * 1024 * 1024): ByteArray? {
        var current = address
        repeat(3) {
            if (closed || Thread.currentThread().isInterrupted) return null
            val url = runCatching { URL(current) }.getOrNull() ?: return null
            if (url.protocol !in listOf("http", "https") || url.userInfo != null) return null
            val ips = runCatching { InetAddress.getAllByName(url.host) }.getOrNull() ?: return null
            if (url.host !in privateHosts && ips.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isMulticastAddress }) return null

            val connection = url.openConnection() as HttpURLConnection
            connections.add(connection)
            try {
                if (closed) return null
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs.coerceIn(250, 15000)
                connection.readTimeout = readTimeoutMs.coerceIn(250, 15000)
                connection.setRequestProperty("User-Agent", "PenguinAgent/1.23")
                val status = connection.responseCode
                if (status in 300..399) {
                    current = URL(url, connection.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (status !in 200..299 || connection.contentLengthLong > limit) return null
                return connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        if (closed || Thread.currentThread().isInterrupted) return null
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > limit) return null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (_: Exception) {
                return null
            } finally {
                connections.remove(connection)
                connection.disconnect()
            }
        }
        return null
    }

    data class FullSkin(val image: BufferedImage, val slim: Boolean, val address: String)
    private data class Located(val image: BufferedImage, val avatar: Boolean, val slim: Boolean, val address: String)

    /** Online-list compatibility API: second value indicates an avatar-only result. */
    fun find(player: OnlineListPlayer, fetcher: (String) -> ByteArray? = { fetchUrl(it) }): Pair<BufferedImage, Boolean>? =
        locate(player, false, fetcher)?.let { it.image to it.avatar }

    /** Body previews must skip avatar endpoints instead of painting a face over a whole player. */
    fun findFullSkin(player: OnlineListPlayer, fetcher: (String) -> ByteArray? = { fetchUrl(it) }): FullSkin? =
        locate(player, true, fetcher)?.let { FullSkin(it.image, it.slim, it.address) }

    private fun locate(player: OnlineListPlayer, fullOnly: Boolean, fetcher: (String) -> ByteArray?): Located? {
        val name = URLEncoder.encode(player.name, "UTF-8")
        val uuid = player.uuid.replace("-", "")
        for (source in sources) {
            if (closed || Thread.currentThread().isInterrupted) return null
            val endpoint = source["url"]?.replace("{name}", name)?.replace("{uuid}", uuid) ?: continue
            val type = source["type"] ?: "skin"
            val directAvatar = type in listOf("avatar", "crafatar")
            if (fullOnly && directAvatar) continue
            var slim = source["slim"].equals("true", true)
            val body = fetcher(endpoint) ?: continue
            val texture = when (type) {
                "avatar", "skin" -> null
                "csl" -> runCatching {
                    val skins = JSON.parseObject(String(body, Charsets.UTF_8)).getJSONObject("skins")
                    val classic = skins?.getString("default")?.takeIf { it.isNotBlank() }
                    val hash = classic ?: skins?.getString("slim")
                    slim = classic == null && !skins?.getString("slim").isNullOrBlank()
                    when {
                        hash.isNullOrBlank() -> null
                        hash.startsWith("https://") -> hash
                        hash.matches(Regex("[0-9a-fA-F]{32,64}")) ->
                            (source["root"] ?: endpoint.substringBefore("/csl/")).trimEnd('/') + "/textures/" + hash
                        else -> null
                    }
                }.getOrNull()
                "yggdrasil","profile" -> runCatching {
                    val profile=JSON.parseObject(String(body,Charsets.UTF_8))
                    val properties=profile.getJSONArray("properties")
                    if(properties!=null)(0 until properties.size).firstNotNullOfOrNull { i ->
                        val p=properties.getJSONObject(i)
                        if(p.getString("name")=="textures") {
                            slim = textureIsSlim(p.getString("value"))
                            SkinResolver.textureUrl(p.getString("value"))
                        } else null
                    }else {
                        slim = textureIsSlim(profile.toJSONString())
                        SkinResolver.textureUrl(profile.toJSONString())
                    }
                }.getOrNull()
                "ashcon" -> runCatching {
                    val json=JSON.parseObject(String(body,Charsets.UTF_8))
                    slim = json.getJSONObject("textures")?.getBooleanValue("slim") == true
                    json.getJSONObject("textures")?.getJSONObject("skin")?.getString("url")
                        ?: json.getJSONObject("skin")?.getString("url")
                }.getOrNull()
                "ely", "uniskin" -> runCatching {
                    val json = JSON.parseObject(String(body, Charsets.UTF_8))
                    slim = textureIsSlim(json.toJSONString())
                    json.getJSONObject("SKIN")?.getString("url")
                        ?: json.getJSONObject("textures")?.getJSONObject("SKIN")?.getString("url")
                }.getOrNull()
                "mojang" -> runCatching {
                    val id = JSON.parseObject(String(body, Charsets.UTF_8)).getString("id")
                    if (id.isNullOrBlank() || !id.matches(Regex("[0-9a-fA-F]{32}"))) return@runCatching null
                    val profile = fetcher("https://sessionserver.mojang.com/session/minecraft/profile/$id") ?: return@runCatching null
                    val properties = JSON.parseObject(String(profile, Charsets.UTF_8)).getJSONArray("properties") ?: return@runCatching null
                    (0 until properties.size).firstNotNullOfOrNull { index ->
                        val property = properties.getJSONObject(index)
                        if (property.getString("name") == "textures") {
                            slim = textureIsSlim(property.getString("value"))
                            SkinResolver.textureUrl(property.getString("value"))
                        } else null
                    }
                }.getOrNull()
                else -> null
            }
            val bytes = if (directAvatar || type == "skin") body else texture?.let(fetcher)
            val decoded = image(bytes) ?: continue
            val bitmap = if (fullOnly) normaliseFullSkin(decoded) ?: continue else decoded
            return Located(bitmap, directAvatar, slim, texture ?: endpoint)
        }
        return null
    }
}
