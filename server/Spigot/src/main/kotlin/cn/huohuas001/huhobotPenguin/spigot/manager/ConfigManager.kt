package cn.huohuas001.huhobotPenguin.spigot.manager

import cn.huohuas001.bot.agent.AgentCommandMode
import cn.huohuas001.bot.agent.AgentPrompts
import cn.huohuas001.bot.provider.AdminMode
import cn.huohuas001.bot.provider.ChatFormat
import cn.huohuas001.bot.provider.ConfigUpgrader
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.bot.provider.Motd
import cn.huohuas001.bot.provider.PlayerEventFormat
import cn.huohuas001.bot.provider.VersionedUpgrade
import cn.huohuas001.bot.provider.WhiteList
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import java.io.File
import kotlin.collections.get

/** Spigot 配置的初始化、升级和强类型读取入口。 */
class ConfigManager(
    private val plugin: HuHoBotSpigot
) {
    val configFile: File
        get() = File(plugin.dataFolder, "config.yml")

    fun initialize() {
        plugin.saveDefaultConfig()
        reload()
    }

    fun reload() {
        mergeDuplicateTopLevelSections()
        plugin.reloadConfig()

        var changed = appendMissingConfigEntries()
        changed = migratePostPrefix() || changed
        changed = removeLegacyMotdOptions() || changed
        changed = removeLegacyInventoryCommandOptions() || changed
        changed = removeLegacyAgentSettingCommandOptions() || changed

        val previousVersion = plugin.config.getInt(CONFIG_VERSION_PATH, 0)

        // 版本化升级：根据配置版本号自动更新已有字段的值
        changed = ConfigUpgrader.upgradeValues(
            currentVersion = previousVersion,
            upgrades = VERSIONED_UPGRADES,
            get = { plugin.config.get(it) },
            set = { path, value -> plugin.config.set(path, value) }
        ) || changed

        if (previousVersion != CURRENT_CONFIG_VERSION) {
            plugin.config.set(CONFIG_VERSION_PATH, CURRENT_CONFIG_VERSION)
            changed = true
        }

        if (changed) {
            plugin.saveConfig()
            plugin.logger.info("配置文件已升级到版本 $CURRENT_CONFIG_VERSION（旧版本：$previousVersion）")
        }

    }

    /**
     * 把新增配置项与注释补进 config.yml。
     *
     * 走文本级增量修改而不是 `config.set` + `saveConfig()`：
     * 前者能真正给旧配置补上模板里新增的键（`config.contains` 会被模板默认值干扰），
     * 并且不会清掉用户文件里的注释。详见 [ConfigMigrator]。
     */
    private fun appendMissingConfigEntries(): Boolean {
        if (!configFile.isFile) return false
        val raw = try {
            configFile.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }

        val result = ConfigMigrator.apply(raw, DEFAULT_VALUES, KEY_COMMENTS, EXTRA_NOTES)
        if (!result.changed) return false

        return try {
            configFile.writeText(result.text, Charsets.UTF_8)
            // 让内存配置与文件同步，后续读取才能看到刚追加的键
            plugin.reloadConfig()
            if (result.addedKeys.isNotEmpty()) {
                plugin.logger.info("已为配置文件追加 ${result.addedKeys.size} 个新配置项：${result.addedKeys.joinToString(", ")}")
            }
            if (result.commentedKeys.isNotEmpty()) {
                plugin.logger.info("已补回 ${result.commentedKeys.size} 项配置注释")
            }
            true
        } catch (error: Exception) {
            plugin.logger.warning("追加新配置项失败: ${error.message}")
            false
        }
    }

    /** 合并旧配置升级器追加的重复顶层段，保留各段中的命令开关和 Agent 设置。 */
    private fun mergeDuplicateTopLevelSections() {
        if (!configFile.isFile) return
        val raw = try { configFile.readText(Charsets.UTF_8) } catch (_: Exception) { return }
        val lines = raw.lines()
        val rootLine = Regex("^[^\\s#][^:]*:.*$")
        val starts = lines.indices.filter { rootLine.matches(lines[it]) }
        if (starts.isEmpty()) return
        val chunks = mutableListOf<List<String>>()
        if (starts.first() > 0) chunks += lines.subList(0, starts.first())
        starts.forEachIndexed { index, start ->
            chunks += lines.subList(start, starts.getOrElse(index + 1) { lines.size })
        }
        fun headerKey(chunk: List<String>): String? {
            val header = chunk.firstOrNull()?.substringBefore(" #")?.trimEnd() ?: return null
            if (!header.endsWith(":")) return null
            val key = header.removeSuffix(":").trim()
            return key.takeIf { it.isNotEmpty() && !it.startsWith("#") }
        }
        val duplicateKeys = chunks.mapNotNull { headerKey(it) }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateKeys.isEmpty()) return
        val childLine = Regex("^  ([^\\s#][^:]*):.*$")
        val firstIndex = duplicateKeys.associateWith { key ->
            chunks.indexOfFirst { headerKey(it) == key }
        }
        val merged = linkedMapOf<String, List<String>>()
        for (key in duplicateKeys) {
            val matching = chunks.filter { headerKey(it) == key }
            if (matching.any { chunk -> chunk.none { childLine.matches(it) } }) {
                plugin.logger.warning("重复配置段 \"$key\" 存在没有子项的块，已中止合并以免丢失内容")
                return
            }
            val values = linkedMapOf<String, List<String>>()
            val first = matching.first()
            val firstChild = first.indexOfFirst { childLine.matches(it) }
            val prefix = first.take(if (firstChild < 0) first.size else firstChild)
            for (chunk in matching) {
                val positions = chunk.indices.filter { childLine.matches(chunk[it]) }
                positions.forEachIndexed { index, start ->
                    val name = childLine.find(chunk[start])!!.groupValues[1]
                    values[name] = chunk.subList(start, positions.getOrElse(index + 1) { chunk.size })
                }
            }
            merged[key] = prefix + values.values.flatten()
        }
        try {
            val backup = File(configFile.parentFile, "config.yml.before-duplicate-merge.bak")
            if (!backup.exists()) configFile.copyTo(backup)
            val normalized = chunks.flatMapIndexed { index, chunk ->
                val key = headerKey(chunk)
                when {
                    key == null || key !in duplicateKeys -> chunk
                    index == firstIndex[key] -> merged.getValue(key)
                    else -> emptyList()
                }
            }
            configFile.writeText(normalized.joinToString("\n").trimEnd() + "\n", Charsets.UTF_8)
            plugin.logger.info("已合并重复配置段: ${duplicateKeys.joinToString()}（原文件已备份）")
        } catch (e: Exception) {
            plugin.logger.warning("合并重复配置段失败: ${e.message}")
        }
    }

    /** 将旧 chat-format.post-prefix 原值迁移到 chat-format.start-with。 */
    private fun migratePostPrefix(): Boolean {
        val legacyPath = "chat-format.post-prefix"
        if (!plugin.config.contains(legacyPath)) return false

        if (!plugin.config.contains("chat-format.start-with")) {
            plugin.config.set(
                "chat-format.start-with",
                plugin.config.getString(legacyPath, "")
            )
        }
        plugin.config.set(legacyPath, null)
        return true
    }

    /** 删除不再使用的 MOTD 配置项。 */
    private fun removeLegacyMotdOptions(): Boolean {
        var changed = false
        listOf(
            "motd.output-online-list",
            "motd.custom-markdown"
        ).forEach { path ->
            if (plugin.config.contains(path)) {
                plugin.config.set(path, null)
                changed = true
            }
        }
        return changed
    }

    /** 清理旧版背包别名和渲染测试命令，避免它们继续进入 QQ 指令面板。 */
    private fun removeLegacyInventoryCommandOptions(): Boolean {
        var changed = false
        listOf(
            "commands.inv",
            "commands.inventory",
            "commands.ec",
            "commands.enderchest",
            "commands.invtest",
            "commands.inventorytest"
        ).forEach { path ->
            if (plugin.config.contains(path)) {
                plugin.config.set(path, null)
                changed = true
            }
        }
        return changed
    }

    /**
     * 删除已下线的 Agent 思考设置命令配置项。
     *
     * 深度思考/展示方式/推理设置/思考引导改为只能在配置文件（WebUI）里设置，
     * 对应的 QQ 命令已移除，这里把旧配置里遗留的 commands.xxx 段清掉，避免残留在面板里。
     */
    private fun removeLegacyAgentSettingCommandOptions(): Boolean {
        var changed = false
        listOf(
            "commands.深度思考",
            "commands.思考展示",
            "commands.思考程度",
            "commands.思考引导"
        ).forEach { path ->
            if (plugin.config.contains(path)) {
                plugin.config.set(path, null)
                changed = true
            }
        }
        return changed
    }

    fun botAppId(): String = plugin.config.getString("bot.app-id").orEmpty()
    fun botSecret(): String = plugin.config.getString("bot.secret").orEmpty()
    fun botName(): String = plugin.config.getString("bot.name", "HuHoBot")!!
    fun serverName(): String = plugin.config.getString("serverName", botName())!!
    fun groupOpenIds(): List<String> = plugin.config.getStringList("bot.groups")
    fun suppressQqBotConsoleOutput(): Boolean =
        plugin.config.getBoolean("bot.suppress-console-output", true)

    fun commandSender(): String = plugin.config.getString("command-sender", "Hybrid")!!

    fun chatFormat(): ChatFormat = ChatFormat(
        fromGame = plugin.config.getString("chat-format.from-game", "[游戏] {name}: {message}")!!,
        fromGroup = plugin.config.getString("chat-format.from-group", "[QQ] {name}: {message}")!!,
        postChat = plugin.config.getBoolean("chat-format.post-chat", true),
        startWith = plugin.config.getString("chat-format.start-with", "")!!
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

    fun onlineListAdminFirst(): Boolean =
        plugin.config.getBoolean("online-list.admin-first", true)

    fun onlineListAdminPermission(): String =
        plugin.config.getString("online-list.admin-permission", "").orEmpty()

    fun onlineListSkinEnabled(): Boolean =
        plugin.config.getBoolean("online-list.skin.enabled", true)

    fun onlineListSkinConnectTimeoutMs(): Int =
        plugin.config.getInt("online-list.skin.connect-timeout-ms", 3000).coerceAtLeast(250)

    fun onlineListSkinReadTimeoutMs(): Int =
        plugin.config.getInt("online-list.skin.read-timeout-ms", 5000).coerceAtLeast(250)

    fun onlineListSkinCacheSize(): Int =
        plugin.config.getInt("online-list.skin.cache-size", 200).coerceAtLeast(8)

    fun onlineListSkinDownloadThreads(): Int =
        plugin.config.getInt("online-list.skin.download-threads", 4).coerceIn(1, 12)

    fun onlineListCooldownSeconds(): Long =
        plugin.config.getLong("online-list.cooldown-seconds", 3L).coerceAtLeast(0L)

    fun onlineListRenderColumns(): Int =
        plugin.config.getInt("online-list.render.columns", 3).coerceIn(1, 3)

    /**
     * 头像 API 模板（离线模式兜底）。{name} / {uuid} 占位符；留空禁用。
     * 默认使用 mc-heads（离线名也能返回 Steve/Alex 头像）。
     */
    fun onlineListAvatarApi(): String =
        (plugin.config.getString("online-list.skin.avatar-api", "https://mc-heads.net/avatar/{name}/64") ?: "")
            .trim()

    /** 是否显示节日 / 事件横幅（内置事件表）。 */
    fun onlineListEventsEnabled(): Boolean =
        plugin.config.getBoolean("online-list.render.events", true)

    /** 在线列表图片主题：dark / light。 */
    fun onlineListTheme(): String =
        (plugin.config.getString("online-list.render.theme", "dark") ?: "dark").trim().lowercase()
            .takeIf { it == "light" } ?: "dark"

    /**
     * 查在线图片中显示的服务器地址（纯域名，不带端口）。
     * 未配置时回退 motd.server-ip；两者都为默认值则不显示。
     */
    fun onlineListAddress(): String {
        val own = (plugin.config.getString("online-list.display.address", "") ?: "").trim()
        if (own.isNotEmpty()) return own
        val motdIp = (plugin.config.getString("motd.server-ip", "") ?: "").trim()
        return if (motdIp.isNotEmpty() && motdIp != "127.0.0.1") motdIp else ""
    }

    /** 是否显示 LuckPerms 前缀（称号）。 */
    fun onlineListShowPrefix(): Boolean =
        plugin.config.getBoolean("online-list.display.prefix", true)

    /** 是否显示 LuckPerms 后缀。 */
    fun onlineListShowSuffix(): Boolean =
        plugin.config.getBoolean("online-list.display.suffix", true)

    /** 玩家名颜色（hex 或颜色名）；留空 = 默认。 */
    fun onlineListNameColor(): String =
        (plugin.config.getString("online-list.display.name-color", "") ?: "").trim()

    /** 这些 LuckPerms 组的成员在列表中按管理员高亮。 */
    fun onlineListAdminGroups(): List<String> =
        plugin.config.getStringList("online-list.display.admin-groups").map(String::trim).filter(String::isNotEmpty)

    /** 在线列表图片的自定义信息行（支持 §/hex/MiniMessage）。 */
    fun onlineListCustomLines(): List<String> =
        plugin.config.getStringList("online-list.render.custom-lines").filter(String::isNotBlank)

    /** 单张图片最多渲染的玩家数；超出部分用 Markdown 文字列表补全。 */
    fun onlineListMaxRenderPlayers(): Int =
        plugin.config.getInt("online-list.max-render-players", 27).coerceIn(1, 200)

    fun onlineListRenderFontFamily(): String =
        plugin.config.getString("online-list.render.font-family", "").orEmpty()

    fun onlineListRenderFooterText(): String =
        plugin.config.getString("online-list.render.footer-text", "POWERED BY HuHoBot").orEmpty()

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

    /** 强制 OTA 清单地址（空 = 不启用）。 */
    fun otaManifestUrl(): String =
        (plugin.config.getString("update.manifest-url", "") ?: "").trim()

    fun otaCheckHours(): Int =
        plugin.config.getInt("update.check-hours", 6)

    fun performanceNotifyEnabled(): Boolean =
        plugin.config.getBoolean("server-notify.performance.enabled", true)

    fun performanceTpsThreshold(): Double =
        plugin.config.getDouble("server-notify.performance.tps-threshold", 15.0)

    fun performanceMemoryThreshold(): Double =
        plugin.config.getDouble("server-notify.performance.memory-threshold", 0.9)

    /** 发送策略：auto=被动优先(默认)，active=总是主动。 */
    fun sendPolicy(): String =
        (plugin.config.getString("message.send-policy", "auto") ?: "auto").trim().lowercase().takeIf { it == "active" } ?: "auto"

    fun motdServerIp(): String = plugin.config.getString("motd.server-ip", "127.0.0.1") ?: "127.0.0.1"

    fun motdServerPort(): Int = plugin.config.getInt("motd.server-port", plugin.server.port)

    fun filterRegexList(): List<String> = plugin.config.getStringList("filter-regex")

    fun adminMode(): AdminMode =
        AdminMode.from(plugin.config.getString("admin.mode")) ?: AdminMode.BOTH

    fun adminOpenIds(): List<String> = plugin.config.getStringList("admin.openids")

    fun fullForwardingByDefault(): Boolean =
        plugin.config.getBoolean("features.full-amount", false)

    fun isAuthenticationEnabled(): Boolean = plugin.config.getBoolean("features.enable-auth", true)

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

    // ── 存储语言调试 ──

    fun storageType(): String = plugin.config.getString("storage.type", "file") ?: "file"

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

    fun agentSearchProvider(): String = plugin.config.getString("agent.search.provider", "") ?: ""

    fun agentSearchApiUrl(): String = plugin.config.getString("agent.search.api-url", "") ?: ""

    fun agentSearchApiKey(): String = plugin.config.getString("agent.search.api-key", "") ?: ""

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

    fun chatAiSearchProvider(): String = plugin.config.getString("chat-ai.search.provider", "") ?: ""

    fun chatAiSearchApiUrl(): String = plugin.config.getString("chat-ai.search.api-url", "") ?: ""

    fun chatAiSearchApiKey(): String = plugin.config.getString("chat-ai.search.api-key", "") ?: ""

    // ── 全局搜索 ──

    fun searchProvider(): String = plugin.config.getString("search.provider", "none") ?: "none"

    fun searchApiUrl(): String = plugin.config.getString("search.api-url", "") ?: ""

    fun searchApiKey(): String = plugin.config.getString("search.api-key", "") ?: ""

    fun searchResultCount(): Int = plugin.config.getInt("search.result-count", 5).coerceIn(1, 10)

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
        private const val CURRENT_CONFIG_VERSION = 14
        private const val CONFIG_VERSION_PATH = "config-version"

        /**
         * 不进 QQ 面板菜单的命令。
         *
         * 面板最多展示 20 条，超出的会被自动截断，所以把低频的运维/管理类命令排除在外，
         * 它们仍然可以在配置里开关，也能通过 /帮助 查看。
         */
        private val COMMANDS_HIDDEN_FROM_MENU = setOf(
            "blockMotd",
            "unblockMotd",
            "压缩",
            "压缩token",
            "压缩保留",
            "删除信息",
            "锁定",
            "解除锁定"
        )

        /** 版本化升级列表：当前配置版本 < toVersion 时自动更新对应字段的值。 */
        private val VERSIONED_UPGRADES = listOf(
            VersionedUpgrade(
                toVersion = 6,
                values = mapOf(
                    "motd.post-img" to true,
                    "motd.use-markdown" to true
                )
            ),
            VersionedUpgrade(
                toVersion = 7,
                values = mapOf(
                    "webui-port" to 5678
                )
            )
        )

        /**
         * 定点补充说明：给已有注释的配置项再加一行补充。
         *
         * 用于说明某项在特定开关下的实际作用范围，避免用户同时维护互斥配置。
         * 以【注意】开头，升级器会在内容变化时替换旧的那行。
         */
        private val EXTRA_NOTES: Map<String, String> = mapOf(
            "binding.require-game-verification" to
                "【注意】强制绑定开启时，被踢出的玩家用 5 位验证码直接完成绑定，不经过本流程；" +
                "但免验证玩家主动绑定时仍走普通流程，是否需要游戏内 /qqbind 验证由本项决定"
        )

        /** 每个配置项的注释说明，用于自动追加时生成可读的 YAML。 */
        private val KEY_COMMENTS: Map<String, String> = mapOf(
            "bot.app-id" to "",
            "bot.secret" to "",
            "bot.name" to "机器人显示名称",
            "bot.groups" to "允许使用的 QQ 群 OpenId 列表",
            "bot.suppress-console-output" to "屏蔽 io.github.kloping.qqbot 直接通过 System.out 输出的调试信息",
            "serverName" to "服务器显示名称，可在进服/退服格式中通过 {server} 使用",
            "webui-port" to "WebUI 管理界面端口，修改后需重启生效",
            "chat-format.from-game" to "游戏→QQ 消息格式，可用占位符：{name}、{message}",
            "chat-format.from-group" to "QQ→游戏 消息格式，可用占位符：{name}、{message}；支持 MiniMessage（<gold>、<#FFAA00>）与 & 颜色码",
            "chat-format.post-chat" to "是否开启群聊转发",
            "chat-format.start-with" to "只有以该内容开头的游戏消息才会转发；留空表示全部转发",
            "player-events.join.enabled" to "是否转发玩家进服通知",
            "player-events.join.format" to "进服通知格式，可用占位符：{name}、{player}、{server}、{platform}",
            "player-events.quit.enabled" to "是否转发玩家退服通知",
            "player-events.quit.format" to "退服通知格式，可用占位符：{name}、{player}、{server}、{platform}",
            "player-events.always-forward" to "是否忽略平台事件的隐藏/取消/登录状态判断，始终转发进退服事件",
            "player-events.death.enabled" to "是否转发玩家死亡播报",
            "player-events.death.format" to "死亡播报格式，可用占位符：{name}、{player}、{server}、{platform}、{message}（中文死亡描述）",
            "server-notify.enabled" to "服务器状态通知总开关，关闭后关机、重启、意外关闭都不发送",
            "server-notify.shutdown.enabled" to "服务器正常关闭时向 QQ 群发送通知",
            "server-notify.shutdown.message" to "正常关闭通知内容，可用占位符：{server}、{platform}",
            "server-notify.restart.enabled" to "服务器重新启动且距上次关闭未超过判定窗口时发送通知",
            "server-notify.restart.message" to "重启通知内容，可用占位符：{server}、{platform}",
            "server-notify.crash.enabled" to "上次运行未正常关闭时，下次启动后发送意外关闭通知",
            "server-notify.crash.message" to "意外关闭通知内容，可用占位符：{server}、{platform}",
            "server-notify.restart-window" to "上次正常关闭后多少秒内重新启动算作重启，建议 120-300",
            "markdown.queryOnline" to "查在线命令使用的 Markdown 模板文件名",
            "motd.server-ip" to "MOTD 查询的服务器地址",
            "motd.server-port" to "MOTD 查询的服务器端口",
            "motd.text" to "MOTD 查询结果文本模板",
            "motd.post-img" to "查在线时是否附带服务器状态图片",
            "motd.use-markdown" to "查在线是否使用 Markdown 卡片格式",
            "whitelist.add-command" to "绑定时自动添加白名单的命令，{name} 替换为玩家名",
            "whitelist.del-command" to "解绑时自动移除白名单的命令，{name} 替换为玩家名",
            "filter-regex" to "消息过滤正则列表，匹配到的内容会被屏蔽",
            "admin.mode" to "管理员判定方式：qq / config / both",
            "admin.openids" to "手动添加的管理员 OpenId 列表",
            "features.full-amount" to "是否默认开启全量聊天转发",
            "features.enable-auth" to "是否启用 QQ 头像认证功能",
            "binding.require-game-verification" to "绑定时是否需要游戏内 /qqbind 验证；关闭时直接绑定无需游戏内操作；强制绑定下仅影响走普通流程的绑定（如免验证玩家主动绑定）",
            "binding.max-bindings" to "一个 QQ 号最多可绑定的游戏账号数，默认 1；大于 1 时可用 /绑定 <游戏ID> 继续绑定，用 /解除绑定 <游戏ID> 解除指定角色",
            "binding.force-bind" to "强制绑定：未绑定玩家进游戏会被踢出并拿到 5 位验证码，必须先在 QQ 群执行 /绑定 <验证码> 才能进入；开启后请勿再叠加白名单插件或 Minecraft 自带白名单",
            "binding.force-bind-groups" to "强制绑定提示里展示的 QQ 群号列表，留空则不提示具体群号",
            "binding.verify-exempt" to "免验证名单：这些玩家无需 QQ 绑定即可进入服务器（受限于设备或环境无法使用 QQ 的人），每行一个玩家名",
            "inventory.render.custom-background.enabled" to "是否启用用户自定义背包底图",
            "inventory.render.custom-background.inventory-file" to "背包底图文件名，文件放在 inventory/backgrounds/ 目录",
            "inventory.render.custom-background.ender-chest-file" to "末影箱底图文件名；留空时复用背包底图",
            "inventory.render.custom-background.fit" to "底图缩放方式：cover 裁切填满，stretch 拉伸填满",
            "audit.base-url" to "OpenAI 兼容审核接口地址，留空则只执行本地敏感词检测",
            "audit.api-key" to "审核接口密钥",
            "audit.model" to "审核使用的模型名",
            "agent.enabled" to "AI Agent 总开关",
            "agent.base-url" to "AI Agent 的 OpenAI 兼容接口地址",
            "agent.api-key" to "AI Agent 的接口密钥",
            "language" to "插件消息语言：zh_CN / en_US",
            "debug" to "调试模式：输出更详细的诊断日志，便于反馈问题",
            "storage.type" to "状态存储后端：file(默认) 或 sqlite",
            "agent.system-prompt" to "Agent 自定义系统提示词；留空使用内置默认",
            "agent.search.provider" to "Agent 联网搜索：bing(原生必应) / custom(自定义API)；留空关闭",
            "agent.search.api-url" to "自定义搜索 API 地址，支持 {query} {count} {apikey} 占位符模板或 POST JSON",
            "agent.search.api-key" to "自定义搜索 API 密钥",
            "chat-ai.enabled" to "群员聊天 AI 总开关（与工作 Agent 完全隔离：独立 API/提示词/工具/上下文）",
            "chat-ai.base-url" to "聊天 AI 的 OpenAI 兼容接口地址",
            "chat-ai.api-key" to "聊天 AI 的接口密钥",
            "chat-ai.model" to "聊天 AI 模型名",
            "chat-ai.trigger" to "聊天 AI 触发前缀（可选）；留空=@机器人 的任何非命令消息",
            "chat-ai.think" to "聊天 AI 思考参数开关",
            "chat-ai.think-level" to "聊天 AI 思考程度档位",
            "chat-ai.think-param" to "聊天 AI 思考参数名",
            "chat-ai.system-prompt" to "聊天 AI 自定义系统提示词；留空使用内置默认",
            "chat-ai.max-context" to "同群共享上下文保留条数（跨群隔离）",
            "chat-ai.compress.target" to "聊天 AI 上下文压缩目标 token",
            "chat-ai.compress.keep" to "聊天 AI 压缩时保留的最近条数",
            "chat-ai.search.provider" to "聊天 AI 搜索：bing / custom；留空关闭",
            "chat-ai.search.api-url" to "聊天 AI 自定义搜索地址",
            "chat-ai.search.api-key" to "聊天 AI 自定义搜索密钥",
            "search.provider" to "全局搜索默认：none / bing / custom",
            "search.api-url" to "全局自定义搜索地址",
            "search.api-key" to "全局自定义搜索密钥",
            "search.result-count" to "搜索返回条数(1-10)",
            "network.proxy.enabled" to "网络代理总开关（AI 请求/搜索/更新检查）",
            "network.proxy.type" to "代理类型：http / socks",
            "network.proxy.host" to "代理主机",
            "network.proxy.port" to "代理端口",
            "join-approve.enabled" to "入群自动审批开关（需 QQ 白名单能力，不可用时自动停用）",
            "join-approve.interval-seconds" to "入群审批轮询间隔秒数(10-600)",
            "proxy-bridge.enabled" to "代理互通桥：把游戏聊天转发给代理端广播其它子服(默认关闭)",
            "agent.model" to "AI Agent 使用的模型名",
            "agent.command-mode" to "AI Agent 命令执行模式：auto 自动执行 / manual 手动审批",
            "agent.hide-fetch-results" to "获取类工具（插件列表/命令帮助/服务器日志）结果只交给 AI，不在群里发卡片",
            "agent.think" to "深度思考：原生推理模型用，开启后才向接口附加思考参数（需同时配置 think-level 与 think-param）；与思考引导互斥",
            "agent.think-visibility" to "思考过程展示方式：log=只写日志 / group=只发 QQ 群 / both=都发",
            "agent.think-param" to "思考参数名，按厂商选：reasoning_effort / effort / thinking_level / reasoning.effort / thinking.type / thinking_budget / enable_thinking",
            "agent.think-level" to "推理设置档位：none/minimal/low/medium/high/xhigh/max，也可填整数 token 预算或 true/false；留空不附加思考参数",
            "agent.think-guide" to "思考引导：非推理模型用，提示词加 [思考] 模拟深度思考；与深度思考互斥，开启后深度思考相关设置不生效",
            "agent.think-guide-prompt" to "思考引导提示词；留空则使用代码内置默认值",
            "agent.context.compress.target" to "上下文压缩后的目标 token 数",
            "agent.context.compress.keep" to "上下文压缩时原样保留的最近消息条数",
            "agent.context.compress.prompt" to "上下文压缩提示词，支持 {target} 占位符；留空则使用代码内置默认值",
            "agent.context.truncate.drop" to "/删除信息 未指定条数时默认删除的条数",
            "command-sender" to "命令执行收集模式：Hybrid 同时收集发送者输出和服务端日志",
            "command-blacklist" to "/执行 命令黑名单，禁止通过 /执行 运行的服务器命令列表",
            "command-panel.show-admin-commands" to "在 QQ 面板中向所有人展示管理员命令；执行时仍验证管理员权限",
            "update-check.enabled" to "启动时与 /版本 命令检查 GitHub Release 新版本（忽略 Pre-Release）",
            "update-check.url" to "自定义更新检查数据源（逗号分隔的 URL，可返回纯文本版本号或 JSON）；留空使用内置数据源",
            "placeholder-api.enabled" to "启用 PlaceholderAPI 占位符解析（未安装 PlaceholderAPI 时无效果）",
            "bot.auto-add-groups" to "收到陌生群消息时自动把群 OpenID 写入 bot.groups",
            "online-list.enabled" to "查在线命令是否使用本地渲染的在线列表图片（关闭后回退到 MOTD 外部 API 图片）",
            "online-list.page-size" to "在线列表每页玩家数（三列布局推荐 27 = 3列×9行）",
            "online-list.max-render-players" to "单张图片最多渲染的玩家数；超出时完整名单以 Markdown 文字补发",
            "online-list.admin-first" to "管理员是否排在在线列表最前面",
            "online-list.admin-permission" to "在线列表管理员判定权限节点；留空时只看 OP",
            "online-list.cooldown-seconds" to "同一 QQ 群两次在线列表请求的最小间隔秒数，防刷屏",
            "online-list.skin.enabled" to "在线列表是否下载并显示玩家皮肤头像",
            "online-list.skin.connect-timeout-ms" to "皮肤下载连接超时（毫秒）",
            "online-list.skin.read-timeout-ms" to "皮肤下载读取超时（毫秒）",
            "online-list.skin.cache-size" to "皮肤头像 LRU 缓存容量",
            "online-list.skin.download-threads" to "皮肤头像并发下载线程数（1-12）",
            "online-list.render.columns" to "在线列表渲染列数（1-3，推荐 3）",
            "online-list.render.font-family" to "在线列表渲染字体；留空自动检测",
            "online-list.render.footer-text" to "在线列表图片页脚文字",
            "online-list.image-max-bytes" to "在线列表图片最大字节数，超出则拒绝发送",
        )

        private val COMMAND_NAMES = listOf(
            "查信息",
            "查管理",
            "加管理",
            "删管理",
            "管理方式",
            "添加白名单",
            "删除白名单",
            "查白名单",
            "查在线",
            "在线服务器",
            "发信息",
            "执行命令",
            "执行",
            "管理员执行",
            "全量",
            "认证",
            "解除认证",
            "agent",
            "压缩",
            "压缩token",
            "压缩保留",
            "删除信息",
            "我的背包",
            "我的末影箱",
            "背包查看",
            "末影箱查看",
            "强制解绑",
            "添加免验证",
            "取消免验证",
            "锁定",
            "解除锁定"
        )

        private val DEFAULT_VALUES: Map<String, Any> = buildMap {
            put(CONFIG_VERSION_PATH, CURRENT_CONFIG_VERSION)
            put("bot.app-id", "")
            put("bot.secret", "")
            put("bot.name", "HuHoBot")
            put("bot.groups", emptyList<String>())
            put("bot.suppress-console-output", true)
            put("serverName", "HuHoBot")
            put("webui-port", 5678)

            put("chat-format.from-game", "[游戏] {name}: {message}")
            put("chat-format.from-group", "[QQ] {name}: {message}")
            put("chat-format.post-chat", true)
            put("chat-format.start-with", "")

            put("player-events.join.enabled", true)
            put("player-events.join.format", "[游戏] {name} 加入了服务器")
            put("player-events.quit.enabled", true)
            put("player-events.quit.format", "[游戏] {name} 离开了服务器")
            put("player-events.always-forward", false)
            put("player-events.death.enabled", true)
            put("player-events.death.format", "[游戏] {message}")

            put("server-notify.enabled", true)
            put("server-notify.shutdown.enabled", true)
            put("server-notify.shutdown.message", "服务器已正常关闭")
            put("server-notify.restart.enabled", true)
            put("server-notify.restart.message", "服务器已重启完成")
            put("server-notify.crash.enabled", true)
            put("server-notify.crash.message", "服务器意外关闭（崩溃或被强制结束），请管理员检查控制台")
            put("server-notify.restart-window", 180)

            put("markdown.queryOnline", "online.md")

            put("motd.server-ip", "127.0.0.1")
            put("motd.server-port", 25565)
            put("motd.text", "")
            put("motd.post-img", true)
            put("motd.use-markdown", true)

            put("whitelist.add-command", "whitelist add {name}")
            put("whitelist.del-command", "whitelist remove {name}")
            put("filter-regex", emptyList<String>())
            put("admin.mode", "both")
            put("admin.openids", emptyList<String>())
            put("features.full-amount", false)
            put("features.enable-auth", true)
            put("audit.base-url", "")
            put("audit.api-key", "")
            put("audit.model", "gpt-4o-mini")
            put("agent.enabled", false)
            put("agent.base-url", "")
            put("agent.api-key", "")

            put("language", "zh_CN")
            put("debug", false)
            put("storage.type", "file")

            put("agent.system-prompt", "")
            put("agent.search.provider", "")
            put("agent.search.api-url", "")
            put("agent.search.api-key", "")

            put("chat-ai.enabled", false)
            put("chat-ai.base-url", "")
            put("chat-ai.api-key", "")
            put("chat-ai.model", "gpt-4o-mini")
            put("chat-ai.trigger", "")
            put("chat-ai.think", false)
            put("chat-ai.think-level", "")
            put("chat-ai.think-param", "reasoning_effort")
            put("chat-ai.system-prompt", "")
            put("chat-ai.max-context", 30)
            put("chat-ai.compress.target", 4000)
            put("chat-ai.compress.keep", 20)
            put("chat-ai.search.provider", "")
            put("chat-ai.search.api-url", "")
            put("chat-ai.search.api-key", "")

            put("tts.enabled", false)
            put("tts.base-url", "https://api.xiaomimimo.com/v1")
            put("tts.api-key", "")
            put("tts.public-base-url", "")
            put("tts.format", "wav")
            put("tts.preset-model", "mimo-v2.5-tts")
            put("tts.natural-model", "mimo-v2.5-tts-voicedesign")
            put("tts.clone-model", "mimo-v2.5-tts-voiceclone")
            put("tts.preset-default-voice", "mimo_default")
            put("tts.natural-default-voice", "温柔亲切的中文女声")
            put("tts.clone-voices-dir", "tts-voices")
            put("tts.clone-default-voice", "")
            put("tts.max-per-turn", 2)

            put("command-aliases.map", LinkedHashMap<String, String>())
            put("command-aliases.rename", LinkedHashMap<String, String>())

            put("search.provider", "none")
            put("search.api-url", "")
            put("search.api-key", "")
            put("search.result-count", 5)

            put("network.proxy.enabled", false)
            put("network.proxy.type", "http")
            put("network.proxy.host", "")
            put("network.proxy.port", 8080)

            put("join-approve.enabled", false)
            put("join-approve.interval-seconds", 30)

            put("proxy-bridge.enabled", false)
            put("agent.model", "gpt-4o-mini")
            put("agent.command-mode", "manual")
            put("agent.hide-fetch-results", true)
            put("agent.think", false)
            put("agent.think-visibility", "log")
            put("agent.think-level", "")
            put("agent.think-param", "reasoning_effort")
            put("agent.think-guide", false)
            put("agent.think-guide-prompt", AgentPrompts.THINK_GUIDE)
            put("agent.context.compress.target", 4000)
            put("agent.context.compress.keep", 20)
            put("agent.context.compress.prompt", AgentPrompts.CONTEXT_COMPRESS)
            put("agent.context.truncate.drop", 10)
            put("binding.require-game-verification", true)
            put("binding.max-bindings", 1)
            put("binding.force-bind", false)
            put("binding.force-bind-groups", emptyList<String>())
            put("binding.verify-exempt", emptyList<String>())
            put("inventory.render.custom-background.enabled", false)
            put("inventory.render.custom-background.inventory-file", "inventory.png")
            put("inventory.render.custom-background.ender-chest-file", "")
            put("inventory.render.custom-background.fit", "cover")
            put("command-blacklist", emptyList<String>())
            put("custom-commands", emptyList<Map<String, Any>>())
            put("command-sender", "Hybrid")
            put("command-panel.show-admin-commands", true)
            put("update-check.enabled", true)
            put("update-check.url", "")
            put("placeholder-api.enabled", true)
            put("bot.auto-add-groups", true)

            // 在线列表渲染
            put("online-list.enabled", true)
            put("online-list.page-size", 27)
            put("online-list.max-render-players", 27)
            put("online-list.render.custom-lines", emptyList<String>())
            put("online-list.display.prefix", true)
            put("online-list.display.suffix", true)
            put("online-list.display.name-color", "")
            put("online-list.display.admin-groups", emptyList<String>())
            put("online-list.display.address", "")
            put("online-list.render.theme", "dark")
            put("online-list.render.events", true)
            put("online-list.skin.avatar-api", "https://mc-heads.net/avatar/{name}/64")
            put("message.send-policy", "auto")
            put("binding.member-check.enabled", false)
            put("binding.member-check.interval-minutes", 30)
            put("binding.member-check.notify-group", true)
            put("moderation.blacklist", emptyList<String>())
            put("moderation.block-messages", true)
            put("update.manifest-url", "")
            put("update.check-hours", 6)
            put("server-notify.performance.enabled", true)
            put("server-notify.performance.tps-threshold", 15.0)
            put("server-notify.performance.memory-threshold", 0.9)
            put("online-list.admin-first", true)
            put("online-list.admin-permission", "")
            put("online-list.cooldown-seconds", 3L)
            put("online-list.skin.enabled", true)
            put("online-list.skin.connect-timeout-ms", 3000)
            put("online-list.skin.read-timeout-ms", 5000)
            put("online-list.skin.cache-size", 200)
            put("online-list.skin.download-threads", 4)
            put("online-list.render.columns", 3)
            put("online-list.render.font-family", "")
            put("online-list.render.footer-text", "POWERED BY HuHoBot")
            put("online-list.image-max-bytes", 20 * 1024 * 1024)

            COMMAND_NAMES.forEach { commandName ->
                put("commands.$commandName", true)
            }
        }
    }
}
