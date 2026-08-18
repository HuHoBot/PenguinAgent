package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import com.alibaba.fastjson.JSON
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.net.HttpURLConnection
import java.net.URL

class MotdCommands : CommandSupport() {

    @Commands("motd")
    fun motd(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val host = params.trim()
        if (host.isBlank()) {
            reply(plugin, event, "用法: /motd <服务器地址>\n示例: /motd mc.hypixel.net")
            return
        }

        val apiUrl = "https://motd.minebbs.com/api/status?ip=$host&stype=auto"
        val imgUrl = "https://motd.minebbs.com/api/status_img?ip=$host&stype=auto&theme=simple"

        try {
            val response = fetchJson(apiUrl)
            if (response == null) {
                reply(plugin, event, "查询失败，请检查服务器地址是否正确")
                return
            }

            val status = response.getString("status") ?: "unknown"
            if (status != "online") {
                val error = response.getString("error") ?: "服务器离线或无法连接"
                reply(plugin, event, "服务器 $host 当前离线\n$error")
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
            setRequestProperty("Referer", "https://motd.minebbs.com/")
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
