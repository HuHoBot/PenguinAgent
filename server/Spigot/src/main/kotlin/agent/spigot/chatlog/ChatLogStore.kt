package agent.spigot.chatlog

import java.util.ArrayDeque

/** Bounded, memory-only public chat history. Nothing is persisted: logs stay where the owner chose. */
object ChatLogStore {
    data class Entry(val name: String, val display: String, val message: String, val at: Long)

    private val entries = ArrayDeque<Entry>()
    @Volatile private var maximum = 300

    @Synchronized fun configure(keep: Int) {
        maximum = keep.coerceIn(30, 5000)
        while (entries.size > maximum) entries.removeFirst()
    }

    @Synchronized fun record(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > maximum) entries.removeFirst()
    }

    @Synchronized fun clear() = entries.clear()

    @Synchronized fun size(): Int = entries.size

    /**
     * Newest first. A long message occupies two visual slots so a page never becomes wall-to-wall
     * text and the rendered image stays readable.
     */
    @Synchronized fun page(playerFilter: String?, requestedPage: Int, pageSize: Int, maxLength: Int): Result {
        val filtered = entries.asSequence().filter { playerFilter == null || it.name.equals(playerFilter, true) }.toList().asReversed()
        if (filtered.isEmpty()) return Result(emptyList(), 0, 1, 0)
        val pages = mutableListOf<MutableList<Entry>>()
        var current = mutableListOf<Entry>()
        var slots = 0
        for (entry in filtered) {
            val cost = if (entry.message.length <= maxLength) 1 else 2
            if (current.isNotEmpty() && slots + cost > pageSize) {
                pages.add(current); current = mutableListOf(); slots = 0
            }
            current.add(entry); slots += cost
        }
        if (current.isNotEmpty()) pages.add(current)
        // A pathological single entry still belongs to exactly one page, never dropped.
        val total = pages.size.coerceAtLeast(1)
        val index = (requestedPage - 1).coerceIn(0, total - 1)
        return Result(pages.getOrElse(index) { mutableListOf() }, index + 1, total, filtered.size)
    }

    data class Result(val entries: List<Entry>, val page: Int, val totalPages: Int, val totalEntries: Int)
}
