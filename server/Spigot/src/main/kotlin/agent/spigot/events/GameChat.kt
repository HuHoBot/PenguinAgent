package agent.spigot.events

import agent.QClient
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class GameChat : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        // Public chat history is captured before moderation so an operator sees what players
        // actually typed; the image command never invents or replays anything into the world.
        val meta = agent.spigot.onlinelist.NameMeta.read(event.player)
        agent.spigot.chatlog.ChatLogStore.record(
            agent.spigot.chatlog.ChatLogStore.Entry(
                name = event.player.name,
                display = (meta.prefix + event.player.name + meta.suffix).trim(),
                message = event.message,
                at = System.currentTimeMillis()
            )
        )
        QClient.broadcastGameMessage(event.player.name, event.message)
        // 代理互通桥：转发到代理端广播其它子服（默认关闭）
        agent.spigot.manager.ProxyBridge.forwardChat(event.player, event.player.name, event.message)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        QClient.broadcastPlayerJoin(event.player.name)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        QClient.broadcastPlayerQuit(event.player.name)
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        QClient.broadcastPlayerDeath(
            event.entity.name,
            DeathMessage.describe(event),
            DeathMessage.killerOf(event),
            event.deathMessage
        )
    }
}
