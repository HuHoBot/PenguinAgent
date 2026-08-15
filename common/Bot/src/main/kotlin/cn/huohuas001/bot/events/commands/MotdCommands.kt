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
            reply(plugin, event, "用法: /motd <服务器地址>\n示例: /motd mc.hypixel.net:25565")
            return
        }

        val serverAddress = if (host.contains(":")) host else "$host:25565"

        val apiUrl = "https://motdbe.blackbe.work/api/java?host=$serverAddress"
        val imgApiUrl = "https://motdbe.blackbe.work/status_img/java?host=$serverAddress"

        try {
            val response = fetchJson(apiUrl)
            if (response == null) {
                reply(plugin, event, "查询失败，请检查服务器地址是否正确")
                return
            }

            val status = response.getString("status") ?: "unknown"
            if (status != "online") {
                reply(plugin, event, "服务器 $serverAddress 当前离线或无法连接")
                return
            }

            val motd = response.getString("motd") ?: "未知"
            val version = response.getString("version") ?: "未知"
            val agreement = response.getString("agreement") ?: "未知"
            val online = response.getIntValue("online")
            val max = response.getIntValue("max")
            val delay = response.getIntValue("delay")
            val levelName = response.getString("level_name") ?: ""
            val gamemode = response.getString("gamemode") ?: ""

            val sampleArray = response.getJSONArray("sample")
            val playerList = mutableListOf<String>()
            if (sampleArray != null) {
                for (i in 0 until sampleArray.size) {
                    val player = sampleArray.getJSONObject(i)
                    player?.getString("name")?.let { playerList.add(it) }
                }
            }

            val markdown = buildString {
                appendLine("**服务器状态查询**")
                appendLine()
                appendLine("状态: 在线")
                appendLine("描述: $motd")
                appendLine("延迟: ${delay}ms")
                appendLine("版本: $version")
                appendLine("协议: $agreement")
                appendLine("在线人数: $online/$max")
                if (gamemode.isNotBlank()) appendLine("游戏模式: $gamemode")
                if (levelName.isNotBlank()) appendLine("存档名称: $levelName")
                if (playerList.isNotEmpty()) {
                    appendLine()
                    appendLine("**在线玩家:**")
                    playerList.forEach { appendLine("- $it") }
                }
            }

            replyWithImg(plugin, event, markdown, imgApiUrl)
        } catch (e: Exception) {
            plugin.log_error("MOTD 查询异常: ${e.message}")
            reply(plugin, event, "查询出错: ${e.message}")
        }
    }

    private fun fetchJson(urlStr: String): com.alibaba.fastjson.JSONObject? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
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
