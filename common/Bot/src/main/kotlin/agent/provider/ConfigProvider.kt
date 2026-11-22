package agent.provider

import agent.tools.MiniMessageSupport
import agent.tools.filterTextByRegex
import java.io.File

class ChatFormat(
    val fromGame: String,
    val fromGroup: String,
    val postChat: Boolean,
    /** 游戏消息必须以此前缀开头才会转发；空字符串表示转发全部消息。 */
    val startWith: String,
    /** QQ Markdown 不支持颜色，转发前去掉 §/& 代码，避免读者看到原始标记。 */
    val stripColourCodes: Boolean = true
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
    /** AI second review is opt-in; local built-ins remain active by default. */
    fun isModerationAiReviewEnabled(): Boolean = false
    /** Automatically attempt to recall an offending inbound QQ message; platform permissions still apply. */
    fun isModerationAutoRecallEnabled(): Boolean = false
    /** Explicitly disables built-in words. This is intentionally off by default. */
    fun isModerationAllowNone(): Boolean = false
    fun getSensitiveWords(): List<String> {
        val directories =
            listOfNotNull(getDataDirectory()?.resolve("sensitive-words"), getConfigFile()?.parentFile?.resolve("sensitive-words"))
        return directories.asSequence().filter { it.isDirectory }.flatMap { dir ->
            (dir.listFiles { file -> file.isFile && file.extension.equals("txt", true) } ?: emptyArray()).asSequence()
        }.flatMap { it.readLines(Charsets.UTF_8).asSequence() }.map { it.trim() }
            .filter { it.length >= 2 && !it.startsWith("#") && !it.startsWith(";") }.distinct()
            .toList()
    }

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

    /** 运行状态、缓存和可恢复数据目录；配置文件本身仍位于 getConfigFile() 同级。 */
    fun getDataDirectory(): File? = getConfigFile()?.parentFile?.resolve("data")

    fun getFilterRegexList(): List<String> {
        return emptyList()
    }

    fun filterText(text: String): String {
        return filterTextByRegex(text, getFilterRegexList())
    }

    fun formatGroupMessage(name: String, message: String): String {
        val filtered = filterText(message)
        // QQ 用户名和消息内容来自用户，先转义 MiniMessage 标签，
        // 否则谁发一句 <red> 就能把后面的聊天全部染色。
        val safeName = MiniMessageSupport.escape(name)
        val safeMessage = MiniMessageSupport.escape(filtered)
        val result = getChatFormat().fromGroup
            .replace("{name}", safeName)
            .replace("{nick}", safeName)
            .replace("{message}", safeMessage)
            .replace("{msg}", safeMessage)
        // 模板支持 MiniMessage（<gold>、<#FFAA00>）与旧式 & 颜色码，统一转成 § 码。
        return convertAmpersandColors(MiniMessageSupport.toLegacy(result))
    }

    fun formatGameMessage(name: String, message: String): String {
        val filtered = filterText(message)
        val result = getChatFormat().fromGame
            .replace("{name}", name)
            .replace("{message}", filtered)
            .replace("{msg}", filtered)
        // 这条消息发到 QQ，QQ 不支持 § 颜色码，所以这里不做颜色转换。
        return result
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
        // 死亡播报发到 QQ，QQ 不支持 § 颜色码，所以这里不做颜色转换。
        return applyPlaceholders(name, result)
    }

    fun formatPlayerEventMessage(format: String, name: String): String {
        val result = format
            .replace("{name}", name)
            .replace("{player}", name)
            .replace("{server}", getServerName())
            .replace("{platform}", getPlatform())
        // 进退服通知发到 QQ，QQ 不支持 § 颜色码，所以这里不做颜色转换。
        return applyPlaceholders(name, result)
    }

    /** 将 & 颜色符号转换为 Minecraft § 颜色代码，用于发进游戏的消息。 */
    fun convertAmpersandColors(text: String): String {
        return text.replace(Regex("&([\\da-fk-orA-FK-OR])")) { "§${it.groupValues[1].lowercase()}" }
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
        val configDirectory = getDataDirectory()?.absoluteFile ?: return null

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

    /** 按群解析的可用命令开关；默认等同全局。 */
    fun getCommandList(groupId: String?): Map<String, Boolean> =
        agent.state.GroupCommandConfigStore.effectiveCommandSwitches(groupId, getCommandList())

    /** 获取命令面板开关列表,命令名 -> 是否推送到 QQ 指令面板,默认为空 */
    fun getCommandMenuList(): Map<String, Boolean> {
        return emptyMap()
    }

    /** 按群解析的命令面板开关；默认等同全局。 */
    fun getCommandMenuList(groupId: String?): Map<String, Boolean> =
        agent.state.GroupCommandConfigStore.effectiveCommandMenuSwitches(groupId, getCommandMenuList())

    /** 数值越小越先进入 QQ 面板；只影响面板，不影响命令执行。 */
    fun getCommandMenuPriorities(): Map<String, Int> = emptyMap()

    /** 按群解析的命令面板优先级；默认等同全局。 */
    fun getCommandMenuPriorities(groupId: String?): Map<String, Int> =
        agent.state.GroupCommandConfigStore.effectiveCommandMenuPriorities(groupId, getCommandMenuPriorities())

    /** 展示管理员命令不放宽实际执行权限。 */
    fun showAdminCommandsInMenu(): Boolean = true

    /** 按群解析的管理员命令面板可见性；默认等同全局。 */
    fun showAdminCommandsInMenu(groupId: String?): Boolean =
        agent.state.GroupCommandConfigStore.effectiveBoolean(
            groupId, "command-panel.show-admin-commands", showAdminCommandsInMenu()
        )

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
    fun getAgentCommandMode(): agent.ai.AgentCommandMode = agent.ai.AgentCommandMode.MANUAL

    /**
     * Agent 请求的 max_tokens 上限；0 表示不在请求体中出现（保持服务端默认）。
     * 部分兼容网关在客户端不带 max_tokens 时默认值很小，长回答会被截断。
     */
    fun getAgentMaxTokens(): Int = 0

    /** Agent 请求的读超时（秒）；推理模型思考时间长时可调大。 */
    fun getAgentTimeoutSeconds(): Int = 120
    fun getAgentMaxSteps(): Int = 15
    fun getAgentMaxWaitSeconds(): Int = 300

    /** Agent 自定义系统提示词；留空 = 使用内置默认提示词。 */
    fun getAgentSystemPrompt(): String = ""

    /** Agent 允许的工具名列表；空 = 全部可用（工具名见 AgentTools）。 */
    fun getAgentTools(): List<String> = emptyList()

    /** Agent 专属搜索 provider；空 = 不启用搜索。 */
    fun getAgentSearchProvider(): String = ""
    fun getAgentSearchApiUrl(): String = ""
    fun getAgentSearchApiKey(): String = ""

    // ------------------------------------------------------ 存储 / 语言 / 调试

    /** 存储后端：file（默认）| sqlite */
    fun getStorageType(): String = "sqlite"

    /** 界面语言：zh_CN（默认）| en_US */
    fun getLanguage(): String = "zh_CN"

    /** Platform command path, including any live conflict fallback. */
    fun getGameCommandPath(command: String): String = "/$command"

    /** 调试模式：输出更详细的诊断日志（便于用户反馈问题时打开）。 */
    fun isDebugEnabled(): Boolean = false

    // ------------------------------------------------------ 群员聊天 AI（与工作 Agent 完全隔离）

    fun getChatAiEnabled(): Boolean = false
    fun getChatAiBaseUrl(): String? = null
    fun getChatAiApiKey(): String? = null
    fun getChatAiModel(): String? = null
    /**
     * 触发前缀（可选）；默认 "ai"。
     *
     * 注意：QQ 客户端会把 "/xxx" 当成未知指令拦截（提示"未找到该命令"），
     * 因此默认不带斜杠；若仍想用 "/ai"，可配置 trigger: "/ai"（部分环境可用）。
     * 空 = 任何 @机器人 的非命令消息都会触发聊天 AI。
     */
    fun getChatAiTrigger(): String = "ai"
    fun getChatAiThink(): Boolean = false
    fun getChatAiThinkLevel(): String = ""
    fun getChatAiThinkParam(): String = "reasoning_effort"
    /** 聊天 AI 自定义系统提示词；留空 = 内置默认。 */
    fun getChatAiSystemPrompt(): String = ""
    /** 聊天 AI 允许的工具；空 = 仅搜索（若启用）。 */
    fun getChatAiTools(): List<String> = emptyList()
    /** 同群共享上下文保留条数。 */
    fun getChatAiMaxContext(): Int = 30

    // ------------------------------------------------------ TTS 语音合成（AI 可调用工具）

    fun isTtsEnabled(): Boolean = false
    /** 统一 API 地址（OpenAI 兼容 chat/completions），默认小米 MiMo。 */
    fun getTtsBaseUrl(): String = "https://api.xiaomimimo.com/v1"
    fun getTtsApiKey(): String = ""
    /** WebUI 对外的公网地址；QQ 服务器经此拉取音频。 */
    fun getTtsPublicBaseUrl(): String = ""
    /** 音频格式：wav / mp3 / pcm16（部分环境语音需 silk，可用第三方转码服务配合）。 */
    fun getTtsFormat(): String = "wav"
    /** 预制音色模型 ID；留空 = AI 无法使用预制音色。 */
    fun getTtsPresetModel(): String = "mimo-v2.5-tts"
    /** 自然语言音色模型 ID；留空 = AI 无法使用。 */
    fun getTtsNaturalModel(): String = ""
    /** 音色克隆模型 ID；留空 = AI 无法使用。 */
    fun getTtsCloneModel(): String = ""
    fun getTtsPresetDefaultVoice(): String = "mimo_default"
    fun getTtsNaturalDefaultVoice(): String = "温柔亲切的中文女声"
    /** 克隆参考音频目录（插件数据目录下的相对路径）。 */
    fun getTtsCloneVoicesDir(): String = "tts-voices"
    /** 默认克隆参考音频文件名；留空 = 需 AI 指定 voice_file。 */
    fun getTtsCloneDefaultVoice(): String = ""
    /** 每条消息（一轮对话）最多合成语音条数。 */
    fun getTtsMaxPerTurn(): Int = 2

    /** 发送策略：auto=被动优先(默认)，active=总是主动发送。 */
    fun getSendPolicy(): String = "auto"

    /** 50 only for explicitly confirmed developer-admin groups, never inferred from bot role. */
    fun getGroupPassiveReplyLimit(groupOpenId: String): Int = 5

    // ------------------------------------------------------ 退群解绑与黑名单

    /** 是否启用群成员校验（退群自动解绑）。需要 QQ 白名单能力 11253 的成员列表接口。 */
    fun isMemberCheckEnabled(): Boolean = false

    fun getMemberCheckIntervalMinutes(): Int = 30

    /** 解绑时是否发 QQ 群通知。 */
    fun isMemberCheckNotifyGroup(): Boolean = true

    /** 机器人黑名单（QQ openid）：其消息不会被处理，入群申请也会被拒绝。 */
    fun getBlacklistOpenIds(): List<String> = emptyList()

    /** 是否直接忽略黑名单用户的消息（false = 只拒绝入群，消息照常进入 AI/转发）。 */
    fun isBlacklistBlockMessages(): Boolean = true

    // ------------------------------------------------------ 命令映射与切换

    /** 映射：原名 → 新名，原名与新名都可用。 */
    fun getCommandAliasMap(): Map<String, String> = emptyMap()

    /** 切换：原名 → 新名，只有新名可用。 */
    fun getCommandAliasRename(): Map<String, String> = emptyMap()
    fun getChatAiCompressTarget(): Int = 4000
    fun getChatAiCompressKeep(): Int = 20
    fun getChatAiSearchProvider(): String = ""
    fun getChatAiSearchApiUrl(): String = ""
    fun getChatAiSearchApiKey(): String = ""

    // ------------------------------------------------------ 全局搜索（聊天 AI / Agent 各自可覆盖）

    /** none | bing（原生必应抓取，无需密钥）| custom */
    fun getSearchProvider(): String = "none"
    fun getSearchApiUrl(): String = ""
    fun getSearchApiKey(): String = ""
    fun getSearchResultCount(): Int = 5

    // ------------------------------------------------------ 网络代理（AI 请求 / 搜索 / 更新检查）

    fun getProxyEnabled(): Boolean = false
    fun getProxyType(): String = "http"
    fun getProxyHost(): String = ""
    fun getProxyPort(): Int = 8080

    // ------------------------------------------------------ 入群自动审批

    /** 是否启用入群自动审批（需要 QQ 白名单能力接口，不可用时自动降级关闭）。 */
    fun isJoinApproveEnabled(): Boolean = false

    /** 入群审批轮询间隔（秒）；0 = 不轮询。 */
    fun getJoinApproveIntervalSeconds(): Int = 30

    /** 入群审批策略列表：[{name, mode: allow|deny, openids[], reject-reason}] */
    fun getJoinApproveStrategies(): List<com.alibaba.fastjson.JSONObject> = emptyList()

    /** 平台服务器诊断文本（TPS/MSPT/内存/运行时长）；未实现返回 null。 */
    fun getServerDiagnostics(): String? = null

    /** 玩家统计文本（击杀/死亡/时长/等级/坐标）；玩家不在线返回 null。 */
    fun getPlayerStatsText(playerName: String): String? = null

    /**
     * 协议/版本支持摘要（如 "协议 767 (1.21) · 支持 1.8.8 – 1.21.11 (ViaVersion)"）；
     * 未实现或检测失败返回 null。
     */
    fun getProtocolSummary(): String? = null

    /** 获取类工具结果只交给 AI，不在群里发卡片（默认开启）。 */
    fun isAgentFetchResultHidden(): Boolean = true

    // ------------------------------------------------------ Agent 深度思考

    /** 深度思考：原生推理模型用，开启后才向接口附加思考参数（档位与参数名都配了才生效）；开启思考引导后不生效。 */
    fun getAgentThink(): Boolean = false

    /**
     * 思考过程的展示方式。
     *
     * log=只写日志（默认，避免刷屏）/ group=只发 QQ 群 / both=两边都发。
     */
    fun getAgentThinkVisibility(): String = "log"

    /**
     * 思考程度档位。
     *
     * none/minimal/low/medium/high/xhigh/max，也可填整数 token 预算（如 10000、-1），
     * 或开关值（true/false、enabled/disabled）。留空表示不向接口附加思考参数。
     */
    fun getAgentThinkLevel(): String = ""

    /**
     * 思考参数名，按模型厂商选择。
     *
     * reasoning_effort / effort / thinking_level / reasoning.effort / thinking.type /
     * thinking_budget / enable_thinking。
     */
    fun getAgentThinkParam(): String = "reasoning_effort"

    /** 思考引导：非推理模型用，提示词加 <think> 模拟深度思考；与深度思考互斥，开启后深度思考相关设置不生效。 */
    fun getAgentThinkGuide(): Boolean = false

    /** 思考引导提示词；留空则使用代码内置的默认提示词。 */
    fun getAgentThinkGuidePrompt(): String = ""

    // ------------------------------------------------------ Agent 上下文压缩

    /** 压缩后的目标 token 数。 */
    fun getAgentCompressTarget(): Int = 4000

    /** 压缩时原样保留的最近消息条数。 */
    fun getAgentCompressKeep(): Int = 20

    /** 压缩提示词，支持 {target} 占位符；留空则使用代码内置的默认提示词。 */
    fun getAgentCompressPrompt(): String = ""

    /** /删除信息 未指定条数时默认删除的条数。 */
    fun getAgentTruncateDrop(): Int = 10

    // ------------------------------------------------------ Agent 配置写入

    /** 写入深度思考开关，供 WebUI 使用；未实现时返回 false。 */
    fun setAgentThink(enabled: Boolean): Boolean = false

    /** 写入思考过程展示方式，供 WebUI 使用；未实现时返回 false。 */
    fun setAgentThinkVisibility(visibility: String): Boolean = false

    /** 写入推理设置（思考程度），供 WebUI 使用；未实现时返回 false。 */
    fun setAgentThinkLevel(level: String): Boolean = false

    /** 写入思考引导开关，供 WebUI 使用；未实现时返回 false。 */
    fun setAgentThinkGuide(enabled: Boolean): Boolean = false

    /** 写入压缩目标 token，供 /压缩token 使用；未实现时返回 false。 */
    fun setAgentCompressTarget(target: Int): Boolean = false

    /** 写入压缩保留条数，供 /压缩保留 使用；未实现时返回 false。 */
    fun setAgentCompressKeep(keep: Int): Boolean = false

    /** 绑定时是否需要游戏内 /qqbind 验证；关闭时直接绑定。 */
    fun getBindingRequireGameVerification(): Boolean = true

    /** 一个 QQ 号最多可绑定的游戏账号数；未配置时只允许绑定一个角色。 */
    fun getMaxBindings(): Int = 1

    /**
     * 账号被锁定后立即断开该角色的在线会话。
     *
     * 由各平台实现（需要踢出在线玩家的能力）；未实现或玩家不在线时返回 false。
     */
    fun kickLockedPlayer(playerName: String): Boolean = false

    /** Send a private moderation warning to one online Minecraft player; never broadcasts it. */
    fun sendPlayerWarning(playerName: String, message: String): Boolean = false

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

    /** 免验证名单：这些玩家无需 QQ 绑定即可进入服务器。 */
    fun getVerifyExemptPlayers(): List<String> = emptyList()

    /** 该玩家是否在免验证名单中（大小写不敏感）。 */
    fun isVerifyExempt(playerName: String): Boolean =
        getVerifyExemptPlayers().any { it.equals(playerName, ignoreCase = true) }

    /**
     * 把玩家加入免验证名单并持久化。
     *
     * 由各平台实现（需要写配置的能力）；未实现时返回 false。
     *
     * @return 是否写入成功
     */
    fun addVerifyExemptPlayer(playerName: String): Boolean = false

    /**
     * 从免验证名单移除玩家并持久化。
     *
     * @return 是否写入成功
     */
    fun removeVerifyExemptPlayer(playerName: String): Boolean = false

    /**
     * 强制绑定场景下，玩家解除绑定后立即断开其在线连接。
     *
     * 由各平台实现（需要踢出在线玩家的能力）；未实现或玩家不在线时返回 false。
     *
     * @return 是否真的踢出了在线玩家
     */
    fun kickUnboundPlayer(playerName: String): Boolean = false

    /** /执行 命令黑名单：禁止通过 /执行 运行的服务器命令（不区分大小写，`bukkit:` 等命名空间前缀会被忽略）。 */
    fun getCommandBlacklist(): List<String> = emptyList()

    /**
     * 把命令名解析成服务器认的规范名，用于黑名单匹配时覆盖别名（如 `/pl` 与 `/plugins`）。
     *
     * 平台层拿不到命令表时返回 null，黑名单退化为按文本规范化后比较。
     */
    fun resolveCommandName(command: String): String? = null

    fun getServerPluginList(): List<String> = emptyList()
    fun getServerCommandHelp(plugin: String?, command: String?): String = "当前平台不支持查询命令信息"
    fun getServerLogs(lines: Int?, keyword: String?): String = "当前平台不支持读取服务端日志"
}

private val DEFAULT_MARKDOWN_FILES = mapOf(
    "queryOnline" to "online.md",
    "motd" to "motd.md"
)
