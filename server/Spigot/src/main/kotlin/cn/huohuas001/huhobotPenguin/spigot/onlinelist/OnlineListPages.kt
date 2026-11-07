package cn.huohuas001.huhobotPenguin.spigot.onlinelist

/** 在线列表稳定排序与分页工具。 */
object OnlineListPages {

    fun comparator(administratorsFirst: Boolean): Comparator<OnlineListPlayer> {
        val byName = compareBy<OnlineListPlayer>(
            { it.name.lowercase() },
            { it.name },
            { it.uuid }
        )
        if (!administratorsFirst) return byName
        return Comparator { a, b ->
            val adminOrder = compareValue(a.administrator, b.administrator)
            if (adminOrder != 0) adminOrder else byName.compare(a, b)
        }
    }

    fun sorted(players: List<OnlineListPlayer>, administratorsFirst: Boolean): List<OnlineListPlayer> {
        val result = players.toMutableList()
        result.sortWith(comparator(administratorsFirst))
        return result
    }

    fun totalPages(playerCount: Int, pageSize: Int): Int {
        val safePageSize = maxOf(1, pageSize)
        return maxOf(1, (maxOf(0, playerCount) + safePageSize - 1) / safePageSize)
    }

    fun page(
        complete: ServerSnapshot,
        requestedPage: Int,
        pageSize: Int
    ): ServerSnapshot {
        val safePageSize = maxOf(1, pageSize)
        val totalOnline = complete.players.size
        val pages = totalPages(totalOnline, safePageSize)
        require(requestedPage in 1..pages) { "page $requestedPage is outside 1..$pages" }
        val from = minOf((requestedPage - 1) * safePageSize, totalOnline)
        val to = minOf(from + safePageSize, totalOnline)
        return ServerSnapshot(
            serverName = complete.serverName,
            maxPlayers = complete.maxPlayers,
            totalOnlinePlayers = totalOnline,
            players = complete.players.subList(from, to),
            capturedAt = complete.capturedAt,
            currentPage = requestedPage,
            totalPages = pages
        )
    }

    private fun compareValue(a: Boolean, b: Boolean): Int {
        val order = if (a) 0 else 1
        val order2 = if (b) 0 else 1
        return order.compareTo(order2)
    }
}
