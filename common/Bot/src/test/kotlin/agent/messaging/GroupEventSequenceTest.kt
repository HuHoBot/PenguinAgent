package agent.messaging

import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage
import io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent
import kotlin.test.*

class GroupEventSequenceTest {
    @Test fun sdkImageRepliesAndHostRepliesShareOneMessageSequence() {
        val replies = PassiveReplyLedger()
        val meta = JSONObject.parseObject("{\"id\":\"m\",\"group_openid\":\"g\",\"author\":{\"member_openid\":\"u\",\"username\":\"fixture\"}}")
        val event = BaseGroupMessageEvent(RawMessage().setId("m").setContent("fixture"), meta, null)
        replies.note("g", "m")
        BaseGroupMessageEvent.setSequenceAllocator { e, _ ->
            replies.take("g", 50, e.rawMessage.id)?.second ?: error("window unavailable")
        }
        try {
            assertEquals(1, event.msgSeq)
            assertEquals("m" to 2, replies.take("g", 50))
            assertEquals(3, event.msgSeq)
            assertEquals("m" to 4, replies.take("g", 50))
            assertEquals(5, event.msgSeq)
        } finally { BaseGroupMessageEvent.setSequenceAllocator(null) }
    }
}
