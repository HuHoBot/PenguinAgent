package cn.huohuas001.bot.agent

/**
 * AI Agent 各类消息的 QQ 群 Markdown 美化。
 *
 * QQ 群自定义 Markdown 支持标题、加粗、行内代码、代码块、引用、列表等语法，
 * 这里统一生成 [获取] / [执行] 两类卡片样式。
 */
object AgentMessageFormatter {

    private const val HEADER = "## 🤖 AI 助手"

    /** AI 的思考过程。 */
    fun thinking(content: String): String = block("思考", content)

    /** AI 对用户/工具结果的回复。 */
    fun reply(content: String): String = block("回复", content)

    /** AI 请求执行命令的审批卡片（带同意/拒绝按钮）。 */
    fun approvalCard(command: String): String {
        val safeCommand = escapeCommand(command)
        return buildString {
            appendLine(HEADER)
            appendLine()
            appendLine("**[执行]** 执行命令：`$safeCommand`")
            appendLine()
            appendLine("> 该操作需要管理员审批，请点击下方按钮确认。")
        }.trimEnd()
    }

    /** 命令已自动执行/审批通过后的执行结果卡片。 */
    fun executedCard(command: String, output: String): String {
        val safeCommand = escapeCommand(command)
        return buildString {
            appendLine(HEADER)
            appendLine()
            appendLine("**[执行]** 已执行命令：`$safeCommand`")
            if (output.isNotBlank()) {
                appendLine()
                appendLine("**输出：**")
                appendLine()
                appendLine(codeBlock(output))
            }
        }.trimEnd()
    }

    /** 获取类操作的详情卡片。 */
    fun fetchCard(title: String, content: String): String {
        return buildString {
            appendLine(HEADER)
            appendLine()
            appendLine("**[获取]** $title")
            if (content.isNotBlank()) {
                appendLine()
                appendLine(codeBlock(content))
            }
        }.trimEnd()
    }

    /** 审批结果通知。 */
    fun approvedNotice(byWho: String): String = block(
        "审批",
        "命令已获管理员 **$byWho** 批准，正在执行。"
    )

    /** 审批被拒绝通知。 */
    fun rejectedNotice(byWho: String): String = block(
        "审批",
        "命令被管理员 **$byWho** 拒绝，已停止执行。"
    )

    /** 无审批权限提示。 */
    fun noPermissionNotice(): String = block(
        "审批",
        "只有本群管理员或群主才能进行审批操作。"
    )

    /** 任务结束提示。 */
    fun taskFinished(): String = block("完成", "本次任务已处理完毕。")

    /** AI 接口出错的提示。 */
    fun error(message: String): String = block("错误", "AI 调用失败：$message")

    /** 正在加载 SKILL 的提示。 */
    fun skillLoading(skillName: String): String = block("SKILL", "正在加载 **$skillName** SKILL…")

    private fun block(tag: String, content: String): String = buildString {
        appendLine(HEADER)
        appendLine()
        appendLine("**[$tag]** ${content.trim()}")
    }.trimEnd()

    private fun escapeCommand(command: String): String =
        command.trim().replace("`", "'").take(200)

    private fun codeBlock(content: String): String {
        val escaped = content.trim().replace("```", "` ` `")
        return "```\n$escaped\n```"
    }
}
