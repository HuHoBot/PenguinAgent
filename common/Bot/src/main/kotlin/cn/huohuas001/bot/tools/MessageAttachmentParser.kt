package cn.huohuas001.bot.tools

/** 将 QQ 原始消息中的语音、图片、视频和文件标签转换为便于阅读的文本。 */
object MessageAttachmentParser {
    private val attachmentPattern = Regex("""\[(voice|pic|video|file):([^]]*)]""")

    fun parse(content: String): String = attachmentPattern.replace(content) { match ->
        val payload = match.groupValues[2]
        when (match.groupValues[1]) {
            "voice" -> {
                val text = payload.substringAfter('|', missingDelimiterValue = "").trim()
                if (text.isEmpty()) "[语音]" else "[语音:$text]"
            }

            "pic" -> "[图片]"
            "video" -> "[视频]"
            "file" -> "[文件:$payload]"
            else -> match.value
        }
    }
}
