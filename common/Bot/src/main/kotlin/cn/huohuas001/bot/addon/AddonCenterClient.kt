package cn.huohuas001.bot.addon

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 附属插件中心（addon.txssb.cn）客户端：拉取插件列表与下载文件。
 *
 * 只做下载，不做热加载；下载后需重启服务器生效。
 */
object AddonCenterClient {
    const val CENTER_URL = "https://addon.txssb.cn"
    private const val API_BASE = "https://addon.txssb.cn/api.php"
    private const val TIMEOUT_MILLIS = 20_000
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** 只允许出现在列表中的服务端类型关键词（Paper / Spigot）。 */
    private val ALLOWED_PLATFORMS = listOf("spigot", "paper")

    data class AddonEntry(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val tags: List<String>,
        val serverType: String,
        val jarName: String,
        val jarSize: Long,
        val downloads: Long,
        val readme: String
    )

    data class DownloadedAddon(val fileName: String, val bytes: ByteArray)

    /** 拉取插件列表，只保留 Spigot / Paper 平台。 */
    fun listAddons(search: String? = null): List<AddonEntry> {
        val query = buildString {
            append("?action=list")
            if (!search.isNullOrBlank()) {
                append("&search=").append(URLEncoder.encode(search.trim(), "UTF-8"))
            }
        }
        val body = readText(API_BASE + query) ?: return emptyList()
        val plugins = JSON.parseObject(body)?.getJSONArray("plugins") ?: return emptyList()
        return plugins.mapNotNull { item ->
            val json = item as? JSONObject ?: return@mapNotNull null
            val entry = json.toEntry() ?: return@mapNotNull null
            if (!isAllowedPlatform(entry.serverType)) null else entry
        }
    }

    /** 获取单个插件详情（含 readme）。 */
    fun detail(id: String): AddonEntry? {
        val body = readText("$API_BASE?action=detail&id=" + URLEncoder.encode(id, "UTF-8")) ?: return null
        val json = JSON.parseObject(body) ?: return null
        return json.toEntry()
    }

    /** 下载插件文件，返回建议的文件名与内容。 */
    fun download(id: String): DownloadedAddon? {
        val encoded = URLEncoder.encode(id, "UTF-8")
        val connection = (URL("$API_BASE?action=fetch&id=$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("User-Agent", "PenguinAgent-WebUI")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val declared = connection.contentLengthLong
            if (declared > MAX_FILE_BYTES) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return null
            val name = connection.getHeaderField("Content-Disposition")
                ?.let { parseFileName(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "addon-$id.jar"
            DownloadedAddon(name, bytes)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun isAllowedPlatform(serverType: String): Boolean {
        val value = serverType.lowercase()
        return ALLOWED_PLATFORMS.any { value.contains(it) }
    }

    private fun JSONObject.toEntry(): AddonEntry? {
        val id = getString("id")?.takeIf { it.isNotBlank() } ?: return null
        val name = getString("name")?.takeIf { it.isNotBlank() } ?: return null
        val tags = getJSONArray("tags")?.map { it.toString() }?.filter { it.isNotBlank() } ?: emptyList()
        return AddonEntry(
            id = id,
            name = name,
            version = getString("version").orEmpty(),
            author = getString("author").orEmpty(),
            description = getString("description").orEmpty(),
            tags = tags,
            serverType = getString("server_type").orEmpty(),
            jarName = getString("jar_name").orEmpty(),
            jarSize = getLongValue("jar_size"),
            downloads = getLongValue("downloads"),
            readme = getString("readme").orEmpty()
        )
    }

    private fun parseFileName(contentDisposition: String): String {
        val encoded = Regex("filename\\*=UTF-8''([^;]+)").find(contentDisposition)?.groupValues?.get(1)
        if (encoded != null) return URLDecoder.decode(encoded, "UTF-8")
        return Regex("filename=\"?([^\";]+)\"?").find(contentDisposition)?.groupValues?.get(1).orEmpty()
    }

    private fun readText(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("User-Agent", "PenguinAgent-WebUI")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
