package agent.messaging

/**
 * Bounded, process-local group passive-reply accounting. The 2026-11-17 API change
 * gives group messages a fixed 60-minute lifetime; the quota remains 5 unless the
 * developer (not merely the bot) is the group's owner/admin, in which case it is 50.
 * Taking a slot never extends a message's original deadline or resets its sequence.
 */
class PassiveReplyLedger(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxGroups: Int = 512,
    private val maxMessagesPerGroup: Int = 32
) {
    companion object { const val TTL_MS = 60 * 60_000L }
    private class Window(val arrivedAt: Long, var sequence: Int = 0)
    private val groups = LinkedHashMap<String, LinkedHashMap<String, Window>>()

    @Synchronized fun note(group: String, messageId: String) {
        if (group.isBlank() || messageId.isBlank()) return
        val now = clock()
        prune(now)
        val messages = groups.getOrPut(group) { LinkedHashMap() }
        if (!messages.containsKey(messageId)) messages[messageId] = Window(now)
        while (messages.size > maxMessagesPerGroup.coerceAtLeast(1)) messages.remove(messages.keys.first())
        while (groups.size > maxGroups.coerceAtLeast(1)) groups.remove(groups.keys.first())
    }

    @Synchronized fun take(group: String, maximum: Int, messageId: String? = null): Pair<String, Int>? {
        val now = clock()
        val messages = groups[group] ?: return null
        val quota = maximum.coerceIn(1, 50)
        val candidate = if (messageId == null) {
            messages.entries.toList().asReversed().firstOrNull { (_, v) -> now - v.arrivedAt < TTL_MS && v.sequence < quota }
        } else messages.entries.firstOrNull { (k, v) -> k == messageId && now - v.arrivedAt < TTL_MS && v.sequence < quota }
        if (candidate == null) { prune(now); return null }
        return candidate.key to ++candidate.value.sequence
    }

    @Synchronized fun clear() { groups.clear() }

    private fun prune(now: Long) {
        groups.values.forEach { it.entries.removeAll { (_, v) -> now - v.arrivedAt >= TTL_MS } }
        groups.entries.removeAll { it.value.isEmpty() }
    }
}
