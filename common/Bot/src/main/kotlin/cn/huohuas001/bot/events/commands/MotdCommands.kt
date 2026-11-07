package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import com.alibaba.fastjson.JSON
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MotdCommands : CommandSupport() {

    companion object {
        /** MOTD 查询服务（状态 JSON + app_img 图片）。 */
        private const val MOTD_API_BASE = "https://motd.txssb.cn/api"
        private const val MOTD_API_REFERER = "https://motd.txssb.cn/"

        /** 根据 `/motd` 参数生成状态 JSON 接口与图片接口地址。 */
        internal fun buildApiUrls(input: String): Pair<String, String> {
            val target = parseTarget(input)
            val query = buildString {
                append("ip=").append(URLEncoder.encode(target.host, "UTF-8"))
                // 显式给了端口才传 port；否则交给 stype=auto 解析 SRV 记录
                target.port?.let { append("&port=").append(it) }
                append("&stype=auto&lang=zh-CN")
            }
            return "$MOTD_API_BASE/status?$query" to "$MOTD_API_BASE/app_img?$query"
        }

        private data class Target(val host: String, val port: Int?)

        /**
         * 解析 `/motd` 的地址参数，支持：
         * - `mc.hypixel.net`（不传 port，由服务端解析 SRV）
         * - `mc.hypixel.net:25566`
         * - `[::1]:25566`（IPv6 带端口）
         * - `::1`（IPv6 不带端口）
         */
        private fun parseTarget(input: String): Target {
            val trimmed = input.trim()

            if (trimmed.startsWith("[")) {
                val end = trimmed.indexOf(']')
                if (end > 0) {
                    val host = trimmed.substring(1, end)
                    val port = trimmed.substring(end + 1).removePrefix(":").toIntOrNull()
                    return Target(host, port?.coerceIn(1, 65535))
                }
            }

            // 仅当只有一个冒号时才按 host:port 切分，避免破坏不带方括号的 IPv6
            val colon = trimmed.lastIndexOf(':')
            if (colon > 0 && trimmed.indexOf(':') == colon) {
                val port = trimmed.substring(colon + 1).toIntOrNull()
                if (port != null) {
                    return Target(trimmed.substring(0, colon), port.coerceIn(1, 65535))
                }
            }

            return Target(trimmed, null)
        }
    }

    @Commands(command = "motd", describe = "查询 MC 服务器状态")
    fun motd(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val input = params.trim()
        if (input.isBlank()) {
            reply(plugin, event, "用法: /motd <服务器地址[:端口]>\n示例: /motd mc.hypixel.net\n示例: /motd mc.hypixel.net:25566")
            return
        }

        val (apiUrl, imgUrl) = buildApiUrls(input)

        try {
            val response = fetchJson(apiUrl)
            if (response == null) {
                reply(plugin, event, "查询失败，请检查服务器地址是否正确")
                return
            }

            val status = response.getString("status") ?: "unknown"
            if (status != "online") {
                val error = response.getString("error") ?: "服务器离线或无法连接"
                reply(plugin, event, "服务器 $input 当前离线\n$error")
                return
            }

            val serverType = response.getString("type") ?: "Java"
            val version = response.getString("version") ?: "未知"
            val protocol = response.getIntValue("protocol")
            val delay = response.getIntValue("delay")

            val motdText = response.getString("pureMotd")
                ?: response.getJSONObject("motd")?.getString("pureMotd")
                ?: "未知"

            val playersObj = response.getJSONObject("players")
            val playersOnline = playersObj?.getIntValue("online") ?: 0
            val playersMax = playersObj?.getIntValue("max") ?: 0
            val sampleStr = playersObj?.getString("sample") ?: ""

            val playerList = if (sampleStr.isNotBlank() && sampleStr != "无") {
                sampleStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }

            val markdown = buildString {
                appendLine("**MC 服务器状态查询**")
                appendLine()
                appendLine("类型: $serverType")
                appendLine("状态: 在线")
                appendLine("MOTD: $motdText")
                appendLine("版本: $version")
                if (protocol > 0) appendLine("协议: $protocol")
                appendLine("在线人数: $playersOnline/$playersMax")
                appendLine("延迟: ${delay}ms")
                if (playerList.isNotEmpty()) {
                    appendLine()
                    appendLine("**在线玩家:**")
                    playerList.forEach { appendLine("- $it") }
                }
            }

            val groupOpenId = groupId(event)
            plugin.replyWithImg(event, "", imgUrl)
            plugin.sendMarkdownToGroup(groupOpenId, markdown)
        } catch (e: Exception) {
            plugin.log_error("MOTD 查询异常: ${e.message}")
            reply(plugin, event, "查询出错: ${e.message}")
        }
    }

    private fun fetchJson(urlStr: String): com.alibaba.fastjson.JSONObject? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            setRequestProperty("Referer", MOTD_API_REFERER)
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSON.parseObject(body)
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
