package cn.huohuas001.bot.agent

/**
 * AI Agent 命令执行模式。
 *
 * @property value 配置文件中的原始字符串值
 */
enum class AgentCommandMode(val value: String) {
    /** 自动执行：AI 执行命令无需审批，只推送思考/输出/工具调用信息 */
    AUTO("auto"),

    /** 手动审批：AI 执行命令前必须由管理员在 QQ 按钮卡片上点击同意（默认） */
    MANUAL("manual");

    companion object {
        /** 从配置字符串解析，忽略大小写，解析失败返回 null */
        fun from(value: String?): AgentCommandMode? {
            if (value == null) return null
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
        }
    }
}
