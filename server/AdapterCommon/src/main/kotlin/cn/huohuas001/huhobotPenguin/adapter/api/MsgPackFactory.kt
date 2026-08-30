package cn.huohuas001.huhobotPenguin.adapter.api

import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 将 QQ SDK 群消息转换为供平台事件和第三方插件使用的稳定快照。 */
fun GroupMessageEvent.toMsgPack(messageSequence: Int): MsgPack {
    val rawMessage = rawMessage
    val senderContact = getSender()
    return MsgPack(
        messageId = rawMessage.id.orEmpty(),
        groupOpenId = groupOpenId,
        groupId = groupId,
        sender = MsgPack.Sender(
            id = senderContact?.id,
            openId = senderContact?.openid,
            username = senderContact?.username ?: "unknown",
            role = senderContact?.role
        ),
        content = rawMessage.content.orEmpty(),
        rawContent = rawMessage.toString0(),
        timestamp = rawMessage.timestamp,
        messageSequence = messageSequence,
        mentions = rawMessage.mentions.orEmpty().map { user ->
            MsgPack.Mention(
                id = user.id,
                openId = null,
                username = user.username ?: "unknown",
                role = null
            )
        },
        attachments = rawMessage.attachments.orEmpty().map { attachment ->
            MsgPack.Attachment(
                id = attachment.id,
                filename = attachment.filename,
                url = attachment.url,
                contentType = attachment.content_type,
                size = attachment.size,
                width = attachment.width,
                height = attachment.height,
                asrReferText = null
            )
        }
    )
}

/** 返回带自定义命令键和参数的消息快照。 */
fun MsgPack.withCommand(rawInvocation: String): MsgPack {
    val invocation = rawInvocation
        .replace(Regex("<@!?[^>]+>"), "")
        .trim()
        .removePrefix("/")
        .trim()
    val parts = invocation.split(Regex("\\s+"), limit = 2)
    return copy(
        commandKey = parts.firstOrNull()?.takeIf(String::isNotBlank),
        commandArguments = parts.getOrNull(1).orEmpty()
    )
}
