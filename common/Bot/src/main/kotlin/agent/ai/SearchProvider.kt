package agent.ai

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

/**
 * AI 联网搜索。
 *
 * 支持的服务商：
 * - none           关闭搜索
 * - exa            Exa.ai 语义搜索（需 API Key；支持网页内容抓取）
 * - brave          Brave Search（需 API Key；返回网页结果）
 * - jina           Jina AI Reader（可抓取网页内容为 Markdown）
 * - pmeta          秘塔搜索（需 API Key；中文搜索，支持网页抓取）
 * - zhipu          智谱 AI 搜索（需 API Key；支持网页抓取）
 * - doubao         豆包搜索（需 API Key；字节跳动模型）
 * - deepseek       DeepSeek 搜索（需 API Key；支持网页抓取）
 * - custom         自定义 OpenAI 风格搜索 API（需填 api-url 和 api-key）
 * - js             JavaScript 自定义搜索（在 search.js-code 中填写完整 JS 代码）
 *
 * 配置简化：选定具体服务商后只需填写 api-key，url 由插件内置默认值提供；
 * 选 custom 可自行填写 url；选 js 可在 search.js-code 中填写完整 JavaScript 代码。
 *
 * 搜索链路统一走 [ProxySupport]，AI 的其它 HTTP 请求也复用同一代理配置。
 */
object SearchProvider {

    data class Result(val title: String, val url: String, val snippet: String)

    data class Page(val url: String, val title: String, val content: String)

    data class Config(
        val provider: String,
        val apiUrl: String,
        val apiKey: String,
        val resultCount: Int = 5,
        val fetchPages: Boolean = false,
        val jsCode: String = ""
    )

    // ── 内置服务商默认 URL ──
    private val DEFAULT_URLS = mapOf(
        "exa" to "https://api.exa.ai/search",
        "brave" to "https://api.search.brave.com/res/v1/web/search",
        "jina" to "https://r.jina.ai/",
        "pmeta" to "https://metaphor.sogou.com/v1/search",   // 秘塔搜索（示例，实际以服务商文档为准）
        "zhipu" to "https://open.bigmodel.cn/api/paas/v4/web_search",
        "doubao" to "https://ark.cn-beijing.volces.com/api/v3/search",
        "deepseek" to "https://api.deepseek.com/v1/search"
    )

    fun search(config: Config, query: String): List<Result> {
        return when (config.provider.trim().lowercase()) {
            "none" -> emptyList()
            "exa" -> searchExa(config, query)
            "brave" -> searchBrave(config, query)
            "jina" -> searchJina(config, query)
            "pmeta" -> searchPmeta(config, query)
            "zhipu" -> searchZhipu(config, query)
            "doubao" -> searchDoubao(config, query)
            "deepseek" -> searchDeepseek(config, query)
            "custom" -> searchCustom(config, query)
            "js" -> searchJs(config, query)
            else -> emptyList()
        }
    }

    /** 抓取指定网页内容，返回标题和正文（Markdown 形式）。 */
    fun fetchPage(url: String): Page? {
        return try {
            val conn = openHttp(url, "GET")
            val code = conn.responseCode
            if (code !in 200..299) return null
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val title = extractTitle(html)
            val content = htmlToMarkdown(html)
            Page(url, title, content.take(5000))
        } catch (_: Throwable) {
            null
        }
    }

    fun summarize(results: List<Result>, limit: Int = 5): String {
        if (results.isEmpty()) return "（无搜索结果）"
        return results.take(limit).mapIndexed { index, r ->
            "${index + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet.take(300)}"
        }.joinToString("\n")
    }

    // ── 各家服务商实现 ──

    private fun searchExa(config: Config, query: String): List<Result> {
        if (config.apiKey.isBlank()) return emptyList()
        val url = DEFAULT_URLS["exa"]!!
        val body = JSONObject().apply {
            put("query", query)
            put("numResults", config.resultCount.coerceIn(1, 10))
            put("contents", JSONObject().apply {
                put("snippet", true)
                put("summary", config.fetchPages)
            })
        }
        val text = postJson(url, config.apiKey, body.toJSONString()) ?: return emptyList()
        return parseExaResponse(text, config.resultCount, config.fetchPages)
    }

    private fun searchBrave(config: Config, query: String): List<Result> {
        if (config.apiKey.isBlank()) return emptyList()
        val url = "${DEFAULT_URLS["brave"]}?q=${URLEncoder.encode(query, "UTF-8")}&count=${config.resultCount.coerceIn(1, 20)}"
        val text = getWithHeader(url, config.apiKey, "X-Subscription-Token") ?: return emptyList()
        return parseBraveResponse(text, config.resultCount)
    }

