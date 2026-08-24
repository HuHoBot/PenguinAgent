package cn.huohuas001.bot.tools

import io.github.kloping.qqbot.entities.qqpd.data.Emoji
import io.github.kloping.qqbot.utils.BaseUtils

/** 将 QQ 原始消息中的 face 标签转换为便于阅读的表情描述。 */
object FaceEmojiParser {
    private val facePattern = BaseUtils.EMOJI_V2.toRegex()

    fun parse(content: String): String = facePattern.replace(content) { match ->
        val attributes = runCatching {
            BaseUtils.parseAngleBracketsEmoji(match.value)
        }.getOrNull() ?: return@replace match.value

        val faceType = attributes["faceType"]?.toString()?.toIntOrNull()
            ?: return@replace match.value

        when (faceType) {
            1 -> {
                val faceId = attributes["faceId"]?.toString()?.toIntOrNull()
                    ?: return@replace match.value
                val emoji = Emoji.VALUES.firstOrNull {
                    it.type == faceType && it.id == faceId
                }
                emoji?.let { "[表情:${it.text}]" } ?: "[表情]"
            }

            2 -> "[EMOJI]"
            6 -> "[动画表情]"
            else -> "[表情]"
        }
    }
}
