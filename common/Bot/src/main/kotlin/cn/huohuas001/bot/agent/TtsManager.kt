package cn.huohuas001.bot.agent

import cn.huohuas001.bot.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * TTS 语音合成管理器。
 *
 * 统一走 OpenAI 兼容的 chat/completions 接口（MiMo-V2.5-TTS 系列为主）：
 * - 预制音色（preset）：audio.voice = 音色名；部分服务商音色支持唱歌风格。
 * - 自然语言音色（natural）：user message 传音色描述，无需参考音频。
 * - 音色克隆（clone）：audio.voice = data:{mime};base64,<音频>（mp3/wav，≤10MB），
 *   参考音频放 `plugins/HuHoBotPenguin/<clone-voices-dir>/`，配置文件指定默认。
 *
 * 三种模式各自配置模型 ID；不填则该能力对 AI 不可见（工具描述里不暴露）。
 * 每一轮对话（一条用户消息触发的完整回复）最多合成 [maxPerTurn] 条语音。
 * 音频写入插件数据目录 tts-cache/，经 WebUI 以 /tts-cache/<file> 暴露，
 * 由 QQ 服务器按富媒体 URL 拉取后以语音消息发出（file_type=3）。
 */
object TtsManager {

    const val TOOL_SPEAK_TTS = "speak_tts"

    /** MiMo 预置音色表（工具描述里告知 AI）；其它服务商音色按其文档填写。 */
    private val PRESET_VOICES = listOf(
        "mimo_default（MiMo 默认女声）",
        "default_zh（中文女声）",
        "default_en（英文女声）"
    )

    private val turnCounters = ConcurrentHashMap<String, Int>()

    /** 清空某一轮的语音计数（新一轮用户消息开始时调用）。 */
    fun beginTurn(sessionKey: String) {
        turnCounters[sessionKey] = 0
        // 防泄漏：计数只保留最近 64 个会话
        if (turnCounters.size > 128) {
            turnCounters.keys.take(turnCounters.size - 64).forEach { turnCounters.remove(it) }
        }
    }

    private fun remaining(sessionKey: String, max: Int): Int =
        (turnCounters[sessionKey] ?: 0).let { max - it }

    data class TtsResult(val ok: Boolean, val url: String = "", val message: String = "")

    /** 配置是否具备至少一种可用模式。 */
    fun anyModelAvailable(): Boolean =
        presetModel().isNotBlank() || naturalModel().isNotBlank() || cloneModel().isNotBlank()

    private fun cfg(): cn.huohuas001.bot.provider.ConfigProvider? = BotShared.getPluginOrNull()

    private fun presetModel(): String = cfg()?.getTtsPresetModel()?.trim().orEmpty()
    private fun naturalModel(): String = cfg()?.getTtsNaturalModel()?.trim().orEmpty()
    private fun cloneModel(): String = cfg()?.getTtsCloneModel()?.trim().orEmpty()

    private fun baseUrl(): String = cfg()?.getTtsBaseUrl()?.trim()?.removeSuffix("/")
        .orEmpty().ifBlank { "https://api.xiaomimimo.com/v1" }
    private fun apiKey(): String = cfg()?.getTtsApiKey()?.trim().orEmpty()
    private fun audioFormat(): String = cfg()?.getTtsFormat()?.trim().orEmpty().ifBlank { "wav" }
    private fun maxPerTurn(): Int = cfg()?.getTtsMaxPerTurn()?.coerceIn(1, 10) ?: 2

    private fun presetDefaultVoice(): String =
        cfg()?.getTtsPresetDefaultVoice()?.trim().orEmpty().ifBlank { "mimo_default" }

    private fun naturalDefaultVoice(): String =
        cfg()?.getTtsNaturalDefaultVoice()?.trim().orEmpty().ifBlank { "温柔亲切的中文女声" }

    private fun cloneDefaultVoice(): String = cfg()?.getTtsCloneDefaultVoice()?.trim().orEmpty()

    private fun cloneVoicesDir(): File {
        val plugin = BotShared.getPluginOrNull()
        val dirName = cfg()?.getTtsCloneVoicesDir()?.trim().orEmpty().ifBlank { "tts-voices" }
        return plugin?.getConfigFile()?.parentFile?.resolve(dirName) ?: File(dirName)
    }

