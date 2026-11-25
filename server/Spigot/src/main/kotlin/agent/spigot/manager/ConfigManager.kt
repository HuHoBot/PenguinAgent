package agent.spigot.manager

import agent.ai.AgentCommandMode
import agent.ai.AgentPrompts
import agent.provider.AdminMode
import agent.provider.ChatFormat
import agent.provider.CustomCommandDetail
import agent.provider.Motd
import agent.provider.PlayerEventFormat
import agent.provider.WhiteList
import agent.spigot.HuHoBotSpigot
import java.io.File
import kotlin.collections.get

/** Spigot 配置的初始化、升级和强类型读取入口。 */
class ConfigManager(
    private val plugin: HuHoBotSpigot
) {
    val configFile: File
        get() = File(plugin.dataFolder, "config.yml")

    @Volatile private var active: org.bukkit.configuration.file.YamlConfiguration? = null
    @Volatile private var prepared: org.bukkit.configuration.file.YamlConfiguration? = null
    @Volatile private var savedValues: Map<String, String?> = emptyMap()
    @Volatile private var changedOnActivation: Set<String> = emptySet()
    private val defaultText: String by lazy {
        plugin.getResource("config.yml")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("JAR 内缺少 config.yml 默认配置")
    }

    fun currentConfig(): org.bukkit.configuration.file.YamlConfiguration? = active

    fun initialize() {
        plugin.saveDefaultConfig()
        check(prepareReloadFile()) { "config.yml 无效；已保留原文件，请修复 YAML 后重新启用插件" }
        activateReload()
    }

    /** Parsing and disk work happen on the IO worker; publication is just a reference swap. */
    fun prepareReloadFile(): Boolean = try {
        if (!configFile.isFile) plugin.saveDefaultConfig()
        ConfigUpgrade.apply(configFile, defaultText)
        val loaded = org.bukkit.configuration.file.YamlConfiguration()
        loaded.loadFromString(configFile.readText(Charsets.UTF_8))
        loaded.setDefaults(org.bukkit.configuration.file.YamlConfiguration().apply { loadFromString(defaultText) })
        prepared = loaded
        true
    } catch (error: Exception) {
        prepared = null
        plugin.logger.severe("配置文件无效或无法安全升级；原文件未被覆盖，继续使用当前已加载配置：${error.message}")
        false
    }

    fun activateReload() {
        val next = prepared ?: return
        prepared = null
        val nextValues = encodeValues(next)
        val previousValues = savedValues
        changedOnActivation = if (active == null) nextValues.keys else
            (previousValues.keys + nextValues.keys).filter { previousValues[it] != nextValues[it] }.toSet()
        savedValues = nextValues
        active = next
    }

    /** Paths changed by the most recent activation; used to avoid rebuilding unrelated subsystems. */
    fun changedOnLastActivation(): Set<String> = changedOnActivation

    fun updateFile(changes: Map<String, Any?>) = ConfigUpgrade.update(configFile, defaultText, changes)

    fun pendingChanges(): Map<String, Any?> {
        val current = active ?: return emptyMap()
        return current.getValues(true).filter { (path, value) ->
            value !is org.bukkit.configuration.ConfigurationSection &&
                com.alibaba.fastjson.JSON.toJSONString(value) != savedValues[path]
        }
    }

    private fun encodeValues(config: org.bukkit.configuration.file.YamlConfiguration): Map<String, String?> =
        config.getValues(true).filterValues { it !is org.bukkit.configuration.ConfigurationSection }
            .mapValues { com.alibaba.fastjson.JSON.toJSONString(it.value) }

    fun reload() {
        if (prepareReloadFile()) activateReload()
    }

    fun botAppId(): String = plugin.config.getString("bot.app-id").orEmpty()
    fun botSecret(): String = plugin.config.getString("bot.secret").orEmpty()
    fun botName(): String = plugin.config.getString("bot.name", "HuHoBot") ?: "HuHoBot"
    /** 返回服主配置的原始名称；图片渲染器解析颜色，游戏提示在发送前转成 § 码。 */
    fun serverName(): String = plugin.config.getString("serverName")?.takeIf(String::isNotBlank) ?: botName()
    fun groupOpenIds(): List<String> = plugin.config.getStringList("bot.groups")
    fun suppressQqBotConsoleOutput(): Boolean =
        plugin.config.getBoolean("bot.suppress-console-output", true)

    fun commandSender(): String = plugin.config.getString("command-sender", "Hybrid")!!

    fun chatFormat(): ChatFormat = ChatFormat(
        fromGame = plugin.config.getString("chat-format.from-game", "[游戏] {name}: {message}")!!,
        fromGroup = plugin.config.getString("chat-format.from-group", "[QQ] {name}: {message}")!!,
        postChat = plugin.config.getBoolean("chat-format.post-chat", true),
        startWith = plugin.config.getString("chat-format.start-with", "")!!,
        stripColourCodes = plugin.config.getBoolean("chat-format.strip-colour-codes", true)
    )

    fun playerEventFormat(): PlayerEventFormat = PlayerEventFormat(
        joinEnabled = plugin.config.getBoolean("player-events.join.enabled", true),
        joinFormat = plugin.config.getString(
            "player-events.join.format",
            "[游戏] {name} 加入了服务器"
        )!!,
        quitEnabled = plugin.config.getBoolean("player-events.quit.enabled", true),
        quitFormat = plugin.config.getString(
            "player-events.quit.format",
            "[游戏] {name} 离开了服务器"
        )!!,
        alwaysForward = plugin.config.getBoolean("player-events.always-forward", false),
        deathEnabled = plugin.config.getBoolean("player-events.death.enabled", true),
        deathFormat = plugin.config.getString(
            "player-events.death.format",
            "[游戏] {message}"
        )!!
    )

    fun serverNotifyEnabled(): Boolean =
        plugin.config.getBoolean("server-notify.enabled", true)

    fun serverNotifyShutdownEnabled(): Boolean =
        serverNotifyEnabled() && plugin.config.getBoolean("server-notify.shutdown.enabled", true)

    fun serverNotifyShutdownMessage(): String = plugin.config.getString(
        "server-notify.shutdown.message",
        "服务器已正常关闭"
    )!!

    fun serverNotifyRestartEnabled(): Boolean =
        serverNotifyEnabled() && plugin.config.getBoolean("server-notify.restart.enabled", true)

    fun serverNotifyRestartMessage(): String = plugin.config.getString(
        "server-notify.restart.message",
        "服务器已重启完成"
    )!!

    fun serverNotifyCrashEnabled(): Boolean =
        serverNotifyEnabled() && plugin.config.getBoolean("server-notify.crash.enabled", true)

    fun serverNotifyCrashMessage(): String = plugin.config.getString(
        "server-notify.crash.message",
        "服务器意外关闭（崩溃或被强制结束），请管理员检查控制台"
    )!!

    /** 上次正常关闭后多少秒内重新启动算作一次重启。 */
    fun serverNotifyRestartWindow(): Long =
        plugin.config.getLong("server-notify.restart-window", 180L).coerceIn(0L, 3600L)

    // ---------------------------------------------------------------- 在线列表渲染

    fun onlineListEnabled(): Boolean =
        plugin.config.getBoolean("online-list.enabled", true)

    fun onlineListPageSize(): Int =
        plugin.config.getInt("online-list.page-size", 27).coerceIn(1, 300)

    /** 图片顶部标题，可直接配置 MiniMessage、§ 或 & 颜色码。 */
    fun onlineListTitle(): String = plugin.config.getString("online-list.title")
        ?.takeIf(String::isNotBlank) ?: "<gradient:#62d6df:#ad9cff>在线列表</gradient>"

    fun onlineListAdminPermission(): String =
        plugin.config.getString("online-list.admin-permission", "").orEmpty()

    fun onlineListSkinEnabled(): Boolean =
        plugin.config.getBoolean("online-list.skin.enabled", true)

    /** null = auto (runtime classes decide); explicit values are for hybrid forks. */
    fun serverModeOverride(): ServerMode? = ServerMode.parse(plugin.config.getString("compatibility.server-mode", "auto"))

    /** In offline mode the server cannot own real textures, so sites would only return lookalikes. */
    fun offlineDefaultSkins(): Boolean =
        plugin.config.getBoolean("online-list.skin.offline-default-skins", true)

    fun chatLogEnabled(): Boolean = plugin.config.getBoolean("chat-log.enabled", true)
    fun chatLogKeep(): Int = plugin.config.getInt("chat-log.keep", 300).coerceIn(30, 5000)
    fun chatLogPageSize(): Int = plugin.config.getInt("chat-log.page-size", 30).coerceIn(5, 100)
    fun chatLogMaxLength(): Int = plugin.config.getInt("chat-log.max-length", 80).coerceIn(20, 300)
    fun chatLogCooldownSeconds(): Long = plugin.config.getLong("chat-log.cooldown-seconds", 3).coerceIn(0, 60)

    fun onlineListSkinTimeoutMs(): Int {
        val c = plugin.config
        val legacyConnect = c.getInt("online-list.skin.connect-timeout-ms", 0)
        val legacyRead = c.getInt("online-list.skin.read-timeout-ms", 0)
        val configured = when {
            c.contains("online-list.skin.timeout-ms", true) -> c.getInt("online-list.skin.timeout-ms", 5000)
            legacyConnect > 0 || legacyRead > 0 -> maxOf(legacyConnect, legacyRead)
            else -> 5000
        }
        return configured.coerceIn(500, 15000)
    }

    fun onlineListSkinCacheSize(): Int =
        plugin.config.getInt("online-list.skin.cache-size", 200).coerceAtLeast(8)

    fun onlineListSkinDownloadThreads(): Int =
        plugin.config.getInt("online-list.skin.download-threads", 4).coerceIn(1, 12)

    fun onlineListCooldownSeconds(): Long =
        plugin.config.getLong("online-list.cooldown-seconds", 3L).coerceAtLeast(0L)

    fun onlineListRenderColumns(): Int {
        val c = plugin.config
        val path = when {
            c.contains("online-list.columns", true) -> "online-list.columns"
            c.contains("online-list.render.columns", true) -> "online-list.render.columns"
            else -> "online-list.columns"
        }
        return c.getInt(path, 3).coerceIn(1, 3)
    }

    /** 仅显示主动配置的连接地址，不自动探测，不回退到 MOTD 查询地址。 */
    fun onlineListAddress(): String {
        val c = plugin.config
        val path = when {
            c.contains("online-list.address", true) -> "online-list.address"
            c.contains("online-list.display.address", true) -> "online-list.display.address"
            else -> "online-list.address"
        }
        return c.getString(path, "").orEmpty().trim()
    }

    /** 是否显示 LuckPerms 前缀（称号）。 */
    fun onlineListBrand():String=plugin.config.getString("online-list.display.core-name","").orEmpty()
    fun onlineListPrefixTemplate():String=plugin.config.getString("online-list.display.prefix-format","").orEmpty()
    fun onlineListSuffixTemplate():String=plugin.config.getString("online-list.display.suffix-format","").orEmpty()

    fun onlineListShowPrefix(): Boolean =
        plugin.config.getBoolean("online-list.display.prefix", true)

    /** 是否显示 LuckPerms 后缀。 */
    fun onlineListShowSuffix(): Boolean =
        plugin.config.getBoolean("online-list.display.suffix", true)

    /** 这些 LuckPerms 组的成员在列表中按管理员高亮。 */
    fun onlineListAdminGroups(): List<String> =
        plugin.config.getStringList("online-list.display.admin-groups").map(String::trim).filter(String::isNotEmpty)

    /**
     * 在线列表图片的自定义信息行（支持 §/hex/MiniMessage）。
     * 同时兼容写在 online-list.display.custom-lines 的旧/错位配置（并给出提示）。
     */
    fun onlineListCustomLines(): List<String> {
        val c = plugin.config
        val path = when {
            c.contains("online-list.custom-lines", true) -> "online-list.custom-lines"
            c.contains("online-list.display.custom-lines", true) -> "online-list.display.custom-lines"
            c.contains("online-list.render.custom-lines", true) -> "online-list.render.custom-lines"
            else -> "online-list.custom-lines"
        }
        return c.getStringList(path).filter { agent.spigot.onlinelist.McTextParser.plainText(it).isNotBlank() }.distinct().take(6)
    }

    fun onlineListSkinSources(): List<Map<String,String>> {
        val rows = plugin.config.getMapList("online-list.skin.sources").map { row ->
            row.entries.associate { it.key.toString() to it.value.toString() }
        }
        // Keep the old single-URL setting working without deleting it from the YAML.
        val legacy = plugin.config.getString("online-list.skin.avatar-api").orEmpty().trim()
        if (legacy.isBlank() || legacy == "https://mc-heads.net/avatar/{name}/64") {
            return rows.ifEmpty { agent.spigot.onlinelist.SkinSources.defaults() }
        }
        if (rows.any { it["url"] == legacy }) return rows
        return listOf(mapOf("type" to "avatar", "url" to legacy)) + rows
    }

    /** 单张图片最多渲染的玩家数；超出部分用 Markdown 文字列表补全。 */
    fun onlineListMaxRenderPlayers(): Int =
        plugin.config.getInt("online-list.max-render-players", 27).coerceIn(1, 200)

    fun onlineListRenderFontFamily(): String =
        plugin.config.getString("online-list.render.font-family", "").orEmpty()

    fun onlineListRenderFooterText(): String {
        val c = plugin.config
        val path = when {
            c.contains("online-list.footer-text", true) -> "online-list.footer-text"
            c.contains("online-list.render.footer-text", true) -> "online-list.render.footer-text"
            else -> "online-list.footer-text"
        }
        return c.getString(path, "Powered by HuHoBot").orEmpty()
    }

    fun onlineListImageMaxBytes(): Int =
        plugin.config.getInt("online-list.image-max-bytes", 20 * 1024 * 1024)

    fun markdownFiles(): Map<String, String> {
        val configured = plugin.config.getConfigurationSection("markdown")
            ?.getValues(false)
            ?.mapNotNull { (key, value) -> value?.toString()?.let { key to it } }
            ?.toMap()
            .orEmpty()
        return mapOf("queryOnline" to "online.md") + configured
    }

    fun whiteList(): WhiteList = WhiteList(
        addCommand = plugin.config.getString(
            "whitelist.add-command",
            "whitelist add {name}"
        )!!,
        delCommand = plugin.config.getString(
            "whitelist.del-command",
            "whitelist remove {name}"
        )!!
    )

    fun motd(): Motd = Motd(
        serverIP = plugin.config.getString("motd.server-ip", "127.0.0.1")!!,
        serverPort = plugin.config.getInt("motd.server-port", plugin.server.port),
        api = plugin.config.getString("motd.api")?.takeIf(String::isNotBlank)
            ?: "https://motd.minebbs.com/api/status?ip={ip}&stype=auto",
        text = plugin.config.getString("motd.text", "")!!,
        postImg = plugin.config.getBoolean("motd.post-img", true),
        useMarkdown = plugin.config.getBoolean("motd.use-markdown", true)
    )

    fun motdText(): String = plugin.config.getString("motd.text", "") ?: ""

    fun memberCheckEnabled(): Boolean = plugin.config.getBoolean("binding.member-check.enabled", false)

    fun memberCheckIntervalMinutes(): Int = plugin.config.getInt("binding.member-check.interval-minutes", 30)

    fun memberCheckNotifyGroup(): Boolean = plugin.config.getBoolean("binding.member-check.notify-group", true)

    fun blacklistOpenIds(): List<String> =
        plugin.config.getStringList("moderation.blacklist").map(String::trim).filter(String::isNotEmpty)

    fun blacklistBlockMessages(): Boolean = plugin.config.getBoolean("moderation.block-messages", true)

    fun performanceNotifyEnabled(): Boolean =
        plugin.config.getBoolean("server-notify.performance.enabled", true)

    fun performanceTpsThreshold(): Double =
        plugin.config.getDouble("server-notify.performance.tps-threshold", 15.0)

    fun performanceMemoryThreshold(): Double =
        plugin.config.getDouble("server-notify.performance.memory-threshold", 0.9)

    /** 发送策略：auto=被动优先(默认)，active=总是主动。 */
    fun sendPolicy(): String =
        (plugin.config.getString("message.send-policy", "auto") ?: "auto").trim().lowercase().takeIf { it == "active" } ?: "auto"

    fun groupPassiveReplyLimit(groupOpenId: String): Int =
        if (groupOpenId in plugin.config.getStringList("message.passive-reply.enhanced-groups")) 50 else 5

    fun motdServerIp(): String = plugin.config.getString("motd.server-ip", "127.0.0.1") ?: "127.0.0.1"

    fun motdServerPort(): Int = plugin.config.getInt("motd.server-port", plugin.server.port)

    fun filterRegexList(): List<String> = plugin.config.getStringList("filter-regex")

    fun adminMode(): AdminMode =
        AdminMode.from(plugin.config.getString("admin.mode")) ?: AdminMode.BOTH

    fun adminOpenIds(): List<String> = plugin.config.getStringList("admin.openids")

    fun fullForwardingByDefault(): Boolean =
        plugin.config.getBoolean("features.full-amount", false)

    fun commandSwitches(): Map<String, Boolean> {
        val commandSection = plugin.config.getConfigurationSection("commands") ?: return emptyMap()
        return commandSection.getKeys(false).associateWith { commandName ->
            plugin.config.getBoolean("commands.$commandName.enable",
                plugin.config.getBoolean("commands.$commandName", true))
        }
    }

    fun commandMenuSwitches(): Map<String, Boolean> {
        val commandSection = plugin.config.getConfigurationSection("commands") ?: return emptyMap()
        return commandSection.getKeys(false).associateWith { commandName ->
            val path = "commands.$commandName"
            val default = commandName !in COMMANDS_HIDDEN_FROM_MENU
            val settings = plugin.config.getConfigurationSection(path)
            if (settings == null) {
                // 简单布尔值格式：commands.xxx: true/false
                val enabled = plugin.config.getBoolean(path, true)
                if (!enabled) false else default
            } else {
                // 复杂格式：commands.xxx.pushMenu: true
                val pushMenu = settings.get("pushMenu")
                when (pushMenu) {
                    is Boolean -> pushMenu
                    is Number -> pushMenu.toInt() != 0
                    is String -> pushMenu.toBooleanStrictOrNull() ?: default
                    else -> default
                }
            }
        }
    }

    fun commandMenuPriorities(): Map<String, Int> {
        val section = plugin.config.getConfigurationSection("commands") ?: return emptyMap()
        return section.getKeys(false).associateWith { commandName ->
            plugin.config.getInt("commands.$commandName.priority", if (commandName == "agent") 0 else 100)
                .coerceIn(0, 999)
        }
    }

    fun showAdminCommandsInMenu(): Boolean =
        plugin.config.getBoolean("command-panel.show-admin-commands", true)

    fun isUpdateCheckEnabled(): Boolean =
        plugin.config.getBoolean("update-check.enabled", true)

    fun updateCheckUrls(): String = plugin.config.getString("update-check.url", "").orEmpty()

    fun isPlaceholderApiEnabled(): Boolean =
        plugin.config.getBoolean("placeholder-api.enabled", true)

    fun isAutoAddGroupsEnabled(): Boolean =
        plugin.config.getBoolean("bot.auto-add-groups", true)

    fun auditBaseUrl(): String? =
        plugin.config.getString("audit.base-url")?.takeIf(String::isNotBlank)

    fun auditApiKey(): String? =
        plugin.config.getString("audit.api-key")?.takeIf(String::isNotBlank)

    fun auditModel(): String? =
        plugin.config.getString("audit.model")?.takeIf(String::isNotBlank)

    fun moderationAiReviewEnabled(): Boolean =
        plugin.config.getBoolean("moderation.sensitive-words.ai-review", false)

    fun moderationAutoRecallEnabled(): Boolean =
        plugin.config.getBoolean("moderation.sensitive-words.auto-recall", false)

    fun moderationAllowNone(): Boolean =
        plugin.config.getBoolean("moderation.sensitive-words.allow-none", false)

    fun customCommands(): List<CustomCommandDetail> =
        plugin.config.getMapList("custom-commands").mapNotNull(::parseCustomCommand)

    fun agentEnabled(): Boolean = plugin.config.getBoolean("agent.enabled", false)
    fun agentBaseUrl(): String? = plugin.config.getString("agent.base-url")?.takeIf(String::isNotBlank)
    fun agentApiKey(): String? = plugin.config.getString("agent.api-key")?.takeIf(String::isNotBlank)
    fun agentModel(): String? = plugin.config.getString("agent.model")?.takeIf(String::isNotBlank)
    fun agentCommandMode(): AgentCommandMode =
        AgentCommandMode.from(plugin.config.getString("agent.command-mode")) ?: AgentCommandMode.MANUAL

    fun agentMaxTokens(): Int = plugin.config.getInt("agent.max-tokens", 0)

    fun agentTimeoutSeconds(): Int = plugin.config.getInt("agent.timeout-seconds", 120)

    fun agentMaxSteps(): Int = plugin.config.getInt("agent.max-steps", 15).coerceIn(1, 100)

    fun agentMaxWaitSeconds(): Int = plugin.config.getInt("agent.max-wait-seconds", 300).coerceIn(1, 86_400)

    // ── 存储语言调试 ──

    fun storageType(): String = plugin.config.getString("storage.type", "sqlite") ?: "sqlite"

    fun language(): String = plugin.config.getString("language", "zh_CN") ?: "zh_CN"

    fun debugEnabled(): Boolean = plugin.config.getBoolean("debug", false)

    /** 读取二级字符串映射表（如 command-aliases.map）。 */
    private fun readStringMap(path: String): Map<String, String> {
        val section = plugin.config.getConfigurationSection(path) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in section.getKeys(false)) {
            section.getString(key)?.trim()?.takeIf(String::isNotEmpty)?.let { out[key.trim()] = it }
        }
        return out
    }

    fun commandAliasMap(): Map<String, String> = readStringMap("command-aliases.map")

    fun commandAliasRename(): Map<String, String> = readStringMap("command-aliases.rename")

    // ── Agent 扩展 ──

    fun agentSystemPrompt(): String = plugin.config.getString("agent.system-prompt", "") ?: ""

    fun agentTools(): List<String> =
        (plugin.config.getList("agent.tools") ?: emptyList<Any>()).mapNotNull { it?.toString()?.trim() }
            .filter(String::isNotEmpty)

    fun agentSearchProvider(): String = plugin.config.getString("agent.search.provider", "").orEmpty().ifBlank { plugin.config.getString("search.provider", "").orEmpty() }

    fun agentSearchApiUrl(): String = plugin.config.getString("agent.search.api-url", "").orEmpty().ifBlank { plugin.config.getString("search.api-url", "").orEmpty() }

    fun agentSearchApiKey(): String = plugin.config.getString("agent.search.api-key", "").orEmpty().ifBlank { plugin.config.getString("search.api-key", "").orEmpty() }

    // ── 群员聊天 AI ──

    fun chatAiEnabled(): Boolean = plugin.config.getBoolean("chat-ai.enabled", false)

    fun chatAiBaseUrl(): String? = plugin.config.getString("chat-ai.base-url")?.takeIf(String::isNotBlank)

    fun chatAiApiKey(): String? = plugin.config.getString("chat-ai.api-key")?.takeIf(String::isNotBlank)

    fun chatAiModel(): String? = plugin.config.getString("chat-ai.model")?.takeIf(String::isNotBlank)

    fun chatAiTrigger(): String = plugin.config.getString("chat-ai.trigger", "ai") ?: "ai"

    // ── TTS ──
    fun ttsEnabled(): Boolean = plugin.config.getBoolean("tts.enabled", false)
    fun ttsBaseUrl(): String = plugin.config.getString("tts.base-url", "https://api.xiaomimimo.com/v1") ?: "https://api.xiaomimimo.com/v1"
    fun ttsApiKey(): String = plugin.config.getString("tts.api-key", "") ?: ""
    fun ttsPublicBaseUrl(): String = plugin.config.getString("tts.public-base-url", "") ?: ""
    fun ttsFormat(): String = plugin.config.getString("tts.format", "wav") ?: "wav"
    fun ttsPresetModel(): String = plugin.config.getString("tts.preset-model", "") ?: ""
    fun ttsNaturalModel(): String = plugin.config.getString("tts.natural-model", "") ?: ""
    fun ttsCloneModel(): String = plugin.config.getString("tts.clone-model", "") ?: ""
    fun ttsPresetDefaultVoice(): String = plugin.config.getString("tts.preset-default-voice", "mimo_default") ?: "mimo_default"
    fun ttsNaturalDefaultVoice(): String = plugin.config.getString("tts.natural-default-voice", "温柔亲切的中文女声") ?: "温柔亲切的中文女声"
    fun ttsCloneVoicesDir(): String = plugin.config.getString("tts.clone-voices-dir", "tts-voices") ?: "tts-voices"
    fun ttsCloneDefaultVoice(): String = plugin.config.getString("tts.clone-default-voice", "") ?: ""
    fun ttsMaxPerTurn(): Int = plugin.config.getInt("tts.max-per-turn", 2)

    /** 语音上传方式：url=需要 public-base-url；qq-upload=尝试通过 QQ 上传（暂受限） */
    fun getTtsUploadMode(): String = plugin.config.getString("tts.upload-mode", "url")?.trim().orEmpty()

    fun chatAiThink(): Boolean = plugin.config.getBoolean("chat-ai.think", false)

    fun chatAiThinkLevel(): String = plugin.config.getString("chat-ai.think-level", "") ?: ""

    fun chatAiThinkParam(): String = plugin.config.getString("chat-ai.think-param", "reasoning_effort") ?: "reasoning_effort"

    fun chatAiSystemPrompt(): String = plugin.config.getString("chat-ai.system-prompt", "") ?: ""

    fun chatAiTools(): List<String> =
        (plugin.config.getList("chat-ai.tools") ?: emptyList<Any>()).mapNotNull { it?.toString()?.trim() }
            .filter(String::isNotEmpty)

    fun chatAiMaxContext(): Int = plugin.config.getInt("chat-ai.max-context", 30)

    fun chatAiCompressTarget(): Int = plugin.config.getInt("chat-ai.compress.target", 4000)

    fun chatAiCompressKeep(): Int = plugin.config.getInt("chat-ai.compress.keep", 20)

    fun chatAiSearchProvider(): String = plugin.config.getString("chat-ai.search.provider", "").orEmpty().ifBlank { plugin.config.getString("search.provider", "").orEmpty() }

    fun chatAiSearchApiUrl(): String = plugin.config.getString("chat-ai.search.api-url", "").orEmpty().ifBlank { plugin.config.getString("search.api-url", "").orEmpty() }

    fun chatAiSearchApiKey(): String = plugin.config.getString("chat-ai.search.api-key", "").orEmpty().ifBlank { plugin.config.getString("search.api-key", "").orEmpty() }

    // ── 全局搜索 ──

    fun searchProvider(): String = plugin.config.getString("search.provider", "none") ?: "none"

    fun searchApiUrl(): String = plugin.config.getString("search.api-url", "") ?: ""

    fun searchApiKey(): String = plugin.config.getString("search.api-key", "") ?: ""

    fun searchResultCount(): Int = plugin.config.getInt("search.result-count", 5).coerceIn(1, 10)

    fun searchFetchPages(): Boolean = plugin.config.getBoolean("search.fetch-pages", false)

    fun searchJsCode(): String = plugin.config.getString("search.js-code", "") ?: ""

    // ── 网络代理 ──

    fun proxyEnabled(): Boolean = plugin.config.getBoolean("network.proxy.enabled", false)

    fun proxyType(): String = plugin.config.getString("network.proxy.type", "http") ?: "http"

    fun proxyHost(): String = plugin.config.getString("network.proxy.host", "") ?: ""

    fun proxyPort(): Int = plugin.config.getInt("network.proxy.port", 8080)

    // ── 入群自动审批 ──

    fun joinApproveEnabled(): Boolean = plugin.config.getBoolean("join-approve.enabled", false)

    fun joinApproveIntervalSeconds(): Int = plugin.config.getInt("join-approve.interval-seconds", 30)

    fun proxyBridgeEnabled(): Boolean = plugin.config.getBoolean("proxy-bridge.enabled", false)

    fun joinApproveStrategies(): List<com.alibaba.fastjson.JSONObject> =
        plugin.config.getMapList("join-approve.strategies").mapNotNull { map ->
            val obj = com.alibaba.fastjson.JSONObject()
            for ((k, v) in map) {
                if (k is String) obj[k] = v
            }
            if (obj.getString("mode").isNullOrBlank()) null else obj
        }

    fun isAgentFetchResultHidden(): Boolean =
        plugin.config.getBoolean("agent.hide-fetch-results", true)

    fun agentThink(): Boolean = plugin.config.getBoolean("agent.think", false)

    fun agentThinkVisibility(): String =
        plugin.config.getString("agent.think-visibility")?.takeIf { it.isNotBlank() } ?: "log"

    fun agentThinkLevel(): String = plugin.config.getString("agent.think-level").orEmpty()

    fun agentThinkParam(): String =
        plugin.config.getString("agent.think-param")?.takeIf { it.isNotBlank() } ?: "reasoning_effort"

    fun agentThinkGuide(): Boolean = plugin.config.getBoolean("agent.think-guide", false)

    fun agentThinkGuidePrompt(): String = plugin.config.getString("agent.think-guide-prompt").orEmpty()

    fun agentCompressTarget(): Int = plugin.config.getInt("agent.context.compress.target", 4000)

    fun agentCompressKeep(): Int = plugin.config.getInt("agent.context.compress.keep", 20)

    fun agentCompressPrompt(): String = plugin.config.getString("agent.context.compress.prompt").orEmpty()

    fun agentTruncateDrop(): Int = plugin.config.getInt("agent.context.truncate.drop", 10)

    fun bindingRequireGameVerification(): Boolean =
        plugin.config.getBoolean("binding.require-game-verification", true)

    /** 一个 QQ 号最多可绑定的角色数；默认 1，与旧版行为一致。 */
    fun maxBindings(): Int = plugin.config.getInt("binding.max-bindings", 1).coerceIn(1, 100)

    fun isForceBindEnabled(): Boolean = plugin.config.getBoolean("binding.force-bind", false)

    fun forceBindGroups(): List<String> =
        plugin.config.getStringList("binding.force-bind-groups").map { it.trim() }.filter { it.isNotEmpty() }

    fun verifyExemptPlayers(): List<String> =
        plugin.config.getStringList("binding.verify-exempt").map { it.trim() }.filter { it.isNotEmpty() }

    fun customInventoryBackgroundEnabled(): Boolean =
        plugin.config.getBoolean("inventory.render.custom-background.enabled", false)

    fun customInventoryBackgroundFile(): String =
        plugin.config.getString("inventory.render.custom-background.inventory-file", "inventory.png")!!

    fun customEnderChestBackgroundFile(): String =
        plugin.config.getString("inventory.render.custom-background.ender-chest-file", "")!!

    fun customInventoryBackgroundFit(): String =
        plugin.config.getString("inventory.render.custom-background.fit", "cover")!!

    fun commandBlacklist(): List<String> =
        plugin.config.getStringList("command-blacklist").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun parseCustomCommand(values: Map<*, *>): CustomCommandDetail? {
        val key = values["key"]?.toString()?.trim().orEmpty()
        val command = values["command"]?.toString()?.trim().orEmpty()
        val permission = values["permission"]?.toString()?.toIntOrNull() ?: 0
        val pushMenu = values["pushMenu"]?.toString()?.toBooleanStrictOrNull()
            ?: values["push-menu"]?.toString()?.toBooleanStrictOrNull()
            ?: true

        if (key.isEmpty() || command.isEmpty()) {
            plugin.logger.warning("忽略缺少 key 或 command 的自定义命令配置: $values")
            return null
        }
        return CustomCommandDetail(key, command, permission, pushMenu)
    }

    companion object {
        /** 面板容量有限，这些低频运维指令不放进按钮菜单。 */
        private val COMMANDS_HIDDEN_FROM_MENU = setOf(
            "blockMotd","unblockMotd","压缩","压缩token","压缩保留","删除信息","锁定","解除锁定"
        )



    }
}
