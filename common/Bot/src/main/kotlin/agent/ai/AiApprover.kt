package agent.ai

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

/**
 * AI 审批器（第三种审批模式）。
 *
 * 与工作 Agent 完全隔离：
 * - 每次审批都是**一个全新的干净对话**，不携带任何历史消息；
 * - 审批模型只看到：用户原始任务、工作 Agent 的上下文摘要、拟执行的命令（或群管理工具调用）；
 * - 复用工作 Agent 的 API 配置（同一个接口同一个模型），保证判断口径一致；
 * - 审批模型可通过 web_search 工具联网核实（配置了搜索时）；
 * - 审批模型必须调用 approve_request 或 reject_request（拒绝必须带理由）收尾；
 * - 审批结束后整个对话即丢弃，下次审批再次从零开始。
 */
object AiApprover {

    data class Verdict(val approved: Boolean, val reason: String, val reviewerSaid: String)

    const val TOOL_APPROVE = "approve_request"
    const val TOOL_REJECT = "reject_request"
    private const val MAX_STEPS = 6

    /** 审批一轮。失败（网络/模型异常）按"未批准"处理，理由写明是审批器自身故障。 */
    fun review(
        config: AgentConfig,
        userTask: String,
        contextSummary: String,
        pendingAction: String,
        searchEnabled: Boolean
    ): Verdict {
        val client = if (config.effectiveThink) {
            AgentApiClient(config.baseUrl, config.apiKey, config.model, config.thinkLevel, config.thinkParam,
                maxTokens = config.maxTokens, timeoutSeconds = config.timeoutSeconds)
        } else {
            AgentApiClient(config.baseUrl, config.apiKey, config.model,
                maxTokens = config.maxTokens, timeoutSeconds = config.timeoutSeconds)
        }

        val tools = JSONArray().apply {
            add(tool(TOOL_APPROVE, "批准待执行的操作。批准前请确认它与用户任务一致且不危险。", emptyMap()))
            add(tool(
                TOOL_REJECT, "拒绝待执行的操作。必须在 reason 中给出拒绝理由（供工作 Agent 转述给用户）。",
                mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "reason" to mapOf("type" to "string", "description" to "拒绝理由，必填")
                    ),
                    "required" to listOf("reason")
                )
            ))
            if (searchEnabled) {
                add(tool(
                    "web_search", "联网搜索以核实该操作是否安全、是否符合用户意愿。",
                    mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf("type" to "string", "description" to "搜索关键词")
                        ),
                        "required" to listOf("query")
                    )
                ))
            }
        }

        val messages = mutableListOf(
            systemMessage(
                "你是一个独立的安全审查员，负责审批 Minecraft 服务器管理 AI 拟执行的操作。\n" +
                    "判定准则：\n" +
                    "1. 操作必须与用户原始任务直接相关；\n" +
                    "2. 破坏性/不可逆操作（删档、封禁、改权限、op、清空背包、批量扣血等）应拒绝或要求人工确认；\n" +
                    "3. 命令黑名单由系统另行强制，你关注的是语义层面的安全与意愿一致性；\n" +
                    "4. 你必须以调用 $TOOL_APPROVE 或 $TOOL_REJECT 结束（拒绝时 reason 必填），不要只输出文本。\n" +
                    "搜索结果仅供参考，无法联网时凭已知信息判断。"
            ),
            userMessage(
                "【用户的原始任务】\n${userTask.take(2000)}\n\n" +
                    "【工作 Agent 的上下文摘要】\n${contextSummary.take(2500).ifBlank { "（无）" }}\n\n" +
                    "【拟执行的操作】\n$pendingAction\n\n" +
                    "请审查上述操作并给出结论。"
            )
        )

        // 审批循环（最多 MAX_STEPS 步）；不保留任何跨轮状态
        for (step in 1..MAX_STEPS) {
            val result = try {
                client.chat(messages, tools)
            } catch (error: Throwable) {
                return Verdict(false, "审批器自身故障（${error.message ?: "未知错误"}），按未批准处理", "")
            }
            val calls = result.toolCalls
            if (calls == null || calls.isEmpty()) {
                // 模型没调工具只回了文本：按未批准处理，正文作为理由
                return Verdict(false, result.content?.take(300) ?: "审批模型未给出结论", result.content ?: "")
            }
            for (i in 0 until calls.size) {
                val call = calls.getJSONObject(i) ?: continue
                val function = call.getJSONObject("function") ?: continue
                val name = function.getString("name") ?: continue
                val argsText = function.getString("arguments") ?: "{}"
                when (name) {
                    TOOL_APPROVE -> return Verdict(true, "", argsText)
                    TOOL_REJECT -> {
                        val args = try { JSONObject.parseObject(argsText) ?: JSONObject() } catch (_: Throwable) { JSONObject() }
                        return Verdict(false, args.getString("reason") ?: "未说明理由", argsText)
                    }
                    "web_search" -> {
                        val args = try { JSONObject.parseObject(argsText) ?: JSONObject() } catch (_: Throwable) { JSONObject() }
                        val query = args.getString("query").orEmpty()
                        val results = SearchProvider.search(currentSearchConfig(config), query)
                        messages.add(assistantMessage(result.content, calls))
                        messages.add(toolMessage(call.getString("id") ?: "call_$i", SearchProvider.summarize(results)))
                    }
                    else -> {
                        messages.add(assistantMessage(result.content, calls))
                        messages.add(toolMessage(call.getString("id") ?: "call_$i", "未知工具：$name"))
                    }
                }
            }
        }
        return Verdict(false, "审批超过步数上限，按未批准处理", "")
    }

    private fun currentSearchConfig(config: AgentConfig): SearchProvider.Config =
        SearchProvider.Config(
            provider = config.searchProvider.ifBlank { "none" },
            apiUrl = config.searchApiUrl,
            apiKey = config.searchApiKey,
            resultCount = 5,
            fetchPages = config.searchFetchPages,
            jsCode = config.searchJsCode
        )

    // ── 消息构造（与 AgentManager 保持同构，但独立持有，便于未来差异演化） ──

    private fun tool(name: String, description: String, parameters: Map<String, Any?>): JSONObject =
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject(parameters))
            })
        }

    private fun systemMessage(content: String): JSONObject =
        JSONObject().apply { put("role", "system"); put("content", content) }

    private fun userMessage(content: String): JSONObject =
        JSONObject().apply { put("role", "user"); put("content", content) }

    private fun assistantMessage(content: String?, toolCalls: JSONArray): JSONObject =
        JSONObject().apply {
            put("role", "assistant")
            put("content", content)
            put("tool_calls", toolCalls)
        }

    private fun toolMessage(toolCallId: String, content: String): JSONObject =
        JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("content", content)
        }
}