    /** 供工具描述使用的可用模式说明；未配置的模型不出现在描述里。 */
    fun toolDescription(): String? {
        val lines = mutableListOf<String>()
        if (presetModel().isNotBlank()) {
            lines.add("- mode=preset：预制音色。可选 voice 参数（默认 ${presetDefaultVoice()}）。可用音色：${PRESET_VOICES.joinToString("、")}；其它服务商音色按其文档填写")
        }
        if (naturalModel().isNotBlank()) {
            lines.add("- mode=natural：自然语言生成音色。可选 description 参数描述音色（默认 ${naturalDefaultVoice()}）")
        }
        if (cloneModel().isNotBlank()) {
            val dir = cloneVoicesDir()
            val files = dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
            val listHint = if (files.isEmpty()) "目录为空（请把 mp3/wav 参考音频放到 ${dir.path}）"
            else "目录现有：${files.joinToString("、")}"
            lines.add("- mode=clone：音色克隆。可选 voice_file 参数（参考音频文件名，仅取文件名，非法字符自动过滤；默认 ${cloneDefaultVoice().ifBlank { "未配置，需指定" }}）。$listHint")
        }
        if (lines.isEmpty()) return null
        return "把指定文本合成语音并以 QQ 语音消息发送到当前会话。每一轮对话最多调用 ${maxPerTurn()} 次。\n可用模式：\n" +
            lines.joinToString("\n")
    }

    /**
     * 合成并发送。mode/preset|natural|clone；voice=预制音色名；description=自然语言音色；
     * voiceFile=克隆参考音频文件名（仅文件名）；style=风格指令（可选）。
     */
    fun speak(sessionKey: String, mode: String, text: String, voice: String?, description: String?, voiceFile: String?, style: String?): TtsResult {
        if (text.isBlank()) return TtsResult(false, message = "text 不能为空")
        val max = maxPerTurn()
        val left = remaining(sessionKey, max)
        if (left <= 0) return TtsResult(false, message = "本轮语音次数已用完（每轮最多 $max 条）")
        val audio = when (mode) {
            "preset" -> synthesizePreset(text, voice, style)
            "natural" -> synthesizeNatural(text, description ?: style, style)
            "clone" -> synthesizeClone(text, voiceFile, style)
            else -> return TtsResult(false, message = "未知 mode：$mode（可用 preset/natural/clone）")
        }
        return if (audio.ok) {
            turnCounters[sessionKey] = (turnCounters[sessionKey] ?: 0) + 1
            TtsResult(true, url = audio.url)
        } else audio
    }

