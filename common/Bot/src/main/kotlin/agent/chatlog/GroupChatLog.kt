package agent.chatlog

import java.util.ArrayDeque

/**
 * In-memory, bounded recent group chat shared by the chat AI tools.
 * Nothing is written to disk; a restart clears it. Reading the full group chat still requires
 * the owner's QQ "receive all messages" setting, otherwise only mentions/commands arrive.
 */
object GroupChatLog {
    data class Entry(val name: String, val text: String, val at: Long)

    private const val MAX_PER_GROUP = 200
    private const val MAX_GROUPS = 256
    private val groups = LinkedHashMap<String, ArrayDeque<Entry>>()

    @Synchronized fun record(groupOpenId: String, name: String, text: String) {
        if (groupOpenId.isBlank() || text.isBlank()) return
        val queue = groups.getOrPut(groupOpenId) { ArrayDeque() }
        queue.addLast(Entry(name.take(64), text.take(1000), System.currentTimeMillis()))
        while (queue.size > MAX_PER_GROUP) queue.removeFirst()
        while (groups.size > MAX_GROUPS) groups.remove(groups.keys.first())
    }

    @Synchronized fun recent(groupOpenId: String, limit: Int): List<Entry> =
        groups[groupOpenId]?.toList()?.takeLast(limit.coerceIn(1, 50))?.asReversed().orEmpty()

    /** Newest first; case-insensitive substring, bounded so a tool call cannot scan forever. */
    @Synchronized fun search(groupOpenId: String, keyword: String, limit: Int): List<Entry> {
        if (keyword.isBlank()) return emptyList()
        val needle = keyword.lowercase()
        return groups[groupOpenId]?.asSequence()
            ?.filter { it.text.lowercase().contains(needle) || it.name.lowercase().contains(needle) }
            ?.toList()?.takeLast(limit.coerceIn(1, 50))?.asReversed().orEmpty()
    }

    @Synchronized fun clear() = groups.clear()
}