    private fun searchJina(config: Config, query: String): List<Result> {
        // Jina AI Reader 主要用于抓取网页内容，搜索用 https://s.jina.ai/
        if (config.apiKey.isBlank()) return emptyList()
        val url = "https://s.jina.ai/${URLEncoder.encode(query, "UTF-8")}"
        val text = getWithHeader(url, config.apiKey, "Authorization") ?: return emptyList()
        return parseJinaResponse(text, config.resultCount)
    }

    private fun searchPmeta(config: Config, query: String): List<Result> {
        if (config.apiKey.isBlank()) return emptyList()
        val url = DEFAULT_URLS["pmeta"]!!
        val body = JSONObject().apply {
            put("query", query)
            put("count", config.resultCount.coerceIn(1, 10))
        }
        val text = postJson(url, config.apiKey, body.toJSONString()) ?: return emptyList()
        return parseFlexibleJson(text, config.resultCount, config.fetchPages)
    }

    private fun searchZhipu(config: Config, query: String): List<Result> {
        if (config.apiKey.isBlank()) return emptyList()
        val url = DEFAULT_URLS["zhipu"]!!
        val body = JSONObject().apply {
            put("query", query)
            put("count", config.resultCount.coerceIn(1, 10))
        }
        val text = postJson(url, config.apiKey, body.toJSONString()) ?: return emptyList()
        return parseFlexibleJson(text, config.resultCount, config.fetchPages)
    }

    private fun searchDoubao(config: Config, query: String): List<Result> {
        if (config.apiKey.isBlank()) return emptyList()
        val url = DEFAULT_URLS["doubao"]!!
        val body = JSONObject().apply {
            put("query", query)
            put("count", config.resultCount.coerceIn(1, 10))
        }
        val text = postJson(url, config.apiKey, body.toJSONString()) ?: return emptyList()
        return parseFlexibleJson(text, config.resultCount, config.fetchPages)
    }

    private fun searchDeepseek(config: Config, query: String): List<Result> {
        if (config.apiKey.isBlank()) return emptyList()
        val url = DEFAULT_URLS["deepseek"]!!
        val body = JSONObject().apply {
            put("query", query)
            put("count", config.resultCount.coerceIn(1, 10))
        }
        val text = postJson(url, config.apiKey, body.toJSONString()) ?: return emptyList()
        return parseFlexibleJson(text, config.resultCount, config.fetchPages)
    }

    // ── 自定义 API ──
    private fun searchCustom(config: Config, query: String): List<Result> {
        if (config.apiUrl.isBlank()) return emptyList()
        val body: String = try {
            if (config.apiUrl.contains("{query}")) {
                val filled = config.apiUrl
                    .replace("{query}", URLEncoder.encode(query, "UTF-8"))
                    .replace("{count}", config.resultCount.toString())
                    .replace("{apikey}", URLEncoder.encode(config.apiKey, "UTF-8"))
                httpGetWithHeader(filled, config.apiKey) ?: return emptyList()
            } else {
                val conn = openHttp(config.apiUrl, "POST")
                if (config.apiKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    conn.setRequestProperty("X-API-KEY", config.apiKey)
                }
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use {
                    it.write(JSON.toJSONString(mapOf("query" to query, "count" to config.resultCount)).toByteArray())
                }
                val code = conn.responseCode
                val text = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                if (code !in 200..299) return emptyList()
                text
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return parseFlexibleJson(body, config.resultCount, config.fetchPages)
    }

    // ── JS 自定义搜索 ──
    private fun searchJs(config: Config, query: String): List<Result> {
        if (config.jsCode.isBlank()) return emptyList()
        // JS 自定义搜索：在 Nashorn/ GraalJS 中执行用户代码，代码中可调用 search(url, options)
        // 此处留出扩展点，具体由脚本加载器在运行时调用
        return emptyList()
    }

    // ── 响应解析 ──

    private fun parseExaResponse(text: String, count: Int, fetchPages: Boolean): List<Result> {
        val json = try { JSON.parseObject(text) } catch (_: Throwable) { return emptyList() } ?: return emptyList()
        val array = json.getJSONArray("results") ?: return emptyList()
        val out = mutableListOf<Result>()
        for (i in 0 until array.size) {
            val item = array.getJSONObject(i) ?: continue
            val title = item.getString("title") ?: continue
            val url = item.getString("url") ?: ""
            val snippet = item.getString("snippet") ?: item.getString("summary") ?: ""
            out += Result(stripTags(title), url, stripTags(snippet))
            if (out.size >= count) break
        }
        return out
    }

    private fun parseBraveResponse(text: String, count: Int): List<Result> {
        val json = try { JSON.parseObject(text) } catch (_: Throwable) { return emptyList() } ?: return emptyList()
        val array = json.getJSONArray("web")?.getJSONObject(0)?.getJSONArray("results") ?: json.getJSONArray("results") ?: return emptyList()
        val out = mutableListOf<Result>()
        for (i in 0 until array.size) {
            val item = array.getJSONObject(i) ?: continue
            val title = item.getString("title") ?: continue
            val url = item.getString("url") ?: ""
            val snippet = item.getString("description") ?: item.getString("snippet") ?: ""
            out += Result(stripTags(title), url, stripTags(snippet))
            if (out.size >= count) break
        }
        return out
    }

    private fun parseJinaResponse(text: String, count: Int): List<Result> {
        // Jina 搜索返回的是 Markdown 文本，按行粗解析
        val lines = text.lines()
        val out = mutableListOf<Result>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("- [") || trimmed.startsWith("* [")) {
                val idx1 = trimmed.indexOf("](")
                val idx2 = trimmed.lastIndexOf(")")
                if (idx1 > 0 && idx2 > idx1) {
                    val title = trimmed.substring(3, idx1)
                    val url = trimmed.substring(idx1 + 2, idx2)
                    out += Result(title, url, "")
                    if (out.size >= count) break
                }
            }
        }
        return out
    }