    /** speak_tts 工具 schema；description 由 toolDescription() 动态生成。 */
    fun speakTool(description: String): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_SPEAK_TTS)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("mode", JSONObject().apply { put("type", "string"); put("enum", listOf("preset", "natural", "clone")); put("description", "语音生成方式：preset=预制音色 / natural=自然语言描述音色 / clone=音色克隆") })
                    put("text", JSONObject().apply { put("type", "string"); put("description", "要朗读的文本（不要超过 200 字，过长会被截断）") })
                    put("voice", JSONObject().apply { put("type", "string"); put("description", "preset 模式的音色名，可选") })
                    put("description", JSONObject().apply { put("type", "string"); put("description", "natural 模式的音色自然语言描述，可选") })
                    put("voice_file", JSONObject().apply { put("type", "string"); put("description", "clone 模式的参考音频文件名（仅文件名），可选") })
                    put("style", JSONObject().apply { put("type", "string"); put("description", "朗读风格指令（如'温柔地'），可选") })
                })
                put("required", listOf("mode", "text"))
            })
        })
    }

    private fun base64Of(file: File): String? = runCatching {
        if (!file.isFile) return null
        if (file.length() > 10L * 1024 * 1024) return null
        Base64.getEncoder().encodeToString(file.readBytes())
    }.getOrNull()

    private fun mimeOf(name: String): String =
        if (name.endsWith(".mp3", true)) "audio/mpeg" else "audio/wav"

    private fun synthesizePreset(text: String, voice: String?, style: String?): TtsResult {
        val model = presetModel()
        if (model.isBlank()) return TtsResult(false, message = "预制音色模型未配置（tts.preset-model）")
        val body = JSONObject().apply {
            put("model", model)
            put("modalities", com.alibaba.fastjson.JSONArray(listOf("text", "audio")))
            put("audio", JSONObject().apply { put("format", audioFormat()); put("voice", voice?.takeIf(String::isNotBlank) ?: presetDefaultVoice()) })
            put("messages", com.alibaba.fastjson.JSONArray(listOf(
                JSONObject().apply { put("role", "user"); put("content", style?.takeIf(String::isNotBlank) ?: "自然清晰地朗读") },
                JSONObject().apply { put("role", "assistant"); put("content", text) }
            )))
        }
        return callAndDeliver(body, "preset")
    }

    private fun synthesizeNatural(text: String, description: String?, style: String?): TtsResult {
        val model = naturalModel()
        if (model.isBlank()) return TtsResult(false, message = "自然语言音色模型未配置（tts.natural-model）")
        val desc = (description ?: style)?.takeIf(String::isNotBlank) ?: naturalDefaultVoice()
        val body = JSONObject().apply {
            put("model", model)
            put("modalities", com.alibaba.fastjson.JSONArray(listOf("text", "audio")))
            put("audio", JSONObject().apply { put("format", audioFormat()) })
            put("messages", com.alibaba.fastjson.JSONArray(listOf(
                JSONObject().apply { put("role", "user"); put("content", desc) },
                JSONObject().apply { put("role", "assistant"); put("content", text) }
            )))
        }
        return callAndDeliver(body, "natural")
    }

    private fun synthesizeClone(text: String, voiceFile: String?, style: String?): TtsResult {
        val model = cloneModel()
        if (model.isBlank()) return TtsResult(false, message = "音色克隆模型未配置（tts.clone-model）")
        val name = (voiceFile?.takeIf(String::isNotBlank) ?: cloneDefaultVoice())
        if (name.isBlank()) return TtsResult(false, message = "未指定参考音频（voice_file），且未配置默认音频（tts.clone-default-voice）")
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "")
        val file = cloneVoicesDir().resolve(safe)
        if (!file.isFile) return TtsResult(false, message = "参考音频不存在：${file.path}")
        val b64 = base64Of(file)
            ?: return TtsResult(false, message = "参考音频读取失败或超过 10MB：${file.name}")
        val body = JSONObject().apply {
            put("model", model)
            put("modalities", com.alibaba.fastjson.JSONArray(listOf("text", "audio")))
            put("audio", JSONObject().apply {
                put("format", audioFormat())
                put("voice", "data:${mimeOf(safe)};base64,$b64")
            })
            put("messages", com.alibaba.fastjson.JSONArray(listOf(
                JSONObject().apply { put("role", "user"); put("content", style?.takeIf(String::isNotBlank) ?: "自然清晰地朗读") },
                JSONObject().apply { put("role", "assistant"); put("content", text) }
            )))
        }
        return callAndDeliver(body, "clone")
    }

    /** 调用 chat/completions，取回音频并落盘到 tts-cache，返回可访问 URL。 */
    private fun callAndDeliver(body: JSONObject, tag: String): TtsResult {
        if (apiKey().isBlank()) return TtsResult(false, message = "TTS API Key 未配置（tts.api-key）")
        val publicBase = cfg()?.getTtsPublicBaseUrl()?.trim()?.removeSuffix("/").orEmpty()
        if (publicBase.isBlank()) {
            return TtsResult(false, message = "tts.public-base-url 未配置：QQ 服务器需要能通过公网 URL 拉取音频（填 WebUI 对外的公网地址）")
        }
        val http: HttpURLConnection = URL("${baseUrl()}/chat/completions").openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.connectTimeout = 10_000
            http.readTimeout = 120_000
            http.setRequestProperty("api-key", apiKey())
            http.setRequestProperty("Authorization", "Bearer ${apiKey()}")
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            http.doOutput = true
            OutputStreamWriter(http.outputStream, Charsets.UTF_8).use { it.write(body.toJSONString()) }
            val status = http.responseCode
            val text = (if (status in 200..299) http.inputStream else http.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                return TtsResult(false, message = "TTS 请求失败 HTTP $status：${text.take(200)}")
            }
            val root = JSON.parseObject(text) ?: return TtsResult(false, message = "TTS 响应解析失败")
            val b64 = root.getJSONArray("choices")?.getJSONObject(0)
                ?.getJSONObject("message")?.getJSONObject("audio")?.getString("data")
            if (b64.isNullOrBlank()) return TtsResult(false, message = "TTS 响应中没有音频数据（audio.data 为空）")
            val plugin = BotShared.getPluginOrNull()
            val cacheDir = plugin?.getConfigFile()?.parentFile?.resolve("tts-cache")
                ?: File("tts-cache").also { it.mkdirs() }
            cacheDir.mkdirs()
            // 清理 1 小时前的旧音频
            val cutoff = System.currentTimeMillis() - 3_600_000
            cacheDir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
            val file = File(cacheDir, "tts-${System.currentTimeMillis()}-$tag.${audioFormat()}")
            file.writeBytes(Base64.getDecoder().decode(b64))
            return TtsResult(true, url = "$publicBase/tts-cache/${file.name}")
        } catch (error: Exception) {
            return TtsResult(false, message = "TTS 请求异常：${error.message}")
        } finally {
            http.disconnect()
        }
    }
}
