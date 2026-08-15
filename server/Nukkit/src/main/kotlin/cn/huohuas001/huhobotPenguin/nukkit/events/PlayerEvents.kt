package cn.huohuas001.huhobotPenguin.nukkit.events

import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.nukkit.HuHoBotNukkit
import cn.nukkit.event.EventHandler
import cn.nukkit.event.Listener
import cn.nukkit.event.player.PlayerChatEvent
import cn.nukkit.event.player.PlayerJoinEvent
import cn.nukkit.event.player.PlayerQuitEvent

class PlayerEvents(private val plugin: HuHoBotNukkit) : Listener {
    @EventHandler
    fun onChat(event: PlayerChatEvent) {
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
