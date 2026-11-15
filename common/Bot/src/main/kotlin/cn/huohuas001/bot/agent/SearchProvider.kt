package cn.huohuas001.bot.agent

import com.alibaba.fastjson.JSON
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

/**
 * AI 联网搜索。
 *
 * 支持三种 provider：
 * - bing   原生必应网页抓取（无需密钥；返回 SERP 摘要，稳定性依赖页面结构，属尽力而为）
 * - custom OpenAI 风格之外的通用搜索 API：POST/GET {api-url}，鉴权用 api-key（按 provider 类型放
 *   header 或 query），响应里智能抽取 JSON 数组字段（results/data/items/organic/news/list）
 * - none   关闭
 *
 * 搜索链路统一走 [ProxySupport]，AI 的其它 HTTP 请求也复用同一代理配置。
 */
object SearchProvider {

    data class Result(val title: String, val url: String, val snippet: String)

    data class Config(
        val provider: String,
        val apiUrl: String,
        val apiKey: String,
        val resultCount: Int = 5
    )

    fun search(config: Config, query: String): List<Result> {
        return when (config.provider.trim().lowercase()) {
            "bing" -> searchBing(query, config.resultCount)
            "custom" -> searchCustom(config, query)
            else -> emptyList()
        }
    }

    fun summarize(results: List<Result>, limit: Int = 5): String {
        if (results.isEmpty()) return "（无搜索结果）"
        return results.take(limit).mapIndexed { index, r ->
            "${index + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet.take(300)}"
        }.joinToString("\n")
    }

    // ── 原生必应：抓取 SERP HTML，粗提取结果 ──

    private fun searchBing(query: String, count: Int): List<Result> {
        val url = "https://www.bing.com/search?q=${URLEncoder.encode(query, "UTF-8")}&count=${count.coerceIn(1, 20)}"
        val html = httpGet(url) ?: return emptyList()
        val results = mutableListOf<Result>()
        // b SERP 结果项：<li class="b_algo"><h2><a href="URL">TITLE</a></h2>...<p>SNIPPET</p>
        val itemRegex = Regex("<li class=\"b_algo\"(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
        val linkRegex = Regex("<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        for (m in itemRegex.findAll(html)) {
            val block = m.groupValues[1]
            val link = linkRegex.find(block) ?: continue
            val linkUrl = link.groupValues[1]
            val title = stripTags(link.groupValues[2])
            val snippet = stripTags(snippetRegex.find(block)?.value ?: "")
            if (title.isBlank() || linkUrl.isBlank()) continue
            results += Result(title, linkUrl, snippet)
            if (results.size >= count) break
        }
        return results
    }

    // ── 自定义 API：常见 JSON 搜索服务 ──

    private fun searchCustom(config: Config, query: String): List<Result> {
        if (config.apiUrl.isBlank()) return emptyList()
        val body: String = try {
            if (config.apiUrl.contains("{query}")) {
                // URL 模板型：GET {api-url}，{query} 占位符替换
                val filled = config.apiUrl
                    .replace("{query}", URLEncoder.encode(query, "UTF-8"))
                    .replace("{count}", config.resultCount.toString())
                    .replace("{apikey}", URLEncoder.encode(config.apiKey, "UTF-8"))
                httpGet(withApiKeyHeader(filled, config)) ?: return emptyList()
            } else {
                // POST JSON 型：{"query": "..."}，返回 JSON
                val conn = open(config.apiUrl, "POST")
                config.apiKey.takeIf(String::isNotBlank)?.let {
                    conn.setRequestProperty("Authorization", "Bearer $it")
                    conn.setRequestProperty("X-API-KEY", it)
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
        return parseFlexibleJson(body, config.resultCount)
    }

    private fun withApiKeyHeader(url: String, config: Config): String = url

    /** 兼容多种返回结构：results / data / items / organic / news / list / 顶层数组。 */
    private fun parseFlexibleJson(body: String, count: Int): List<Result> {
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

    // ── HTTP ──

    private fun httpGet(url: String): String? = try {
        val conn = open(url, "GET")
        val code = conn.responseCode
        val text = if (code in 200..299) conn.inputStream.bufferedReader().readText()
        else conn.errorStream?.bufferedReader()?.readText()
        conn.disconnect()
        if (code in 200..299) text else null
    } catch (_: Throwable) {
        null
    }

    private fun open(url: String, method: String): HttpURLConnection {
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
 * Java 代理是进程级 HttpURLConnection 使用的实例级配置，这里提供统一入口。
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
