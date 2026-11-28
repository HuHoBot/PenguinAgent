package agent.spigot.onlinelist

import java.util.Locale

/** 管理员/称号玩家优先；每一层都按名字首字母、完整名称稳定排序。 */
object OnlineListPages {
    fun hasTitle(player: OnlineListPlayer): Boolean = McTextParser.plainText(player.prefix).isNotBlank()
    fun hasSuffix(player: OnlineListPlayer): Boolean = McTextParser.plainText(player.suffix).isNotBlank()

    /** Eight explicit tiers: the six requested categories, plus suffix-only after each no-title tier. */
    fun priorityTier(player: OnlineListPlayer): Int {
        val local=when { hasTitle(player)&&hasSuffix(player)->0;hasTitle(player)->1;!hasSuffix(player)->2;else->3 }
        return (if(player.administrator)0 else 4)+local
    }
    fun initialKey(name: String): Char = name.trim().firstOrNull()?.lowercaseChar() ?: '\uFFFF'

    fun comparator(): Comparator<OnlineListPlayer> =
        compareBy<OnlineListPlayer> { priorityTier(it) }
            .thenBy { initialKey(it.name) }
            .thenBy { it.name.trim().lowercase(Locale.ROOT) }
            .thenBy { it.uuid }

    fun sorted(players: List<OnlineListPlayer>): List<OnlineListPlayer> = players.sortedWith(comparator())

    fun totalPages(playerCount: Int, pageSize: Int): Int =
        maxOf(1, (playerCount.coerceAtLeast(0) + pageSize.coerceAtLeast(1) - 1) / pageSize.coerceAtLeast(1))

    fun page(complete: ServerSnapshot, requestedPage: Int, pageSize: Int): ServerSnapshot {
        val size = pageSize.coerceAtLeast(1)
        val pages = totalPages(complete.players.size, size)
        require(requestedPage in 1..pages)
        val from = ((requestedPage - 1) * size).coerceAtMost(complete.players.size)
        return complete.copy(
            players = complete.players.subList(from, (from + size).coerceAtMost(complete.players.size)),
            currentPage = requestedPage,
            totalPages = pages
        )
    }
}