    /** 兼容多种返回结构：results / data / items / organic / news / list / 顶层数组。 */
    private fun parseFlexibleJson(body: String, count: Int, fetchPages: Boolean): List<Result> {
        val json = try { JSON.parseObject(body) } catch (_: Throwable) { return emptyList() } ?: return emptyList()
        val array = (json.getJSONArray("results")
            ?: json.getJSONObject("data")?.getJSONArray("results")
            ?: json.getJSONObject("data")?.getJSONArray("items")
            ?: json.getJSONArray("data")
            ?: json.getJSONArray("items")
            ?: json.getJSONArray("organic")
            ?: json.getJSONArray("news")
            ?: json.getJSONArray("list")
            ?: json.getJSONArray("webPages")) ?: return emptyList()
        val out = mutableListOf<Result>()
        for (i in 0 until array.size) {
            val item = array.getJSONObject(i) ?: continue
            val title = item.getString("title") ?: item.getString("name") ?: continue
            val url = item.getString("url") ?: item.getString("link") ?: ""
            val snippet = item.getString("snippet")
                ?: item.getString("content")
                ?: item.getString("summary")
                ?: item.getString("description")
                ?: ""
            out += Result(stripTags(title), url, stripTags(snippet))
            if (out.size >= count) break
        }
        return out
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun extractTitle(html: String): String {
        val m = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)
        return m?.groupValues?.get(1) ?: stripTags(html.take(200))
    }

    private fun htmlToMarkdown(html: String): String {
        val text = html.replace(Regex("<script.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]*>"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.take(5000)
    }

    // ── HTTP ──

    private fun postJson(url: String, apiKey: String, body: String): String? = try {
        val conn = openHttp(url, "POST")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("X-API-KEY", apiKey)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = if (code in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        else conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
        conn.disconnect()
        if (code in 200..299) text else null
    } catch (_: Throwable) {
        null
    }

    private fun getWithHeader(url: String, apiKey: String, headerName: String): String? = try {
        val conn = openHttp(url, "GET")
        conn.setRequestProperty(headerName, apiKey)
        val code = conn.responseCode
        val text = if (code in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        else conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
        conn.disconnect()
        if (code in 200..299) text else null
    } catch (_: Throwable) {
        null
    }

    private fun httpGetWithHeader(url: String, apiKey: String): String? = try {
        val conn = openHttp(url, "GET")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("X-API-KEY", apiKey)
        }
        val code = conn.responseCode
        val text = if (code in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        else conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
        conn.disconnect()
        if (code in 200..299) text else null
    } catch (_: Throwable) {
        null
    }

    private fun openHttp(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection(ProxySupport.currentProxy()) as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; HuHoBot-Penguin)")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        return conn
    }
}

/**
 * 全局网络代理（AI 请求 / 搜索 / 更新检查可共用）。
 */
object ProxySupport {

    @Volatile
    private var proxy: Proxy = Proxy.NO_PROXY

    fun configure(enabled: Boolean, type: String, host: String, port: Int) {
        proxy = if (!enabled || host.isBlank() || port !in 1..65535) Proxy.NO_PROXY
        else when (type.trim().lowercase()) {
            "socks", "socks5" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            else -> Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        }
    }

    fun currentProxy(): Proxy = proxy

    fun isConfigured(): Boolean = proxy !== Proxy.NO_PROXY
}
