package agent.ai

import agent.HuHoBot
import agent.events.commands.SensitiveFilter

/**
 * AI 内容守卫：双向违禁词过滤。
 *
 * - 入站（用户 → AI）：把违禁词替换成 *原词* 后再传给模型，模型能理解语境、
 *   也明确知道这些词已被平台屏蔽，不会在回答里复述。
 * - 出站（AI → 群）：模型返回的正文再做一次同样的检查，命中同样替换成 *原词*
 *   后才发送到 QQ 群。工具调用参数、代码块等追求准确的内容不做替换。
 *
 * 只用本地词表（含内置基础词），不触发 AI 复审，避免每条消息两次模型调用。
 */
object AiContentGuard {

    private fun words(plugin: HuHoBot): List<String> =
        runCatching { plugin.getSensitiveWords() }.getOrDefault(emptyList())

    private fun allowNone(plugin: HuHoBot): Boolean =
        runCatching { plugin.isModerationAllowNone() }.getOrDefault(false)

    /** 入站掩蔽：返回 *词* 化后的文本。 */
    fun maskInbound(plugin: HuHoBot, text: String): String {
        if (text.isBlank()) return text
        return runCatching {
            SensitiveFilter.maskForAi(text, words(plugin), allowNone(plugin)).first
        }.getOrDefault(text)
    }

    /**
     * 出站过滤：正文违禁词替换成 *原词*；``` 代码块内不做替换（保持命令/代码准确）。
     * 返回（过滤后文本, 命中词列表）。
     */
    fun filterOutbound(plugin: HuHoBot, text: String): Pair<String, List<String>> {
        if (text.isBlank()) return text to emptyList()
        return runCatching {
            val hits = SensitiveFilter.findMatches(text, words(plugin), allowNone(plugin))
            if (hits.isEmpty()) return text to emptyList()
            val masked = maskOutsideCodeBlocks(text, hits)
            masked to hits
        }.getOrDefault(text to emptyList())
    }

    /** 只过滤代码块之外的文本。 */
    private fun maskOutsideCodeBlocks(text: String, hits: List<String>): String {
        val parts = text.split(Regex("(?s)(```.*?```)"))
        val out = StringBuilder()
        for (part in parts) {
            if (part.startsWith("```")) out.append(part)
            else out.append(SensitiveFilter.maskWrapped(part, hits))
        }
        return out.toString()
    }
}
