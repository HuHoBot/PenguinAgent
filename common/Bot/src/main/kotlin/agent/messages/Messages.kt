package agent.messages

import agent.provider.BotShared
import agent.state.SimpleYaml
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
        val locale = language.trim().replace('-', '_').split('_')
        this.language = if (locale.firstOrNull()?.matches(Regex("[A-Za-z]{2,3}")) == true) {
            locale[0].lowercase() + (locale.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.let { "_" + it.uppercase() } ?: "")
        } else FALLBACK
        fallback = loadBundled(FALLBACK).orEmpty() + extractBundledToDisk(FALLBACK).orEmpty()
        primary = if (this.language == FALLBACK) fallback else
            loadBundled(this.language).orEmpty() + extractBundledToDisk(this.language).orEmpty()
    }

    fun t(key: String, vararg args: Any?): String =
        named(key,args.mapIndexed { index,value -> index.toString() to value }.toMap())

    fun named(key: String, args: Map<String, Any?>): String {
        val text=primary[key] ?: fallback[key] ?: loadBundled(FALLBACK)?.get(key) ?: return key
        return placeholder.replace(text) { m ->
            val name=m.groupValues[1]
            if (name in args) args[name]?.toString().orEmpty() else m.value
        }
    }

    private val placeholder=Regex("\\{([A-Za-z0-9_-]+)}")

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
            val dir = plugin.getDataDirectory()?.resolve("translations") ?: return null
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
        flatten("",SimpleYaml.parse(text))
        return out
    }
}
