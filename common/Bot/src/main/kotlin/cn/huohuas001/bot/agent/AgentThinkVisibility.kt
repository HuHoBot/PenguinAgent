package cn.huohuas001.bot.agent

/**
 * 思考过程的展示方式。
 *
 * 与「是否开启深度思考」是两件事：深度思考决定要不要向接口附加思考参数，
 * 展示方式决定模型返回的思考内容往哪儿送——只写日志、只发 QQ 群，或者两边都要。
 */
enum class AgentThinkVisibility(
    /** 配置文件里使用的值 */
    val key: String,
    /** 群命令里使用的写法 */
    val label: String
) {
    /** 只写日志，不在群里发（默认，避免刷屏） */
    LOG("log", "日志"),

    /** 只发到 QQ 群 */
    GROUP("group", "群"),

    /** 日志与群都发 */
    BOTH("both", "都发");

    companion object {
        /** 解析配置值或群里的写法；无法识别时返回 null。 */
        fun from(value: String?): AgentThinkVisibility? {
            val text = value?.trim()?.lowercase().orEmpty()
            if (text.isEmpty()) return null
            return entries.firstOrNull {
                it.key == text || it.label == value?.trim() || it.name.lowercase() == text
            }
        }
    }
}
