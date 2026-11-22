package agent.ai

import agent.QClient

import agent.HuHoBot
import agent.provider.BotShared
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
        val config = config()
        if (config == null) {
            plugin.log_warning("聊天 AI 已收到消息但配置不完整（chat-ai.enabled/base-url/api-key），本次未处理")
            return false
        }
        var text = content.trim()
        val trigger = plugin.getChatAiTrigger().trim()
        if (trigger.isNotEmpty()) {
            if (!text.startsWith(trigger, true)) return false
            text = text.substring(trigger.length).trim()
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
            for (step in 0 until 5) {
                val result = try {
                    client.chat(prepare(messages, plugin), tools)
                } catch (error: Throwable) {
                    plugin.log_error("聊天 AI 调用失败: ${error.message}")
                    reply0 = null
                    break
                }
                val calls = result.toolCalls
                if (calls == null || calls.isEmpty()) {
                    reply0 = result.content?.trim()?.takeIf(String::isNotEmpty)
                    break
                }
                messages.add(assistantMessage(result.content, calls))
                for (i in 0 until calls.size) {
                    val call = calls.getJSONObject(i) ?: continue
                    val function = call.getJSONObject("function") ?: continue
                    val name = function.getString("name") ?: continue
                    val args = AgentTools.parseArguments(function.getString("arguments"))
                    val output = executeChatTool(plugin, config, name, args, groupOpenId)
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

    /** 指定群当前记录条数（供状态命令）。 */
    fun sizeOf(groupOpenId: String): Int = sessions[groupOpenId]?.size ?: 0

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
        if (allowed.isEmpty() && config.search == null && !agent.provider.BotShared.getPluginOrNull().let {
                it != null && it.isTtsEnabled() && TtsManager.anyModelAvailable()
            }
        ) return null
        val tools = JSONArray()
        if (config.search != null && (allowed.isEmpty() || "web_search" in allowed)) {
            tools.add(AgentTools.webSearchTool())
        }
        // 只读信息查询：聊天 AI 不具备控制服务器的能力
        if (allowed.isEmpty() || "get_server_status" in allowed) tools.add(AgentTools.serverStatusTool())
        if (allowed.isEmpty() || "get_online_players" in allowed) tools.add(AgentTools.onlineListTool())
        if (allowed.isEmpty() || "recent_group_chat" in allowed) tools.add(groupChatRecentTool())
        if (allowed.isEmpty() || "search_group_chat" in allowed) tools.add(groupChatSearchTool())
        // TTS 工具：启用且有任一模型 ID 时才暴露给 AI
        val plugin0 = agent.provider.BotShared.getPluginOrNull()
        if (plugin0 != null && plugin0.isTtsEnabled()) {
            TtsManager.toolDescription()?.let { desc ->
                tools.add(TtsManager.speakTool(desc))
            }
        }
        return if (tools.isEmpty()) null else tools
    }

    private fun groupChatRecentTool(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", "recent_group_chat")
            put("description", "读取本群最近的内存聊天记录（最多 50 条，时间倒序）。只读，不发送任何消息。")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("limit", JSONObject().apply {
                        put("type", "integer"); put("minimum", 1); put("maximum", 50)
                    })
                })
            })
        })
    }

    private fun groupChatSearchTool(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", "search_group_chat")
            put("description", "按关键词搜索本群最近的内存聊天记录（最多 50 条，时间倒序）。只读。")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("keyword", JSONObject().apply { put("type", "string"); put("maxLength", 50) })
                    put("limit", JSONObject().apply { put("type", "integer"); put("minimum", 1); put("maximum", 50) })
                })
                put("required", JSONArray().apply { add("keyword") })
            })
        })
    }

    private fun renderChatLog(entries: List<agent.chatlog.GroupChatLog.Entry>): String =
        if (entries.isEmpty()) "暂无聊天记录（QQ 未开启全量消息时只能看到被 @ 的消息）。"
        else entries.joinToString("\n") { "${it.name}：${it.text}" }

    /** 聊天 AI 的工具执行（只开放只读能力，绝不执行服务器命令）。 */
    private fun executeChatTool(plugin: HuHoBot, config: ChatAiConfig, name: String, args: JSONObject, groupOpenId: String): String =
        when (name) {
            "recent_group_chat" -> renderChatLog(
                agent.chatlog.GroupChatLog.recent(groupOpenId, args.getInteger("limit") ?: 20)
            )
            "search_group_chat" -> renderChatLog(
                agent.chatlog.GroupChatLog.search(
                    groupOpenId, args.getString("keyword").orEmpty(),
                    args.getInteger("limit") ?: 20
                )
            )
            "web_search" -> {
                val search = config.search
                if (search == null) "未启用搜索"
                else SearchProvider.summarize(SearchProvider.search(search, args.getString("query").orEmpty()))
            }
            "get_server_status" -> AgentTools.getServerStatusText(plugin)
            "get_online_players" -> {
                val online = plugin.getOnlineList()
                "当前在线 ${online.size} 人：${online.joinToString("、").ifBlank { "无" }}"
            }
            TtsManager.TOOL_SPEAK_TTS -> {
                val result = TtsManager.speak(
                    sessionKey = groupOpenId,
                    mode = args.getString("mode").orEmpty(),
                    text = args.getString("text").orEmpty(),
                    voice = args.getString("voice"),
                    description = args.getString("description"),
                    voiceFile = args.getString("voice_file"),
                    style = args.getString("style")
                )
                if (!result.ok) result.message
                else if (QClient.sendGroupVoice(groupOpenId, result.url)) "语音已发送到 QQ 群"
                else "语音已合成但发送失败：请检查 tts.public-base-url 是否公网可达以及 QQ 富媒体权限"
            }
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
