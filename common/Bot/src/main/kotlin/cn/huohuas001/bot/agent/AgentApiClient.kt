package cn.huohuas001.bot.agent

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 接口返回的 token 用量；接口不返回 usage 时为 null。 */
class AgentTokenUsage(
    /** 请求侧消耗的 token 数（上下文体积） */
    val promptTokens: Int,
    /** 回复侧消耗的 token 数 */
    val completionTokens: Int,
    val totalTokens: Int
)

/** OpenAI Chat Completions 兼容接口返回的一次对话结果。 */
class AgentChatResult(
    /** 模型返回的正文；无正文时可能为 null。引导模式下的思考块已从这里剥离。 */
    val content: String?,
    /** 模型返回的推理思考过程（仅推理模型提供，如 reasoning_content / reasoning）；不支持推理时为 null */
    val reasoning: String?,
    /** 模型要求的工具调用列表；没有工具调用时为 null */
    val toolCalls: JSONArray?,
    /** 本次调用的 token 用量 */
    val usage: AgentTokenUsage? = null
)

/** 调用 AI 接口失败（网络、鉴权、超时、模型不支持 function calling 等）。 */
class AgentApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 面向 OpenAI 兼容接口的 Chat Completions 客户端，支持 tools(function calling)。
 *
 * 仅依赖项目已有的 fastjson 与 JDK 原生 HTTP 连接，与 [cn.huohuas001.bot.events.commands.SensitiveFilter]
 * 保持一致的实现风格。
 */
class AgentApiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /** 思考程度档位；留空表示不附加任何思考参数 */
    private val thinkLevel: String = "",
    /** 思考参数名，按模型厂商选择 */
    private val thinkParam: String = ""
) {

    /**
     * 最近一次调用里"思考参数没能生效"的提示，由 [AgentManager] 取走记日志。
     *
     * 档位和参数名对不上时（例如把 token 预算填给只认字符串档位的参数），
     * 这里不能让请求直接失败，但也不能悄悄吞掉，由调用方写进日志方便排查。
     */
    @Volatile
    var thinkingWarning: String? = null
        private set

    /** 诊断用：最近一次响应的原始 content（剥离思考块之前）。 */
    @Volatile
    var lastRawContent: String? = null
        private set

    /**
     * 调用 chat/completions，返回模型正文、推理思考与工具调用。
     *
     * @param tools 工具定义；传 null 表示不带工具（上下文压缩这类纯文本任务用）
     */
    fun chat(messages: List<JSONObject>, tools: JSONArray?): AgentChatResult {
        thinkingWarning = null
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            if (tools != null) put("tools", tools)
            put("temperature", 0.3)
        }
        if (thinkLevel.isNotBlank() && thinkParam.isNotBlank()) {
            thinkingWarning = AgentThinking.applyRequest(body, thinkLevel, thinkParam)
        }
        return buildResult(body)
    }

    /** 发送请求并解析响应。 */
    private fun buildResult(body: JSONObject): AgentChatResult {
        val responseText = post(body.toJSONString())

        val response = JSON.parseObject(responseText)
        val choices = response.getJSONArray("choices")
            ?: throw AgentApiException("AI 接口响应缺少 choices 字段: ${responseText.take(500)}")
        val message = choices.getJSONObject(0)?.getJSONObject("message")
            ?: throw AgentApiException("AI 接口响应缺少 message 字段: ${responseText.take(500)}")

        // 引导模式下模型把分析写在 [思考] 标签里，这里当作思考过程剥出来，正文只留答复
        lastRawContent = message.getString("content")
        val (thinkBlocks, visible) = AgentThinking.splitThinkBlocks(message.getString("content"))
        val reasoning = listOfNotNull(extractReasoning(message), thinkBlocks)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

        return AgentChatResult(
            content = visible.takeIf { it.isNotBlank() },
            reasoning = reasoning,
            toolCalls = message.getJSONArray("tool_calls"),
            usage = extractUsage(response)
        )
    }

    /** 解析 usage 字段，用于按真实 token 数做上下文压缩。 */
    private fun extractUsage(response: JSONObject): AgentTokenUsage? {
        val usage = response.getJSONObject("usage") ?: return null
        val prompt = usage.getInteger("prompt_tokens") ?: return null
        val completion = usage.getInteger("completion_tokens") ?: 0
        return AgentTokenUsage(prompt, completion, usage.getInteger("total_tokens") ?: prompt + completion)
    }

    /** 从响应 message 中提取推理模型的思考内容，不支持推理时返回 null。 */
    private fun extractReasoning(message: JSONObject): String? {
        val reasoningContent = message.get("reasoning_content")
        if (reasoningContent is String && reasoningContent.isNotBlank()) return reasoningContent
        val reasoning = message.get("reasoning") ?: return null
        return when (reasoning) {
            is String -> reasoning.takeIf { it.isNotBlank() }
            is JSONArray -> reasoning.joinToString("\n") { element ->
                if (element is JSONObject) element.getString("content") ?: element.toJSONString()
                else element.toString()
            }.takeIf { it.isNotBlank() }
            is JSONObject -> reasoning.getString("content")?.takeIf { it.isNotBlank() }
                ?: reasoning.toJSONString()
            else -> reasoning.toString()
        }
    }

    private fun post(json: String): String {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        } catch (error: Exception) {
            throw AgentApiException("无法连接 AI 接口 $url: ${error.message}", error)
        }

        try {
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val errorBody = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (code !in 200..299) {
                throw AgentApiException("AI 接口返回 HTTP $code: ${errorBody?.take(500).orEmpty()}")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: AgentApiException) {
            throw error
        } catch (error: Exception) {
            throw AgentApiException("请求 AI 接口失败: ${error.message}", error)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
