package cn.huohuas001.huhobotPenguin.velocity.events

import cn.huohuas001.bot.QClient
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.PlayerChatEvent

class GameChat {
    @Subscribe
    fun onPlayerChat(event: PlayerChatEvent) {
        QClient.broadcastGameMessage(event.player.username, event.message)
    }

    @Subscribe
    fun onPlayerJoin(event: PostLoginEvent) {
        QClient.broadcastPlayerJoin(event.player.username)
    }

    @Subscribe
    fun onPlayerQuit(event: DisconnectEvent) {
        if (event.loginStatus == DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            QClient.broadcastPlayerQuit(event.player.username)
        }
    }
}
