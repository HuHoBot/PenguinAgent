package cn.huohuas001.bot.provider

import cn.huohuas001.bot.tools.filterTextByRegex
import java.io.File

class ChatFormat(
    val fromGame: String,
    val fromGroup: String,
    val postChat: Boolean,
    /** 游戏消息必须以此前缀开头才会转发；空字符串表示转发全部消息。 */
    val startWith: String
)

class PlayerEventFormat(
    val joinEnabled: Boolean,
    val joinFormat: String,
    val quitEnabled: Boolean,
    val quitFormat: String
)

class Motd(
    val serverIP: String,
    val serverPort: Int,
    val api: String,
    val text: String,
    val postImg: Boolean,
    val useMarkdown: Boolean
)

class WhiteList(
    val addCommand: String,
    val delCommand: String
)

class CustomCommandDetail(
    val key: String,
    val command: String,
    val permission: Int,
    val pushMenu: Boolean = true
)

/**
 * 管理员模式
 *
 * @property value 配置文件中的原始字符串值
 */
enum class AdminMode(val value: String) {
    /** 仅 QQ 号(见 [ConfigProvider.getAdminList])生效 */
    QQ("qq"),

    /** 仅通过配置文件指定的管理员生效 */
    CONFIG("config"),

    /** 两者皆可 */
    BOTH("both");

    companion object {
        fun from(value: String?): AdminMode? {
            if (value == null) return null
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
        }
    }
}

interface ConfigProvider {
    /** 是否屏蔽 QQ Bot SDK 直接写入 System.out 的调试输出。 */
    fun shouldSuppressQqBotConsoleOutput(): Boolean = true

    /** OpenAI 兼容审核服务；留空时只执行本地敏感词首检。 */
    fun getAuditBaseUrl(): String? = System.getenv("HUHOBOT_AUDIT_BASE_URL")
    fun getAuditApiKey(): String? = System.getenv("HUHOBOT_AUDIT_API_KEY")
    fun getAuditModel(): String? = System.getenv("HUHOBOT_AUDIT_MODEL")
    fun getSensitiveWords(): List<String> {
        val directories =
            listOfNotNull(getConfigFile()?.parentFile?.resolve("sensitive-words"), File("sensitive-words"))
        return directories.asSequence().filter { it.isDirectory }.flatMap { dir ->
            (dir.listFiles { file -> file.isFile && file.extension.equals("txt", true) } ?: emptyArray()).asSequence()
        }.flatMap { it.readLines(Charsets.UTF_8).asSequence() }.map { it.trim() }.filter { it.length >= 2 }.distinct()
            .toList()
    }

    /** 是否启用 QQ 头像认证功能。 */
    fun isAuthenticationEnabled(): Boolean = true

    fun getChatFormat(): ChatFormat
    fun getPlayerEventFormat(): PlayerEventFormat = PlayerEventFormat(
        joinEnabled = true,
        joinFormat = "[游戏] {name} 加入了服务器",
        quitEnabled = true,
        quitFormat = "[游戏] {name} 离开了服务器"
    )

    fun getMotd(): Motd
    fun getWhiteList(): WhiteList = WhiteList("whitelist add {name}", "whitelist remove {name}")
    fun getConfigFile(): File? {
        return null
    }

    fun getFilterRegexList(): List<String> {
        return emptyList()
    }

    fun filterText(text: String): String {
        return filterTextByRegex(text, getFilterRegexList())
    }

    fun formatGroupMessage(name: String, message: String): String {
        val filtered = filterText(message)
        val raw = getChatFormat().fromGroup
            .replace("{name}", name)
            .replace("{nick}", name)
            .replace("{message}", filtered)
            .replace("{msg}", filtered)
        return convertAmpersandColors(raw)
    }

    fun formatGameMessage(name: String, message: String): String {
        val filtered = filterText(message)
        val raw = getChatFormat().fromGame
            .replace("{name}", name)
            .replace("{message}", filtered)
            .replace("{msg}", filtered)
        return convertAmpersandColors(raw)
    }

