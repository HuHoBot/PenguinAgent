package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

/**
 * Agent 上下文管理：token 估算、按边界安全裁剪、摘要压缩、删除最早的消息。
 *
 * 这里最要紧的是**边界安全**：对话里存在 `assistant`(带 tool_calls) 与 `tool` 的配对关系，
 * 任何从中间切断的裁剪都会留下没有归属的 tool 消息，接口会直接 400。
 * 所以所有裁剪都先跳过开头的孤立 tool 消息，收尾时再把 tool 消息所属的 assistant 一起带上。
 */
object AgentContextManager {

    /** 单条消息的固定开销（role / 分隔符等），用于粗略估算。 */
    private const val MESSAGE_OVERHEAD_TOKENS = 4

    /** 压缩结果里给摘要消息加的标记，方便后续轮次识别。 */
    const val SUMMARY_MARK = "【以下是此前对话的压缩摘要，接手后请以此为准继续工作】"

    /** 摘要消息的固定标记前缀。 */
    private const val SUMMARY_PREFIX = SUMMARY_MARK

    /**
     * 粗略估算一段文本的 token 数。
     *
     * 没有分词器可用，按经验值估算：中日韩字符约 1 token，其余约 4 字符 1 token。
     * 估算只用来决定"要不要压缩"和给压缩器一个长度目标，不追求精确。
     */
    fun estimateTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var cjk = 0
        var other = 0
        for (char in text) {
            if (char.code in 0x2E80..0x9FFF || char.code in 0xF900..0xFAFF || char.code in 0xFF00..0xFFEF) cjk++ else other++
        }
        return cjk + other / 4
    }

    /** 估算一组消息的 token 数（含每条消息的固定开销）。 */
    fun estimateTokens(messages: List<JSONObject>): Int =
        messages.sumOf { estimateTokens(it.getString("content")) + MESSAGE_OVERHEAD_TOKENS }

    /**
     * 返回去掉开头孤立 tool 消息后的**新列表**。
     *
     * tool 消息必须紧跟在带 tool_calls 的 assistant 消息之后，开头出现 tool 说明它对应的
     * assistant 已经被裁掉了，这种消息留着必然被接口拒绝。
     *
     * 刻意返回新列表而不是 subList 视图：调用方常常紧接着 clear() 原列表再回填，
     * 返回视图的话原列表一清空，视图就跟着空了。
     */
    fun dropOrphanToolMessages(messages: List<JSONObject>): List<JSONObject> {
        var index = 0
        while (index < messages.size && messages[index].getString("role") == "tool") index++
        return if (index == 0) messages.toList() else messages.subList(index, messages.size).toList()
    }

    /**
     * 算出"保留最近 [keep] 条"时，尾部应该从哪里开始。
     *
     * 从末尾往前数，若落在 tool 消息上就继续往前扩，把对应的 assistant 消息一起带上，
     * 保证不会把一组 tool_calls / tool 结果切成两半。
     */
    fun tailStartIndex(messages: List<JSONObject>, keep: Int): Int {
        var index = (messages.size - keep).coerceAtLeast(0)
        while (index > 0 && messages[index].getString("role") == "tool") index--
        return index
    }

    /**
     * 按条数裁剪上下文：保留首条 system 与最近的消息，且不拆散 tool 调用组。
     *
     * 取代原先直接 takeLast 的做法——那样会留下孤立的 tool 消息导致请求被拒。
     *
     * @return 裁剪后的消息列表；system 消息不存在时也照样能工作
     */
    fun trimByCount(messages: List<JSONObject>, maxMessages: Int): List<JSONObject> {
        val cleaned = dropOrphanToolMessages(messages)
        if (cleaned.size <= maxMessages) return cleaned
        val system = if (cleaned.first().getString("role") == "system") listOf(cleaned.first()) else emptyList()
        val rest = if (system.isEmpty()) cleaned else cleaned.subList(1, cleaned.size)
        val room = (maxMessages - system.size).coerceAtLeast(1)
        val start = tailStartIndex(rest, room)
        return system + rest.subList(start, rest.size)
    }

    /**
     * 删除最早的 [count] 条消息（不动首条 system）。
     *
     * @return 实际删除条数；不足时按实际可删的条数返回
     */
    fun dropOldest(messages: MutableList<JSONObject>, count: Int): Int {
        val cleaned = dropOrphanToolMessages(messages)
        messages.clear()
        messages.addAll(cleaned)
        val offset = if (messages.isNotEmpty() && messages.first().getString("role") == "system") 1 else 0
        val removable = (messages.size - offset).coerceAtLeast(0)
        val target = count.coerceIn(0, removable)
        if (target > 0) {
            messages.subList(offset, offset + target).clear()
            // 删完可能又露出开头的孤立 tool 消息
            val cleanedAgain = dropOrphanToolMessages(messages)
            messages.clear()
            messages.addAll(cleanedAgain)
        }
        return target
    }

    /** 把消息列表转写成人和模型都读得懂的转写文本，供压缩器使用。 */
    fun renderTranscript(messages: List<JSONObject>): String {
        val sb = StringBuilder()
        messages.forEachIndexed { index, message ->
            val role = message.getString("role") ?: "unknown"
            val toolCalls = message.getJSONArray("tool_calls")
            if (role == "assistant" && toolCalls != null && toolCalls.isNotEmpty()) {
                sb.append("[").append(index + 1).append("] assistant 请求调用工具：")
                val names = (0 until toolCalls.size).mapNotNull { i ->
                    val call = toolCalls.getJSONObject(i) ?: return@mapNotNull null
                    val function = call.getJSONObject("function") ?: return@mapNotNull null
                    val name = function.getString("name") ?: return@mapNotNull null
                    val arguments = function.getString("arguments").orEmpty().take(400)
                    "$name $arguments"
                }
                sb.append(names.joinToString("；"))
                sb.appendLine()
                return@forEachIndexed
            }
            val label = when (role) {
                "system" -> "系统设定"
                "user" -> "用户"
                "assistant" -> "助手"
                "tool" -> "工具结果"
                else -> role
            }
            val content = message.getString("content").orEmpty().take(MAX_TRANSCRIPT_CHARS)
            sb.append("[").append(index + 1).append("] ").append(label).append("：").appendLine(content)
        }
        return sb.toString().trim()
    }

    /** 单条消息写进转写文本时的长度上限，避免一条超长日志把压缩请求本身撑爆。 */
    private const val MAX_TRANSCRIPT_CHARS = 4000

    /**
     * 压缩会话上下文：把中间的历史换成一条摘要，保留首条 system 与最近若干条消息。
     *
     * 全程在调用方的异步线程里跑，完成后直接改写 [session] 的消息列表。
     *
     * @param onResult 压缩结束后回调，用于把结果发回群里
     */
    fun compress(
        plugin: HuHoBot,
        session: AgentSession,
        config: AgentConfig,
        onResult: (String) -> Unit
    ) {
        val before = dropOrphanToolMessages(session.messages)
        if (before.size <= 2) {
            onResult("上下文很短（只有 ${before.size} 条消息），暂时不需要压缩")
            return
        }

        val system = if (before.first().getString("role") == "system") listOf(before.first()) else emptyList()
        val body = if (system.isEmpty()) before else before.subList(1, before.size)
        val keep = config.context.compressKeep.coerceIn(1, (body.size - 1).coerceAtLeast(1))
        val cut = tailStartIndex(body, keep)
        val middle = body.subList(0, cut)
        if (middle.isEmpty()) {
            onResult("最近 $keep 条已是全部上下文，暂时不需要压缩")
            return
        }

        val target = config.context.compressTarget.coerceAtLeast(200)
        val beforeTokens = estimateTokens(before)
        val client = AgentApiClient(config.baseUrl, config.apiKey, config.model)
        val systemPrompt = JSONObject()
        systemPrompt.put("role", "system")
        systemPrompt.put("content", config.effectiveCompressPrompt())
        val transcript = JSONObject()
        transcript.put("role", "user")
        transcript.put("content", renderTranscript(middle))
        val request = listOf(systemPrompt, transcript)

        val summary = try {
            client.chat(request, null).content?.trim().orEmpty()
        } catch (error: Throwable) {
            plugin.log_error("Agent 上下文压缩失败: ${error.message}")
            onResult("压缩失败：${error.message ?: "未知错误"}")
            return
        }

        if (summary.isEmpty()) {
            plugin.log_error("Agent 上下文压缩失败: 模型返回了空摘要")
            onResult("压缩失败：模型返回了空摘要，原上下文未改动")
            return
        }

        val summaryMessage = JSONObject()
        summaryMessage.put("role", "user")
        summaryMessage.put("content", "$SUMMARY_PREFIX\n$summary")
        val rebuilt = system + summaryMessage + body.subList(cut, body.size)
        session.messages.clear()
        session.messages.addAll(rebuilt)

        val afterTokens = estimateTokens(rebuilt)
        session.lastPromptTokens = afterTokens
        plugin.log_info(
            "Agent 上下文已压缩：messages=${before.size}->${rebuilt.size}, " +
                "tokens≈${beforeTokens}->$afterTokens (target=$target)"
        )
        if (afterTokens > target) {
            plugin.log_info("Agent 压缩后仍超出目标 token 数（$afterTokens > $target），可调小保留条数或调大压缩目标")
        }
        onResult(
            "✅ 上下文已压缩\n" +
                "- 消息条数：${before.size} → ${rebuilt.size}（摘要 ${middle.size} 条 → 1 条，保留最近 $keep 条）\n" +
                "- 估算 token：$beforeTokens → $afterTokens（目标 $target）"
        )
    }

    /** 群消息里展示用的摘要卡片内容（与压缩结果一并发送）。 */
    fun summaryCard(summary: String): String {
        val clipped = if (summary.length <= MAX_MESSAGE_CHARS) summary
        else summary.take(MAX_MESSAGE_CHARS) + "\n…（摘要过长已截断，完整内容在会话里）"
        return AgentMessageFormatter.fetchCard("上下文已压缩，摘要如下", clipped)
    }

    private const val MAX_MESSAGE_CHARS = 3000

    /** 供日志与卡片复用的工具调用名清单（避免 tool_calls 原文过长）。 */
    fun describeToolCalls(toolCalls: JSONArray?): String {
        if (toolCalls == null || toolCalls.isEmpty()) return ""
        return (0 until toolCalls.size).mapNotNull { index ->
            val call = toolCalls.getJSONObject(index) ?: return@mapNotNull null
            call.getJSONObject("function")?.getString("name")
        }.joinToString("、")
    }
}
