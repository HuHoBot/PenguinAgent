package cn.huohuas001.huhobotPenguin.spigot.manager

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
        api = plugin.config.getString("motd.api", "")!!,
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

    fun commandSwitches(): Map<String, Boolean> {
        val commandSection = plugin.config.getConfigurationSection("commands") ?: return emptyMap()
        return commandSection.getValues(false).mapValues { (_, value) ->
            value as? Boolean ?: true
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
        private const val CURRENT_CONFIG_VERSION = 4
        private const val CONFIG_VERSION_PATH = "config-version"

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
            "解除认证"
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
            put("motd.api", "")
            put("motd.text", "")
            put("motd.post-img", false)
            put("motd.use-markdown", false)

            put("whitelist.add-command", "whitelist add {name}")
            put("whitelist.del-command", "whitelist remove {name}")
            put("filter-regex", emptyList<String>())
            put("admin.mode", "both")
            put("admin.openids", emptyList<String>())
            put("features.full-amount", false)
            put("audit.base-url", "")
            put("audit.api-key", "")
            put("audit.model", "gpt-4o-mini")
            put("custom-commands", emptyList<Map<String, Any>>())
            put("command-sender", "Hybrid")

            COMMAND_NAMES.forEach { commandName ->
                put("commands.$commandName", true)
            }
        }
    }
}
