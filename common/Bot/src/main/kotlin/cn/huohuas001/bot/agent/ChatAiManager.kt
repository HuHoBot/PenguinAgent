package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.provider.BotShared
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 群员聊天 AI —— 与工作 Agent 完全隔离的第二套 AI。
 *
 * 隔离边界：
 * - 独立 API（base-url / api-key / model）与思考参数；
 * - 独立系统提示词与工具集；
 * - 独立上下文：**同一群内所有群员共享一条全局聊天记录**（像群聊本身一样），
 *   **不同群之间完全隔离**；上限 getChatAiMaxContext 条 + 可选摘要压缩。
 * - 聊天记录是纯会话态（内存），重启清零；不落盘以控制存储与隐私面。
 *
 * 触发：公域群机器人只会收到 @机器人 的消息。命令消息（/开头）先行分发，
 * 落到这里的都是"非命令"内容 —— 配置了 trigger 前缀时要求消息以前缀开头。
 */
object ChatAiManager {

    private const val DEFAULT_PROMPT =
        "你是一个 Minecraft 服务器 QQ 群里的聊天助手，回答要简洁友好，默认使用中文。" +
            "服务器相关问题（在线情况、玩法）可以用提供的工具查询；其余话题正常闲聊。"

    /** 群 → 会话（全局共享记录；不同群各一份，互相隔离）。 */
    private val sessions = ConcurrentHashMap<String, MutableList<JSONObject>>()

    /** 正在处理的群（同一群串行，避免共享列表被并发改写）。 */
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** 收到一条群员消息（@机器人、非命令）。reply 用于回复（被动回复，带事件上下文）。返回是否由聊天 AI 处理。 */
    fun handle(
        plugin: HuHoBot,
        groupOpenId: String,
        senderOpenId: String,
        senderName: String,
        content: String,
        reply: (String) -> Unit
    ): Boolean {
        val config = config() ?: return false
        var text = content.trim()
        val trigger = plugin.getChatAiTrigger().trim()
        if (trigger.isNotEmpty()) {
            if (!text.startsWith(trigger)) return false
            text = text.removePrefix(trigger).trim()
        }
        if (text.isEmpty()) text = "你好"
        if (!running.add(groupOpenId)) {
            reply("上一条还在思考中，稍等一下再问我…")
            return true
        }
        try {
            val messages = sessions.getOrPut(groupOpenId) { mutableListOf() }
            messages.add(userMessage("$senderName：$text"))
            trimByCount(messages, plugin.getChatAiMaxContext().coerceIn(4, 100))

            val client = buildClient(config)
            val tools = buildTools(plugin, config)
            // 聊天 AI 走轻量循环：最多 5 步（搜索/查询工具 + 回答）
            var reply0: String? = null
            repeat(5) {
                val result = try {
                    client.chat(prepare(messages, plugin), tools)
                } catch (error: Throwable) {
                    plugin.log_error("聊天 AI 调用失败: ${error.message}")
                    reply0 = null
                    return@repeat
                }
                val calls = result.toolCalls
                if (calls == null || calls.isEmpty()) {
                    reply0 = result.content?.trim()?.takeIf(String::isNotEmpty)
                    return@repeat
                }
                messages.add(assistantMessage(result.content, calls))
                for (i in 0 until calls.size) {
                    val call = calls.getJSONObject(i) ?: continue
                    val function = call.getJSONObject("function") ?: continue
                    val name = function.getString("name") ?: continue
                    val args = AgentTools.parseArguments(function.getString("arguments"))
                    val output = executeChatTool(plugin, config, name, args)
                    messages.add(toolMessage(call.getString("id") ?: "call_$i", output))
                }
            }
            if (reply0 == null) {
                reply("聊天助手暂时没有回复，稍后再试试吧。")
                return true
            }
            messages.add(assistantMessage(reply0, null))
            reply(reply0!!)
            return true
        } finally {
            running.remove(groupOpenId)
        }
    }

    /** /聊天清空：清除指定群的共享上下文。 */
    fun clear(groupOpenId: String) {
        sessions.remove(groupOpenId)
    }

    /** 聊天 AI 上下文压缩（同群共享记录压缩成摘要）。 */
    fun compress(plugin: HuHoBot, groupOpenId: String): String {
        val config = config() ?: return "聊天 AI 未启用"
        val messages = sessions[groupOpenId]
        if (messages == null || messages.size <= 2) return "当前群没有多少聊天记录，不需要压缩"
        val system = if (messages.first().getString("role") == "system") listOf(messages.first()) else emptyList()
        val body = if (system.isEmpty()) messages.toList() else messages.subList(1, messages.size)
        val keep = plugin.getChatAiCompressKeep().coerceIn(2, body.size)
        val cut = AgentContextManager.tailStartIndex(body, keep)
        val middle = body.subList(0, cut)
        if (middle.isEmpty()) return "最近的聊天就是全部记录，不需要压缩"
        val client = buildClient(config)
        val request = listOf(
            JSONObject().apply {
                put("role", "system"); put("content", "请把下面的群聊记录压缩成一段摘要，保留关键话题与结论，300 字以内。")
            },
            JSONObject().apply { put("role", "user"); put("content", AgentContextManager.renderTranscript(middle)) }
        )
        val summary = try {
            client.chat(request, null).content?.trim().orEmpty()
        } catch (error: Throwable) {
            return "压缩失败：${error.message ?: "未知错误"}"
        }
        if (summary.isEmpty()) return "压缩失败：模型返回了空摘要"
        val summaryMessage = userMessage("【此前聊天记录的摘要】\n$summary")
        val rebuilt = system + listOf(summaryMessage) + body.subList(cut, body.size)
        messages.clear()
        messages.addAll(rebuilt)
        return "✅ 聊天记录已压缩（${middle.size} 条 → 1 条摘要，保留最近 $keep 条）"
    }

