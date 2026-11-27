package agent.events.commands

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/** Direction is kept outside the detector so the same policy can be used for QQ and game traffic. */
enum class AuditDirection { QQ_TO_GAME, GAME_TO_QQ }

data class SensitiveAuditResult(
    val original: String,
    val sanitized: String,
    val matchedWords: List<String>,
    val blocked: Boolean,
    val aiBlocked: Boolean = false,
    val aiReason: String = ""
)

/**
 * Local first-pass moderation with an optional, strictly parsed AI second pass.
 *
 * The local detector deliberately canonicalises compatibility/full-width text and removes
 * separators/format characters, so spaces, punctuation and zero-width characters cannot be
 * used as a trivial bypass. AI output can only add a violation when it explicitly returns
 * JSON `{\"violation\":true}`; malformed/network-failed responses never block by themselves.
 */
object SensitiveFilter {
    private val defaults = listOf("傻逼", "操你", "色情", "反动", "赌博")
    private const val MAX_REVIEW_CHARS = 2048

    fun builtInWords(): List<String> = defaults.toList()

    fun effectiveWords(words: List<String>, allowNone: Boolean = false): List<String> {
        val custom = words.asSequence().map { it.trim() }.filter { it.length >= 2 }.distinct().toList()
        return if (allowNone) custom else (defaults + custom).distinct()
    }

    fun inspect(
        value: String,
        words: List<String> = emptyList(),
        allowNone: Boolean = false,
        baseUrl: String? = null,
        apiKey: String? = null,
        model: String? = null,
        aiReview: Boolean = false
    ): SensitiveAuditResult {
        if (value.isBlank()) return SensitiveAuditResult(value, value, emptyList(), false)
        val active = effectiveWords(words, allowNone)
        val matches = active.filter { containsCanonical(value, it) }
        val locallyBlocked = matches.isNotEmpty()
        val ai = if (!locallyBlocked && aiReview && !baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            reviewWithAi(value, baseUrl, apiKey, model)
        } else AiVerdict(false, "")
        val blocked = locallyBlocked || ai.blocked
        val sanitized = when {
            locallyBlocked -> maskLiteralMatches(value, matches)
            ai.blocked -> "【内容已拦截】"
            else -> value
        }
        return SensitiveAuditResult(value, sanitized, matches, blocked, ai.blocked, ai.reason)
    }

    /** 公开匹配接口：返回文本中命中的词表（供 AI 入站/出站过滤复用同一套归一化防护）。 */
    fun findMatches(value: String, words: List<String>, allowNone: Boolean = false): List<String> {
        if (value.isBlank()) return emptyList()
        return effectiveWords(words, allowNone).filter { containsCanonical(value, it) }
    }

    /**
     * 星号包裹掩蔽：违禁词替换为 *原词*（保留原词并标记），供 AI 上下文使用——
     * 模型既能理解语境，又明确知道哪些词被平台屏蔽。
     */
    fun maskWrapped(value: String, matches: List<String>): String {
        var result = value
        matches.sortedByDescending { it.length }.forEach { word ->
            if (word.isBlank()) return@forEach
            result = result.replace(word, "*" + word + "*", ignoreCase = true)
        }
        return result
    }

    /** 便捷入口：直接返回掩蔽后的文本（*词* 格式）与命中词。 */
    fun maskForAi(value: String, words: List<String>, allowNone: Boolean = false): Pair<String, List<String>> {
        val hits = findMatches(value, words, allowNone)
        if (hits.isEmpty()) return value to emptyList()
        return maskWrapped(value, hits) to hits
    }

    /** Compatibility API used by existing formatting paths. */
    fun filter(
        value: String,
        baseUrl: String? = null,
        apiKey: String? = null,
        model: String? = null,
        words: List<String> = emptyList(),
        allowNone: Boolean = false,
        aiReview: Boolean = false
    ): String = inspect(value, words, allowNone, baseUrl, apiKey, model, aiReview).sanitized

    private data class AiVerdict(val blocked: Boolean, val reason: String)

    private fun reviewWithAi(value: String, baseUrl: String, apiKey: String, model: String?): AiVerdict {
        if (value.length > MAX_REVIEW_CHARS) return AiVerdict(false, "")
        return try {
            val connection = URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2500
            connection.readTimeout = 4000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val body = JSONObject().apply {
                put("model", model?.takeIf(String::isNotBlank) ?: "gpt-4o-mini")
                put("temperature", 0.0)
                put("max_tokens", 80)
                put("messages", listOf(
                    mapOf("role" to "system", "content" to "只做内容安全二审。必须只输出JSON：{\"violation\":true或false,\"reason\":\"简短原因\"}。不要复述原文。"),
                    mapOf("role" to "user", "content" to value)
                ))
            }.toJSONString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val response = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            val content = JSON.parseObject(response)?.getJSONArray("choices")?.getJSONObject(0)
                ?.getJSONObject("message")?.getString("content")?.trim().orEmpty()
            val jsonText = content.substringAfter("{", "").substringBeforeLast("}", "}").let { if (it.isBlank()) "" else "{$it}" }
            val verdict = JSON.parseObject(jsonText)
            if (verdict?.getBooleanValue("violation") == true) AiVerdict(true, verdict.getString("reason").orEmpty().take(120))
            else AiVerdict(false, "")
        } catch (_: Exception) {
            AiVerdict(false, "")
        }
    }

    private fun containsCanonical(value: String, word: String): Boolean {
        val haystack = canonical(value)
        val needle = canonical(word)
        return needle.length >= 2 && haystack.contains(needle)
    }

    private fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .filterNot { ch ->
            ch.isWhitespace() || ch == '\u200B' || ch == '\u200C' || ch == '\u200D' ||
                ch == '\uFEFF' || Character.getType(ch).let { type ->
                    type == Character.CONNECTOR_PUNCTUATION.toInt() || type == Character.DASH_PUNCTUATION.toInt() ||
                        type == Character.START_PUNCTUATION.toInt() || type == Character.END_PUNCTUATION.toInt() ||
                        type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                        type == Character.OTHER_PUNCTUATION.toInt()
                }
        }

    private fun maskLiteralMatches(value: String, matches: List<String>): String {
        var result = value
        matches.sortedByDescending { it.length }.forEach { word ->
            if (word.isBlank()) return@forEach
            result = result.replace(word, "*".repeat(word.length), ignoreCase = true)
        }
        // If a separator/format bypass was used, do not accidentally forward the original text.
        return if (result == value && matches.isNotEmpty()) "【内容已拦截】" else result
    }
}
