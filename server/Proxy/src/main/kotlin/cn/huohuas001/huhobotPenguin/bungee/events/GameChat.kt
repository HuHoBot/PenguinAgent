package cn.huohuas001.huhobotPenguin.bungee.events

import cn.huohuas001.bot.QClient
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.ChatEvent
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler

class GameChat : Listener {
    @EventHandler
    fun onPlayerChat(event: ChatEvent) {
        if (event.isCommand || event.isProxyCommand) return
        val sender = event.sender
        if (sender is ProxiedPlayer) QClient.broadcastGameMessage(sender.name, event.message)
    }

    @EventHandler
    fun onPlayerJoin(event: PostLoginEvent) {
        QClient.broadcastPlayerJoin(event.player.name)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerDisconnectEvent) {
        QClient.broadcastPlayerQuit(event.player.name)
    }
}
