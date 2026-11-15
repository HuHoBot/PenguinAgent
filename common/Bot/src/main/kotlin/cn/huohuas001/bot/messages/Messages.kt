package cn.huohuas001.bot.messages

import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.state.SimpleYaml
import java.io.File

/**
 * 插件消息文案（i18n）。
 *
 * - 内置 zh_CN / en_US 两套资源（classpath:messages/messages_<lang>.yml）；
 * - 启动时解压到 `translations/messages_<lang>.yml`（不存在才写，不覆盖用户改动）；
 * - `language` 配置切换；缺键时回退 zh_CN，再缺回退键名本身。
 * - 接入点逐步迁移：尚未接入的文案保持中文原样。
 */
object Messages {

    private val FALLBACK = "zh_CN"

    @Volatile
    private var language: String = FALLBACK

    @Volatile
    private var primary: Map<String, String> = emptyMap()

    @Volatile
    private var fallback: Map<String, String> = emptyMap()

    /** 启动/重载时调用。 */
    fun initialize(language: String) {
        this.language = (language.trim().ifEmpty { FALLBACK }).lowercase()
        fallback = loadBundled(FALLBACK) ?: emptyMap()
        primary = if (this.language == FALLBACK) fallback
        else loadBundled(this.language)
            ?: (extractBundledToDisk(this.language) ?: fallback)
        // 解压到磁盘供用户自定义（仅当文件不存在时写入，避免覆盖用户改动）
        extractBundledToDisk(this.language)
        extractBundledToDisk(FALLBACK)
    }

    /** 取文案；args 依序替换 {0} {1} ...。缺键回退 zh_CN，再缺回退 key。 */
    fun t(key: String, vararg args: Any?): String {
        val raw = primary[key] ?: fallback[key] ?: return key
        if (args.isEmpty()) return raw
        var result = raw
        args.forEachIndexed { index, arg ->
            result = result.replace("{$index}", arg?.toString() ?: "")
        }
        return result
    }

    fun currentLanguage(): String = language

    private fun bundledStream(lang: String) =
        Messages::class.java.getResourceAsStream("/messages/messages_$lang.yml")

    private fun loadBundled(lang: String): Map<String, String>? {
        val stream = bundledStream(lang) ?: return null
        return parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    /** 解压到插件目录（translations/），便于服主自定义；存在则直接读取磁盘版本。 */
    private fun extractBundledToDisk(lang: String): Map<String, String>? {
        return try {
            val plugin = BotShared.getPluginOrNull() ?: return null
            val dir = plugin.getConfigFile()?.parentFile?.resolve("translations") ?: return null
            val target = File(dir, "messages_$lang.yml")
            if (!target.isFile) {
                val stream = bundledStream(lang) ?: return null
                target.parentFile?.mkdirs()
                stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            parse(target.readText())
        } catch (_: Throwable) {
            null
        }
    }

    private fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun flatten(prefix: String, map: Map<String, Any?>) {
            for ((k, v) in map) {
                val path = if (prefix.isEmpty()) k else "$prefix.$k"
                when (v) {
                    is Map<*, *> -> @Suppress("UNCHECKED_CAST") flatten(path, v as Map<String, Any?>)
                    is List<*> -> out[path] = v.joinToString("\n") { item -> item?.toString() ?: "" }
                    else -> out[path] = v?.toString() ?: ""
                }
            }
        }
        val root = SimpleYaml.read(File.createTempFile("huhobot-msg", ".yml").apply {
            writeText(text)
            deleteOnExit()
        })
        flatten("", root)
        return out
    }
}
