package agent.spigot.manager

import agent.QClient
import agent.spigot.HuHoBotSpigot
import agent.tools.Cancelable
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 中心化链接（huhobot.us.ci 或自部署站点）：
 * 用 server_key 定时上报在线人数/玩家/最近聊天，平台返回展示页链接。
 * webui-user / webui-pass 只以哈希形式参与鉴权，明文不出服务器。
 */
object LinkReporter {
    @Volatile private var task: Cancelable? = null

    fun start(plugin: HuHoBotSpigot) { stop(); task = plugin.submitTimer(30_000L, 30_000L) { tick(plugin) } }
    fun stop() { task?.cancel(); task = null }

    private fun tick(plugin: HuHoBotSpigot) {
        try {
            if (!plugin.isLinkEnabled()) return
            val key = plugin.getLinkServerKey()
            if (key.isBlank()) return
            val base = plugin.getLinkBaseUrl().trim().removeSuffix("/")
            if (base.isBlank()) return
            val body = com.alibaba.fastjson.JSONObject().apply {
                put("server_key", key)
                put("online", plugin.getOnlineList().size)
                val players = org.bukkit.Bukkit.getOnlinePlayers()
                put("max", try { org.bukkit.Bukkit.getMaxPlayers() } catch (_: Throwable) { 0 })
                put("players", players.map { it.name })
                put("chat", agent.spigot.chatlog.ChatLogStore.page(null, 1, 30, 80).entries.map { it.name + ": " + it.message })
                val wu = plugin.getLinkWebuiUser()
                if (wu.isNotBlank()) {
                    put("webui_user", wu)
                    put("webui_hash", sha256(plugin.getLinkWebuiPass() + "|" + wu))
                }
            }
            val conn = URL("$base/api/report").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000; conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toJSONString()) }
                conn.responseCode // 上报即发即忘；失败下轮重试
            } finally { conn.disconnect() }
        } catch (_: Throwable) { /* 网络失败静默，下轮重试 */ }
    }

    fun sha256(v: String): String =
        MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
