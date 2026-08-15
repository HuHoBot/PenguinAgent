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

        val apiUrl = "https://motd.minebbs.com/api/status?ip=$host&stype=je"
        val imgUrl = "https://motd.minebbs.com/api/status_img?ip=$host&stype=je&theme=simple"

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

            val online = response.getIntValue("online")
            val max = response.getIntValue("max")
            val delay = response.getIntValue("delay")
            val version = response.getString("version") ?: "未知"
            val protocol = response.getString("protocol") ?: "未知"
            val motd = response.getString("motd") ?: "未知"

            val playersObj = response.getJSONObject("players")
            val playersOnline = playersObj?.getIntValue("online") ?: online
            val playersMax = playersObj?.getIntValue("max") ?: max

            val sampleArray = playersObj?.getJSONArray("list")
            val playerList = mutableListOf<String>()
            if (sampleArray != null) {
                for (i in 0 until sampleArray.size) {
                    val player = sampleArray.getJSONObject(i)
                    player?.getString("name_clean")?.let { playerList.add(it) }
                        ?: player?.getString("name")?.let { playerList.add(it) }
                }
            }

            val markdown = buildString {
                appendLine("**MC 服务器状态查询**")
                appendLine()
                appendLine("状态: 在线")
                appendLine("MOTD: $motd")
                appendLine("版本: $version")
                appendLine("协议: $protocol")
                appendLine("在线人数: $playersOnline/$playersMax")
                appendLine("延迟: ${delay}ms")
                if (playerList.isNotEmpty()) {
                    appendLine()
                    appendLine("**在线玩家:**")
                    playerList.forEach { appendLine("- $it") }
                }
            }

            replyWithImg(plugin, event, markdown, imgUrl)
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
            setRequestProperty("User-Agent", "HuHoBotPenguin/1.1")
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
