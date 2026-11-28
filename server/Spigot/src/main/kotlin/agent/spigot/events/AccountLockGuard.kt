package agent.spigot.events

import agent.state.CommandRepositories
import agent.spigot.HuHoBotSpigot
import org.bukkit.ChatColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * 账号锁定守卫。
 *
 * 玩家在 QQ 群执行 `/锁定` 后，对应的游戏账号会被标记为锁定；
 * 之后每次进服都会被立即踢出，直到在 QQ 群执行 `/解除锁定`。
 *
 * 与强制绑定相互独立：强制绑定拦的是「没有绑定 QQ」的玩家，
 * 这里拦的是「绑定者主动锁起来」的玩家，所以 [PlayerJoinEvent] 里只查锁定状态。
 */
class AccountLockGuard(private val plugin: HuHoBotSpigot) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!CommandRepositories.bindings.isLocked(player.name)) return

        // 下一 tick 再踢，让入服流程走完；理由整段放进踢出画面
        agent.spigot.manager.FoliaScheduler.runForEntity(plugin, player, Runnable {
            if (player.isOnline) {
                player.kickPlayer(buildKickMessage())
                plugin.log_info("账号锁定: 已拒绝 ${player.name} 进入服务器")
            }
        })
    }

    /**
     * 踢出理由。
     *
     * 与强制绑定提示保持同样的分区样式：暗色分隔线 + 红色标题 + 灰色说明。
     */
    private fun buildKickMessage(): String = buildString {
        val line = "${ChatColor.DARK_GRAY}${ChatColor.STRIKETHROUGH}${"─".repeat(30)}"
        appendLine(line)
        appendLine("${ChatColor.RED}${ChatColor.BOLD}账号已被锁定")
        appendLine(line)
        appendLine("${ChatColor.GRAY}该游戏账号已被绑定者锁定，暂时无法进入服务器。")
        appendLine()
        appendLine("${ChatColor.GRAY}如需恢复，请在 QQ 群执行 ${ChatColor.GREEN}/解除锁定${ChatColor.GRAY} 后再重新连接。")
        appendLine(line)
    }
}
