package cn.huohuas001.huhobotPenguin.spigot.manager

import cn.huohuas001.bot.agent.AgentCommandMode
import cn.huohuas001.bot.provider.AdminMode
import cn.huohuas001.bot.provider.ChatFormat
import cn.huohuas001.bot.provider.ConfigUpgrader
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.bot.provider.Motd
import cn.huohuas001.bot.provider.PlayerEventFormat
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
        plugin.reloadConfig()

        var changed = migratePostPrefix()
        changed = removeLegacyMotdOptions() || changed
        changed = ConfigUpgrader.fillMissing(DEFAULT_VALUES, plugin.config::contains, plugin.config::set) || changed

        val previousVersion = plugin.config.getInt(CONFIG_VERSION_PATH, 0)
        if (previousVersion != CURRENT_CONFIG_VERSION) {
            plugin.config.set(CONFIG_VERSION_PATH, CURRENT_CONFIG_VERSION)
            changed = true
        }

        if (changed) {
            plugin.saveConfig()
            plugin.logger.info("配置文件已升级到版本 $CURRENT_CONFIG_VERSION（旧版本：$previousVersion）")
        }

        // 直接检查文件文本，绕过 Bukkit contains/get 的嵌套路径 bug
        appendMissingConfigKeys()
    }

    /**
     * 读取原始 config.yml 文本，逐项检查 DEFAULT_VALUES 中的 key 是否作为 YAML 键存在。
     * 缺失的按 section 分组追加到文件末尾并重载。
     */
    private fun appendMissingConfigKeys() {
        val raw = try { configFile.readText(Charsets.UTF_8) } catch (_: Exception) { return }
        val toAdd = mutableListOf<Pair<String, String>>()

        for ((path, defaultValue) in DEFAULT_VALUES) {
            val leafKey = path.substringAfterLast('.')
            if (!raw.contains("$leafKey:") && !raw.contains("$leafKey =")) {
                val yamlValue = when (defaultValue) {
                    is Boolean -> defaultValue.toString()
                    is Int -> defaultValue.toString()
                    is String -> "\"$defaultValue\""
                    is List<*> -> "[]"
                    else -> defaultValue.toString()
                }
                toAdd.add(path to yamlValue)
            }
        }

        if (toAdd.isEmpty()) return

        try {
            val appended = buildString {
                append(raw.trimEnd())
                append("\n")
                val grouped = toAdd.groupBy { it.first.substringBeforeLast('.', "") }
                for ((section, entries) in grouped) {
                    append("\n")
                    if (section.isNotEmpty()) {
                        append("$section:\n")
                        for ((path, value) in entries) {
                            val leaf = path.substringAfterLast('.')
                            val comment = KEY_COMMENTS[path]
                            if (!comment.isNullOrEmpty()) {
                                append("  # $comment\n")
                            }
                            append("  $leaf: $value\n")
                        }
                    } else {
                        for ((path, value) in entries) {
                            val comment = KEY_COMMENTS[path]
                            if (!comment.isNullOrEmpty()) {
                                append("# $comment\n")
                            }
                            append("$path: $value\n")
                        }
                    }
                }
            }
            configFile.writeText(appended, Charsets.UTF_8)
            plugin.reloadConfig()
            plugin.logger.info("配置文件已追加 ${toAdd.size} 个升级字段")
        } catch (e: Exception) {
            plugin.logger.warning("追加配置字段失败: ${e.message}")
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

    fun botAppId(): String = plugin.config.getString("bot.app-id").orEmpty()
    fun botSecret(): String = plugin.config.getString("bot.secret").orEmpty()
    fun botName(): String = plugin.config.getString("bot.name", "HuHoBot")!!
    fun serverName(): String = plugin.config.getString("serverName", botName())!!
    fun groupOpenIds(): List<String> = plugin.config.getStringList("bot.groups")
    fun suppressQqBotConsoleOutput(): Boolean =
        plugin.config.getBoolean("bot.suppress-console-output", true)

    fun commandSender(): String = plugin.config.getString("command-sender", "Hybrid")!!

    fun chatFormat(): ChatFormat = ChatFormat(
        fromGame = plugin.config.getString("chat-format.from-game", "[游戏] {message}")!!,
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
        )!!
    )

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
        postImg = plugin.config.getBoolean("motd.post-img", false),
        useMarkdown = plugin.config.getBoolean("motd.use-markdown", false)
    )

    fun filterRegexList(): List<String> = plugin.config.getStringList("filter-regex")

    fun adminMode(): AdminMode =
        AdminMode.from(plugin.config.getString("admin.mode")) ?: AdminMode.BOTH

    fun adminOpenIds(): List<String> = plugin.config.getStringList("admin.openids")

    fun fullForwardingByDefault(): Boolean =
        plugin.config.getBoolean("features.full-amount", false)

    fun isAuthenticationEnabled(): Boolean = plugin.config.getBoolean("features.enable-auth", true)

    fun commandSwitches(): Map<String, Boolean> {
        val commandSection = plugin.config.getConfigurationSection("commands") ?: return emptyMap()
        return commandSection.getValues(false).mapValues { (_, value) ->
            value as? Boolean ?: true
        }
    }

    fun commandMenuSwitches(): Map<String, Boolean> {
        val commandSection = plugin.config.getConfigurationSection("commands") ?: return emptyMap()
        return commandSection.getKeys(false).associateWith { commandName ->
            val path = "commands.$commandName"
            val default = commandName !in COMMANDS_HIDDEN_FROM_MENU
            val settings = plugin.config.getConfigurationSection(path)
            if (settings == null) default
            else {
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

    fun bindingRequireGameVerification(): Boolean =
        plugin.config.getBoolean("binding.require-game-verification", false)

    private fun parseCustomCommand(values: Map<*, *>): CustomCommandDetail? {
        val key = values["key"]?.toString()?.trim().orEmpty()
        val command = values["command"]?.toString()?.trim().orEmpty()
        val permission = values["permission"]?.toString()?.toIntOrNull() ?: 0

        if (key.isEmpty() || command.isEmpty()) {
            plugin.logger.warning("忽略缺少 key 或 command 的自定义命令配置: $values")
            return null
        }
        return CustomCommandDetail(key, command, permission)
    }

    companion object {
        private const val CURRENT_CONFIG_VERSION = 5
        private const val CONFIG_VERSION_PATH = "config-version"

        private val COMMANDS_HIDDEN_FROM_MENU = setOf("blockMotd", "unblockMotd")

        /** 每个配置项的注释说明，用于自动追加时生成可读的 YAML。 */
        private val KEY_COMMENTS: Map<String, String> = mapOf(
            "bot.app-id" to "",
            "bot.secret" to "",
            "bot.name" to "机器人显示名称",
            "bot.groups" to "允许使用的 QQ 群 OpenId 列表",
            "bot.suppress-console-output" to "屏蔽 io.github.kloping.qqbot 直接通过 System.out 输出的调试信息",
            "serverName" to "服务器显示名称，可在进服/退服格式中通过 {server} 使用",
            "chat-format.from-game" to "游戏→QQ 消息格式，可用占位符：{name}、{message}",
            "chat-format.from-group" to "QQ→游戏 消息格式，可用占位符：{name}、{message}",
            "chat-format.post-chat" to "是否开启群聊转发",
            "chat-format.start-with" to "只有以该内容开头的游戏消息才会转发；留空表示全部转发",
            "player-events.join.enabled" to "是否转发玩家进服通知",
            "player-events.join.format" to "进服通知格式，可用占位符：{name}、{player}、{server}、{platform}",
            "player-events.quit.enabled" to "是否转发玩家退服通知",
            "player-events.quit.format" to "退服通知格式，可用占位符：{name}、{player}、{server}、{platform}",
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
            "binding.require-game-verification" to "绑定时是否需要游戏内 /qqbind 验证；关闭时直接绑定无需游戏内操作",
            "audit.base-url" to "OpenAI 兼容审核接口地址，留空则只执行本地敏感词检测",
            "audit.api-key" to "审核接口密钥",
            "audit.model" to "审核使用的模型名",
            "agent.enabled" to "AI Agent 总开关",
            "agent.base-url" to "AI Agent 的 OpenAI 兼容接口地址",
            "agent.api-key" to "AI Agent 的接口密钥",
            "agent.model" to "AI Agent 使用的模型名",
            "agent.command-mode" to "AI Agent 命令执行模式：auto 自动执行 / manual 手动审批",
            "command-sender" to "命令执行收集模式：Hybrid 同时收集发送者输出和服务端日志",
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
            "agent"
        )

        private val DEFAULT_VALUES: Map<String, Any> = buildMap {
            put(CONFIG_VERSION_PATH, CURRENT_CONFIG_VERSION)
            put("bot.app-id", "")
            put("bot.secret", "")
            put("bot.name", "HuHoBot")
            put("bot.groups", emptyList<String>())
            put("bot.suppress-console-output", true)
            put("serverName", "HuHoBot")

            put("chat-format.from-game", "[游戏] {message}")
            put("chat-format.from-group", "[QQ] {name}: {message}")
            put("chat-format.post-chat", true)
            put("chat-format.start-with", "")

            put("player-events.join.enabled", true)
            put("player-events.join.format", "[游戏] {name} 加入了服务器")
            put("player-events.quit.enabled", true)
            put("player-events.quit.format", "[游戏] {name} 离开了服务器")

            put("markdown.queryOnline", "online.md")

            put("motd.server-ip", "127.0.0.1")
            put("motd.server-port", 25565)
            put("motd.text", "")
            put("motd.post-img", false)
            put("motd.use-markdown", false)

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
            put("agent.model", "gpt-4o-mini")
            put("agent.command-mode", "manual")
            put("binding.require-game-verification", false)
            put("custom-commands", emptyList<Map<String, Any>>())
            put("command-sender", "Hybrid")

            COMMAND_NAMES.forEach { commandName ->
                put("commands.$commandName", true)
            }
        }
    }
}
