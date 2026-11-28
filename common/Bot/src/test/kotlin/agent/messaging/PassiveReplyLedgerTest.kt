package agent.messaging

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PassiveReplyLedgerTest {
    @Test fun groupWindowLastsOneHourFromArrivalNotFromLastReply() {
        var now = 0L
        val replies = PassiveReplyLedger(clock = { now })
        replies.note("g", "m")
        now = 59 * 60000L
        assertEquals("m" to 1, replies.take("g", 5))
        now = 60 * 60000L
        assertNull(replies.take("g", 5))
    }
    @Test fun duplicateDeliveryCannotResetQuotaOrRenewTheWindow() {
        var now = 0L
        val replies = PassiveReplyLedger(clock = { now })
        replies.note("g", "m")
        repeat(5) { assertEquals("m" to it + 1, replies.take("g", 5)) }
        now += 1000
        replies.note("g", "m")
        assertNull(replies.take("g", 5))
        assertEquals("m" to 6, replies.take("g", 50))
        replies.note("other", "m")
        assertEquals("m" to 1, replies.take("other", 5))
    }
    @Test fun enhancedQuotaIsFiftyPerMessageAndConcurrentReservationsNeverCollide() {
        val replies = PassiveReplyLedger()
        replies.note("g", "m")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..100).map { pool.submit<Pair<String,Int>?> { replies.take("g", 50) } }.mapNotNull { it.get(5,TimeUnit.SECONDS) }
            assertEquals((1..50).toSet(), results.map { it.second }.toSet())
            assertEquals(50, results.size)
        } finally { pool.shutdownNow() }
    }
    @Test fun specificRepliesShareBudgetWithUnsolicitedMessagesWithoutCrossGroupLeakage() {
        val replies = PassiveReplyLedger()
        replies.note("g", "a")
        replies.note("g", "b")
        assertEquals("a" to 1, replies.take("g", 5, "a"))
        assertEquals("b" to 1, replies.take("g", 5))
        assertEquals("a" to 2, replies.take("g", 5, "a"))
        assertNull(replies.take("other", 50, "a"))
        assertNull(replies.take("g", 5, "unknown"))
    }
}


class GroupChatLogTest {
    @Test fun recentAndSearchAreBoundedNewestFirstAndMemoryOnly() {
        agent.chatlog.GroupChatLog.clear()
        repeat(5) { agent.chatlog.GroupChatLog.record("g", "P$it", "message $it") }
        agent.chatlog.GroupChatLog.record("g", "Astra", "帮我找钻石")
        val recent = agent.chatlog.GroupChatLog.recent("g", 3)
        assertEquals(3, recent.size)
        assertEquals("Astra", recent.first().name)
        val hit = agent.chatlog.GroupChatLog.search("g", "钻石", 10)
        assertEquals(1, hit.size)
        assertTrue(agent.chatlog.GroupChatLog.search("g", "不存在", 10).isEmpty())
        assertTrue(agent.chatlog.GroupChatLog.recent("other", 10).isEmpty())
        agent.chatlog.GroupChatLog.clear()
    }
}
