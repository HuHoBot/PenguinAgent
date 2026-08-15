package cn.huohuas001.bot.events.commands

import com.alibaba.fastjson.JSON
import java.net.HttpURLConnection
import java.net.URL

/** 本地首检；配置了 OpenAI 兼容地址时执行 AI 二审，失败回退本地替换。 */
object SensitiveFilter {
    private val defaults = listOf("傻逼", "操你", "色情", "反动", "赌博")

    fun filter(value: String, baseUrl: String? = null, apiKey: String? = null, model: String? = null, words: List<String> = emptyList()): String {
        var local = value
        (defaults + words).distinct().forEach { word -> local = local.replace(word, "*".repeat(word.length), ignoreCase = true) }
        if (local == value || baseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return local
        return try {
            val connection = URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val body = "{\"model\":\"${escape(model ?: "gpt-4o-mini")}\",\"messages\":[{\"role\":\"system\",\"content\":\"你是敏感词二审工具，只输出替换敏感内容后的完整原文。\"},{\"role\":\"user\",\"content\":\"${escape(value)}\"}],\"temperature\":0.1}"
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSON.parseObject(response).getJSONArray("choices")?.getJSONObject(0)?.getJSONObject("message")?.getString("content")?.trim() ?: local
        } catch (_: Exception) { local }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
