package cn.huohuas001.bot.update

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 版本更新检查：启动时后台检查一次，/版本 命令可再次触发。
 *
 * 只认正式 Release（忽略 draft 与 Pre-Release），失败时仅记录状态，不影响插件运行。
 * 优先使用官网版本接口，失败再回退到 GitHub Releases（老旧 JDK 可能不信任 GitHub 根证书）。
 */
object UpdateChecker {
    private const val GITHUB_API_PATH = "https://api.github.com/repos/HuHoBot/PenguinAgent/releases"

    /** 国内网络下 GitHub 直连常被拦截，优先走 gh-proxy 代理。 */
    private val GITHUB_RELEASES_URLS = listOf(
        "https://gh-proxy.org/$GITHUB_API_PATH",
        GITHUB_API_PATH
    )
    private const val OFFICIAL_SITE = "https://huhobot.dpdns.org"
    private const val PROJECT_URL = "https://github.com/HuHoBot/PenguinAgent"
    private const val DOCS_URL = "https://docs.huhobot.dpdns.org/"
    private const val TIMEOUT_MILLIS = 15_000
    private const val CACHE_MILLIS = 10 * 60 * 1000L

    const val SITE_URL = OFFICIAL_SITE
    const val PROJECT = PROJECT_URL
    const val DOCS = DOCS_URL

    private var latestVersion: String? = null
    private var checkedAt: Long = 0L
    private var notifiedOutdated = false
    private var failureLogged = false
    private val checking = AtomicBoolean(false)

    /** 启动后调用：异步检查一次，发现新版时控制台提示一次。 */
    fun checkOnStartup(plugin: HuHoBot) {
        if (!plugin.isUpdateCheckEnabled()) return
        plugin.submitAsync {
            val state = check(plugin, force = true)
            if (state.outdated && !notifiedOutdated) {
                notifiedOutdated = true
                plugin.log_info("发现新版本 ${state.latest}，请前往官网 $OFFICIAL_SITE 下载更新")
            }
        }
    }

    /** 10 分钟内已检查过则直接返回缓存结果，否则返回 null。 */
    fun cachedState(plugin: HuHoBot): UpdateState? {
        val cached = latestVersion ?: return null
        if (System.currentTimeMillis() - checkedAt >= CACHE_MILLIS) return null
        return UpdateState(cached, outdated = isOutdated(plugin, cached), checked = true)
    }

    /** 执行一次检查；10 分钟内复用缓存，force=true 时忽略缓存。 */
    fun check(plugin: HuHoBot, force: Boolean = false): UpdateState {
        if (!plugin.isUpdateCheckEnabled()) return UpdateState.disabled()
        val now = System.currentTimeMillis()
        val cached = latestVersion
        if (!force && cached != null && now - checkedAt < CACHE_MILLIS) {
            return UpdateState(cached, outdated = isOutdated(plugin, cached), checked = true)
        }
        if (!checking.compareAndSet(false, true)) {
            return UpdateState(cached, outdated = cached?.let { isOutdated(plugin, it) } ?: false, checked = cached != null)
        }
        return try {
            val latest = fetchLatestRelease()
            if (latest == null) {
                UpdateState(cached, outdated = false, checked = false)
            } else {
                latestVersion = latest
                checkedAt = System.currentTimeMillis()
                failureLogged = false
                UpdateState(latest, outdated = isOutdated(plugin, latest), checked = true)
            }
        } finally {
            checking.set(false)
        }
    }

    private fun isOutdated(plugin: HuHoBot, latest: String): Boolean =
        compareVersion(latest, plugin.getPluginVersion()) > 0

    /** 依次尝试配置的数据源、gh-proxy 代理的 GitHub API、GitHub API 直连。 */
    private fun fetchLatestRelease(): String? {
        val sources = ArrayList<Pair<String, SourceKind>>()
        configuredSources().forEach { sources += it to SourceKind.AUTO }
        GITHUB_RELEASES_URLS.forEach { sources += it to SourceKind.GITHUB_LIST }

        val failures = ArrayList<String>()
        for ((url, kind) in sources) {
            val label = shortLabel(url)
            val body = try {
                readBody(url)
            } catch (error: Exception) {
                failures += "$label=${error.javaClass.simpleName}"
                continue
            }
            if (body == null) {
                failures += "$label=HTTP 失败"
                continue
            }
            val version = try {
                when (kind) {
                    SourceKind.GITHUB_LIST -> parseGithubReleases(body)
                    SourceKind.AUTO -> parseAuto(body)
                }
            } catch (error: Exception) {
                failures += "$label=解析失败"
                null
            }
            if (version != null) return version
            failures += "$label=无有效版本"
        }
        if (failures.isNotEmpty() && !failureLogged) {
            failureLogged = true
            BotLog.debug("检查更新失败（已降级为不提示）: ${failures.joinToString("; ")}")
        }
        return null
    }

    private fun shortLabel(url: String): String = when {
        url.contains("gh-proxy.org") -> "gh-proxy"
        url.contains("api.github.com") -> "github"
        else -> url.take(48)
    }

    private enum class SourceKind { GITHUB_LIST, AUTO }

    /** update-check.url 中配置的自定义数据源，逗号分隔。 */
    private fun configuredSources(): List<String> = try {
        BotShared.getPlugin().getUpdateCheckUrls()
            .split(",")
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 通用解析：兼容 JSON（{"version"/"tag"/"tag_name"}）与纯文本版本号（如 version.txt 内容 1.12.0）。
     */
    private fun parseAuto(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            val json = JSON.parseObject(trimmed) ?: return null
            return normalize(json.getString("version") ?: json.getString("tag") ?: json.getString("tag_name"))
        }
        return Regex("\\d+(?:\\.\\d+)*").find(trimmed)?.value?.let { normalize(it) }
    }

    /** GitHub releases 列表：取最新的非 draft、非 Pre-Release。 */
    private fun parseGithubReleases(body: String): String? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) return null
        val releases = JSON.parseArray(trimmed) ?: JSONArray()
        return releases.mapNotNull { it as? JSONObject }
            .filter { !it.getBooleanValue("draft") && !it.getBooleanValue("prerelease") }
            .maxByOrNull { it.getString("published_at").orEmpty() }
            ?.getString("tag_name")
            ?.let { normalize(it) }
    }

    private fun normalize(tag: String?): String? =
        tag?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() }

    private fun readBody(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("User-Agent", "PenguinAgent-UpdateCheck")
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/json")
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    /** 比较两个版本号，返回 1 表示 left 更新，-1 表示 right 更新，0 表示相同。 */
    private fun compareVersion(left: String, right: String): Int {
        fun parts(value: String) = value.trim().removePrefix("v")
            .split('.', '-', '_')
            .map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val result = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    data class UpdateState(
        val latest: String?,
        val outdated: Boolean,
        val checked: Boolean
    ) {
        companion object {
            fun disabled() = UpdateState(null, false, false)
        }
    }
}

private object BotLog {
    fun debug(message: String) {
        try {
            cn.huohuas001.bot.provider.BotShared.getPlugin().log_debug("更新检查：$message")
        } catch (_: Exception) {
        }
    }
}