    fun formatPlayerJoinMessage(name: String): String =
        formatPlayerEventMessage(getPlayerEventFormat().joinFormat, name)

    fun formatPlayerQuitMessage(name: String): String =
        formatPlayerEventMessage(getPlayerEventFormat().quitFormat, name)

    /**
     * 将 & 颜色码转换为 Minecraft § 格式码，支持 &0-9, &a-f, &k-o, &r。
     * 用法示例：from-group 格式中写 "&b[QQ] &f{name}: &7{message}"
     */
    fun convertAmpersandColors(text: String): String {
        return text.replace(Regex("&([0-9a-fk-orA-FK-OR])")) { match ->
            "§${match.groupValues[1].lowercase()}"
        }
    }

    fun formatPlayerEventMessage(format: String, name: String): String = convertAmpersandColors(
        format
            .replace("{name}", name)
            .replace("{player}", name)
            .replace("{server}", getServerName())
            .replace("{platform}", getPlatform())
    )

    /** Markdown 配置键到 Markdown 目录内文件名的映射。 */
    fun getMarkdownFiles(): Map<String, String> = DEFAULT_MARKDOWN_FILES

    /**
     * 读取插件配置目录下 Markdown 目录中的文件。
     */
    fun getMarkdown(key: String): String? {
        val fileName = (getMarkdownFiles()[key] ?: DEFAULT_MARKDOWN_FILES[key])
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val configDirectory = getConfigFile()?.absoluteFile?.parentFile ?: return null

        return try {
            val markdownDirectory = configDirectory.resolve("Markdown").canonicalFile
            val markdownFile = markdownDirectory.resolve(fileName).canonicalFile
            if (!markdownFile.toPath().startsWith(markdownDirectory.toPath()) || !markdownFile.isFile) {
                null
            } else {
                markdownFile.readText(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getAdminMode(): AdminMode {
        return AdminMode.BOTH
    }

    fun getAdminList(): List<String> {
        return emptyList()
    }

    fun getGroupOpenIdList(): List<String> {
        return emptyList()
    }

    fun getFullAmount(): Boolean {
        return false
    }

    fun getCommandList(): Map<String, Boolean> {
        return emptyMap()
    }

    /** 获取命令面板开关列表,命令名 -> 是否推送到 QQ 指令面板,默认为空 */
    fun getCommandMenuList(): Map<String, Boolean> {
        return emptyMap()
    }

    fun getBotName(): String
    fun getServerName(): String = getBotName()
    fun getPlatform(): String
    fun getPluginVersion(): String
    fun getCustomCommands(): List<CustomCommandDetail> = emptyList()

    /** 服务器 Minecraft 版本信息（供 AI Agent 生成匹配的命令语法）。 */
    fun getServerVersion(): String = "未知"

    /** AI Agent 总开关；关闭时 /agent 命令不可用。 */
    fun getAgentEnabled(): Boolean = false
    fun getAgentBaseUrl(): String? = System.getenv("HUHOBOT_AGENT_BASE_URL")
    fun getAgentApiKey(): String? = System.getenv("HUHOBOT_AGENT_API_KEY")
    fun getAgentModel(): String? = System.getenv("HUHOBOT_AGENT_MODEL")
    fun getAgentCommandMode(): cn.huohuas001.bot.agent.AgentCommandMode = cn.huohuas001.bot.agent.AgentCommandMode.MANUAL

    /** 绑定时是否需要游戏内 /qqbind 验证；关闭时直接绑定。 */
    fun getBindingRequireGameVerification(): Boolean = false

    fun getServerPluginList(): List<String> = emptyList()
    fun getServerCommandHelp(plugin: String?, command: String?): String = "当前平台不支持查询命令信息"
    fun getServerLogs(lines: Int?, keyword: String?): String = "当前平台不支持读取服务端日志"
}

private val DEFAULT_MARKDOWN_FILES = mapOf(
    "queryOnline" to "online.md",
    "motd" to "motd.md"
)
