package cn.huohuas001.huhobotPenguin.spigot.manager

import cn.huohuas001.bot.agent.AgentCommandMode
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

        var changed = migratePostPrefix()
        changed = removeLegacyMotdOptions() || changed
        changed = removeLegacyInventoryCommandOptions() || changed
        changed = ConfigUpgrader.fillMissing(DEFAULT_VALUES, plugin.config::contains, plugin.config::set) || changed

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
        alwaysForward = plugin.config.getBoolean("player-events.always-forward", false)
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
        postImg = plugin.config.getBoolean("motd.post-img", true),
        useMarkdown = plugin.config.getBoolean("motd.use-markdown", true)
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

    fun isAgentFetchResultHidden(): Boolean =
        plugin.config.getBoolean("agent.hide-fetch-results", true)

    fun bindingRequireGameVerification(): Boolean =
        plugin.config.getBoolean("binding.require-game-verification", false)

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
        private const val CURRENT_CONFIG_VERSION = 8
        private const val CONFIG_VERSION_PATH = "config-version"

        private val COMMANDS_HIDDEN_FROM_MENU = setOf("blockMotd", "unblockMotd")

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
            "chat-format.from-group" to "QQ→游戏 消息格式，可用占位符：{name}、{message}",
            "chat-format.post-chat" to "是否开启群聊转发",
            "chat-format.start-with" to "只有以该内容开头的游戏消息才会转发；留空表示全部转发",
            "player-events.join.enabled" to "是否转发玩家进服通知",
            "player-events.join.format" to "进服通知格式，可用占位符：{name}、{player}、{server}、{platform}",
            "player-events.quit.enabled" to "是否转发玩家退服通知",
            "player-events.quit.format" to "退服通知格式，可用占位符：{name}、{player}、{server}、{platform}",
            "player-events.always-forward" to "是否忽略平台事件的隐藏/取消/登录状态判断，始终转发进退服事件",
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
            "agent.model" to "AI Agent 使用的模型名",
            "agent.command-mode" to "AI Agent 命令执行模式：auto 自动执行 / manual 手动审批",
            "agent.hide-fetch-results" to "获取类工具（插件列表/命令帮助/服务器日志）结果只交给 AI，不在群里发卡片",
            "command-sender" to "命令执行收集模式：Hybrid 同时收集发送者输出和服务端日志",
            "command-blacklist" to "/执行 命令黑名单，禁止通过 /执行 运行的服务器命令列表",
            "command-panel.show-admin-commands" to "在 QQ 面板中向所有人展示管理员命令；执行时仍验证管理员权限",
            "update-check.enabled" to "启动时与 /版本 命令检查 GitHub Release 新版本（忽略 Pre-Release）",
            "update-check.url" to "自定义更新检查数据源（逗号分隔的 URL，可返回纯文本版本号或 JSON）；留空使用内置数据源",
            "placeholder-api.enabled" to "启用 PlaceholderAPI 占位符解析（未安装 PlaceholderAPI 时无效果）",
            "bot.auto-add-groups" to "收到陌生群消息时自动把群 OpenID 写入 bot.groups",
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
            "我的背包",
            "我的末影箱",
            "背包查看",
            "末影箱查看"
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
            put("agent.model", "gpt-4o-mini")
            put("agent.command-mode", "manual")
            put("agent.hide-fetch-results", true)
            put("binding.require-game-verification", false)
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

            COMMAND_NAMES.forEach { commandName ->
                put("commands.$commandName", true)
            }
        }
    }
}
