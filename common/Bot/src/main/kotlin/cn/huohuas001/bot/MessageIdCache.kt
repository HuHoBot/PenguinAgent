package cn.huohuas001.bot

import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存每个群最近一次收到的消息 ID 和递增序号，
 * 用于主动发送时携带 msg_id 避免 40034105。
 *
 * QQ 被动回复规则：
 * - msg_id 来自 GROUP_AT_MESSAGE_CREATE 事件的 d.id，5 分钟内有效
 * - 同一 msg_id 最多回复 5 次，需用递增 msg_seq 区分
 */
object MessageIdCache {
    private data class Entry(
        val msgId: String,
        val msgSeq: Int,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 收到群消息时调用，更新该群的 msg_id 缓存。 */
    fun update(groupId: String, msgId: String?, msgSeq: Int) {
        if (msgId.isNullOrBlank()) return
        val existing = cache[groupId]
        if (existing != null && existing.msgId == msgId) {
            // 同一条消息，递增 seq
            cache[groupId] = Entry(msgId, maxOf(existing.msgSeq, msgSeq) + 1, System.currentTimeMillis())
        } else {
            cache[groupId] = Entry(msgId, msgSeq + 1, System.currentTimeMillis())
        }
    }

    /** 获取该群最近的 msg_id，超过 5 分钟返回 null。 */
    fun getMsgId(groupId: String): String? {
        val entry = cache[groupId] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > 5 * 60 * 1000) {
            cache.remove(groupId)
            return null
        }
        return entry.msgId
    }

    /** 获取并递增该群的 msg_seq（用于同一 msg_id 的多次回复）。 */
    fun nextMsgSeq(groupId: String): Int {
        val entry = cache[groupId] ?: return 1
        val next = entry.msgSeq + 1
        cache[groupId] = entry.copy(msgSeq = next)
        return next
    }
}
