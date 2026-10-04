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
    val quitFormat: String,
    /** 是否忽略平台事件的隐藏、取消或登录状态判断，始终转发进退服事件。 */
    val alwaysForward: Boolean = false,
    /** 是否把玩家死亡播报到 QQ 群。 */
    val deathEnabled: Boolean = false,
    /**
     * 死亡播报格式，可用 {name}、{player}、{server}、{platform}、{killer}、{message}。
     * {message} 是中文死亡描述（不含死者名，如「被苦力怕杀死了」），{killer} 是击杀者名。
     */
    val deathFormat: String = "[游戏] {name} 死亡了"
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
        val result = getChatFormat().fromGroup
            .replace("{name}", name)
            .replace("{nick}", name)
            .replace("{message}", filtered)
            .replace("{msg}", filtered)
        return convertAmpersandColors(result)
    }

    fun formatGameMessage(name: String, message: String): String {
        val filtered = filterText(message)
        val result = getChatFormat().fromGame
            .replace("{name}", name)
            .replace("{message}", filtered)
            .replace("{msg}", filtered)
        return convertAmpersandColors(result)
    }

    fun formatPlayerJoinMessage(name: String): String =
        formatPlayerEventMessage(getPlayerEventFormat().joinFormat, name)

    fun formatPlayerQuitMessage(name: String): String =
        formatPlayerEventMessage(getPlayerEventFormat().quitFormat, name)

    /**
     * 格式化死亡播报。
     *
     * @param deathMessage 中文死亡描述（Spigot 侧由 DeathMessage 生成），替换格式中的 {message}
     * @param killerName 击杀者名，替换格式中的 {killer}；环境死亡等没有击杀者时填「未知」
     */
    fun formatPlayerDeathMessage(name: String, deathMessage: String?, killerName: String? = null): String {
        val message = deathMessage?.takeIf(String::isNotBlank)?.let { filterText(it) }.orEmpty()
        val killer = killerName?.takeIf(String::isNotBlank) ?: "未知"
        val result = getPlayerEventFormat().deathFormat
            .replace("{message}", message)
            .replace("{killer}", killer)
            .replace("{name}", name)
            .replace("{player}", name)
            .replace("{server}", getServerName())
            .replace("{platform}", getPlatform())
            .trim()
        return applyPlaceholders(name, convertAmpersandColors(result))
    }

    fun formatPlayerEventMessage(format: String, name: String): String {
        val result = format
            .replace("{name}", name)
            .replace("{player}", name)
            .replace("{server}", getServerName())
            .replace("{platform}", getPlatform())
        return applyPlaceholders(name, convertAmpersandColors(result))
    }

    /** 将 & 颜色符号转换为 Minecraft § 颜色代码。 */
    fun convertAmpersandColors(text: String): String {
        return text.replace(Regex("&(\\da-fk-orA-FK-OR)")) { "§${it.groupValues[1].lowercase()}" }
    }

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

    /**
     * 解析第三方占位符（Spigot 侧为 PlaceholderAPI 的 %占位符%）。
     *
     * 未安装对应插件时默认原样返回，平台可覆盖实现。
     *
     * @param playerName 玩家名；为空表示没有玩家上下文，只解析全局占位符
     */
    fun applyPlaceholders(playerName: String?, text: String): String = text

    /** 是否启用 PlaceholderAPI 占位符解析；未安装 PlaceholderAPI 时无效果。 */
    fun isPlaceholderApiEnabled(): Boolean = true

    /** 自定义更新检查数据源（逗号分隔的 URL），留空使用内置数据源。 */
    fun getUpdateCheckUrls(): String = ""

    /** 是否启用启动时与 /版本 的 GitHub Release 更新检查。 */
    fun isUpdateCheckEnabled(): Boolean = true

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

    /** 数值越小越先进入 QQ 面板；只影响面板，不影响命令执行。 */
    fun getCommandMenuPriorities(): Map<String, Int> = emptyMap()

    /** 展示管理员命令不放宽实际执行权限。 */
    fun showAdminCommandsInMenu(): Boolean = true

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

    /** 获取类工具结果只交给 AI，不在群里发卡片（默认开启）。 */
    fun isAgentFetchResultHidden(): Boolean = true

    /** 绑定时是否需要游戏内 /qqbind 验证；关闭时直接绑定。 */
    fun getBindingRequireGameVerification(): Boolean = true

    /**
     * 是否开启强制绑定：未绑定的玩家进游戏会被踢出并拿到验证码，
     * 必须先在 QQ 群完成绑定才能进入服务器。
     *
     * 注意：开启后请勿再叠加白名单插件或 Minecraft 自带白名单，
     * 否则未绑定玩家会在白名单阶段先被拦下，拿不到验证码。
     */
    fun isForceBindEnabled(): Boolean = false

    /** 强制绑定提示里展示的 QQ 群号列表；留空则不提示具体群号。 */
    fun getForceBindGroups(): List<String> = emptyList()

    /**
     * 强制绑定场景下，玩家解除绑定后立即断开其在线连接。
     *
     * 由各平台实现（需要踢出在线玩家的能力）；未实现或玩家不在线时返回 false。
     *
     * @return 是否真的踢出了在线玩家
     */
    fun kickUnboundPlayer(playerName: String): Boolean = false

    /** /执行 命令黑名单：禁止通过 /执行 运行的服务器命令（不区分大小写）。 */
    fun getCommandBlacklist(): List<String> = emptyList()

    fun getServerPluginList(): List<String> = emptyList()
    fun getServerCommandHelp(plugin: String?, command: String?): String = "当前平台不支持查询命令信息"
    fun getServerLogs(lines: Int?, keyword: String?): String = "当前平台不支持读取服务端日志"
}

private val DEFAULT_MARKDOWN_FILES = mapOf(
    "queryOnline" to "online.md",
    "motd" to "motd.md"
)
