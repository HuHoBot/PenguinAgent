package cn.huohuas001.huhobotPenguin.spigot.events

import cn.huohuas001.bot.QClient
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class GameChat : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        QClient.broadcastGameMessage(event.player.name, event.message)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        QClient.broadcastPlayerJoin(event.player.name)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        QClient.broadcastPlayerQuit(event.player.name)
    }
}