    // ── 内部 ──

    private data class ChatAiConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val think: Boolean,
        val thinkLevel: String,
        val thinkParam: String,
        val systemPrompt: String,
        val allowedTools: List<String>,
        val search: SearchProvider.Config?
    )

    private fun config(): ChatAiConfig? {
        val plugin = BotShared.getPluginOrNull() ?: return null
        if (!plugin.getChatAiEnabled()) return null
        val baseUrl = plugin.getChatAiBaseUrl()?.takeIf(String::isNotBlank) ?: return null
        val apiKey = plugin.getChatAiApiKey()?.takeIf(String::isNotBlank) ?: return null
        val model = plugin.getChatAiModel()?.takeIf(String::isNotBlank) ?: "gpt-4o-mini"
        val provider = plugin.getChatAiSearchProvider().trim()
        val search = if (provider.isNotEmpty() && provider != "none") {
            SearchProvider.Config(
                provider = provider,
                apiUrl = plugin.getChatAiSearchApiUrl(),
                apiKey = plugin.getChatAiSearchApiKey(),
                resultCount = plugin.getSearchResultCount().coerceIn(1, 10)
            )
        } else null
        return ChatAiConfig(
            baseUrl = baseUrl, apiKey = apiKey, model = model,
            think = plugin.getChatAiThink(),
            thinkLevel = plugin.getChatAiThinkLevel(),
            thinkParam = plugin.getChatAiThinkParam(),
            systemPrompt = plugin.getChatAiSystemPrompt().trim(),
            allowedTools = plugin.getChatAiTools(),
            search = search
        )
    }

    private fun buildClient(config: ChatAiConfig): AgentApiClient =
        if (config.think) AgentApiClient(config.baseUrl, config.apiKey, config.model, config.thinkLevel, config.thinkParam)
        else AgentApiClient(config.baseUrl, config.apiKey, config.model)

    private fun buildTools(plugin: HuHoBot, config: ChatAiConfig): JSONArray? {
        val allowed = config.allowedTools
        if (allowed.isEmpty() && config.search == null) return null
        val tools = JSONArray()
        if (config.search != null && (allowed.isEmpty() || "web_search" in allowed)) {
            tools.add(AgentTools.webSearchTool())
        }
        // 预留：allowedTools 里显式列出的其它只读工具（get_server_status 等）
        return if (tools.isEmpty()) null else tools
    }

    /** 聊天 AI 的工具执行（只开放只读能力，绝不执行服务器命令）。 */
    private fun executeChatTool(plugin: HuHoBot, config: ChatAiConfig, name: String, args: JSONObject): String =
        when (name) {
            "web_search" -> {
                val search = config.search
                if (search == null) "未启用搜索"
                else SearchProvider.summarize(SearchProvider.search(search, args.getString("query").orEmpty()))
            }
            "get_server_status" -> AgentTools.getServerStatusText(plugin)
            else -> "未知或未启用的工具：$name"
        }

    private fun prepare(messages: MutableList<JSONObject>, plugin: HuHoBot): List<JSONObject> {
        val systemPrompt = plugin.getChatAiSystemPrompt().trim().ifEmpty { DEFAULT_PROMPT }
        val out = mutableListOf<JSONObject>()
        if (messages.isEmpty() || messages.first().getString("role") != "system") {
            out.add(systemMessage(systemPrompt))
        }
        out.addAll(AgentContextManager.dropOrphanToolMessages(messages))
        return out
    }

    private fun trimByCount(messages: MutableList<JSONObject>, maxMessages: Int) {
        val trimmed = AgentContextManager.trimByCount(messages, maxMessages + 1)
        messages.clear()
        messages.addAll(trimmed)
    }

    private fun userMessage(content: String): JSONObject =
        JSONObject().apply { put("role", "user"); put("content", content) }

    private fun systemMessage(content: String): JSONObject =
        JSONObject().apply { put("role", "system"); put("content", content) }

    private fun assistantMessage(content: String?, toolCalls: JSONArray?): JSONObject =
        JSONObject().apply {
            put("role", "assistant")
            put("content", content)
            if (toolCalls != null) put("tool_calls", toolCalls)
        }

    private fun toolMessage(toolCallId: String, content: String): JSONObject =
        JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("content", content)
        }
}
