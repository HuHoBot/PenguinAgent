package cn.huohuas001.huhobotPenguin.spigot.manager

import com.alibaba.fastjson.JSON
import org.bukkit.plugin.messaging.PluginMessageListener

/**
 * 子服 ↔ 代理互通桥（huhobot:bridge 插件消息通道）。
 *
 * - `proxy-bridge.enabled=false`（默认）：完全不注册，零开销；
 * - 开启后：游戏聊天会转发到代理端（由代理广播给其它子服），
 *   代理端下发的消息以广播形式显示给玩家。
 * 通道载体是"任意一个在线玩家"（Minecraft 插件消息协议限制），无人在线时发送静默跳过。
 */
object ProxyBridge {

    const val CHANNEL = "huhobot:bridge"

    @Volatile
    private var enabled = false

    @Volatile
    private var pluginRef: org.bukkit.plugin.Plugin? = null

    fun initialize(plugin: org.bukkit.plugin.Plugin, enabled: Boolean) {
        this.enabled = enabled
        this.pluginRef = plugin
        if (!enabled) return
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
        plugin.server.messenger.registerIncomingPluginChannel(plugin, CHANNEL, Incoming())
        plugin.logger.info("代理互通桥已启用(通道 $CHANNEL)")
    }

    fun shutdown() {
        val plugin = pluginRef ?: return
        if (enabled) {
            runCatching {
                plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL)
                plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CHANNEL)
            }
        }
        enabled = false
        pluginRef = null
    }

    fun isEnabled(): Boolean = enabled

    /** 子服 → 代理：转发一条游戏聊天。 */
    fun forwardChat(player: org.bukkit.entity.Player, playerName: String, message: String) {
        if (!enabled) return
        val plugin = pluginRef ?: return
        runCatching {
            val payload = JSON.toJSONString(
                mapOf("type" to "chat", "player" to playerName, "message" to message)
            )
            player.sendPluginMessage(plugin, CHANNEL, payload.toByteArray(Charsets.UTF_8))
        }
    }

    private class Incoming : PluginMessageListener {
        override fun onPluginMessageReceived(channel: String, player: org.bukkit.entity.Player, data: ByteArray) {
            if (channel != CHANNEL) return
            val plugin = pluginRef
            if (plugin == null) return
            val json = try {
                JSON.parseObject(String(data, Charsets.UTF_8))
            } catch (_: Throwable) {
                null
            } ?: return
            if (!"chat".equals(json.getString("type"))) return
            val message = json.getString("message") ?: return
            plugin.server.scheduler.runTask(plugin, java.lang.Runnable {
                org.bukkit.Bukkit.broadcastMessage(org.bukkit.ChatColor.GRAY.toString() + message)
            })
        }
    }
}
