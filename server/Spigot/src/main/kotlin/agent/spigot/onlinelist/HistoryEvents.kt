package agent.spigot.onlinelist

import agent.spigot.HuHoBotSpigot
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/** 加入时注册集合立即变化；退出事件在 Bukkit 移除前触发，所以显式删除，不等待下一个 tick。 */
class HistoryEvents(plugin: HuHoBotSpigot,
    private val onJoin:(org.bukkit.entity.Player)->Unit = {},
    private val onQuit:(org.bukkit.entity.Player)->Unit = {}
): Listener {
    private val online=java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.UUID>().apply {
        plugin.server.onlinePlayers.forEach { add(it.uniqueId) }
    }
    @EventHandler(priority=EventPriority.MONITOR) fun joined(e:PlayerJoinEvent) {
        online.add(e.player.uniqueId);OnlineHistoryStore.record(online.size);onJoin(e.player)
    }
    @EventHandler(priority=EventPriority.MONITOR) fun left(e:PlayerQuitEvent) {
        online.remove(e.player.uniqueId);OnlineHistoryStore.record(online.size);onQuit(e.player)
    }
}
