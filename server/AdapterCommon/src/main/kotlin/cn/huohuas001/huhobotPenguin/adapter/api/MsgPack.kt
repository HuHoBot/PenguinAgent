package cn.huohuas001.huhobotPenguin.adapter.api

/**
 * QQ 群消息的跨平台不可变快照。
 *
 * 平台事件只暴露该稳定结构，不直接暴露 QQ SDK 的可变事件对象。
 */
data class MsgPack(
    val messageId: String,
    val groupOpenId: String,
    val groupId: String?,
    val sender: Sender,
    val content: String,
    val rawContent: String,
    val timestamp: String?,
    val messageSequence: Int = 0,
    val commandKey: String? = null,
    val commandArguments: String? = null,
    val mentions: List<Mention> = emptyList(),
    val attachments: List<Attachment> = emptyList()
) {
    data class Sender(
        val id: String?,
        val openId: String?,
        val username: String,
        val role: String?
    )

    data class Mention(
        val id: String?,
        val openId: String?,
        val username: String,
        val role: String?
    )

    data class Attachment(
        val id: String?,
        val filename: String?,
        val url: String?,
        val contentType: String?,
        val size: Int?,
        val width: Int?,
        val height: Int?,
        val asrReferText: String?
    )
}
