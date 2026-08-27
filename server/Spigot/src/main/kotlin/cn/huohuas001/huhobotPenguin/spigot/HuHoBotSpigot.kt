package cn.huohuas001.huhobotPenguin.spigot

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.agent.AgentCommandMode
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.spigot.commands.AtCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.QqBindCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.BukkitConsoleSender
import cn.huohuas001.huhobotPenguin.spigot.commands.CommandOutputAppender
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.HybridCommandExecutor
import cn.huohuas001.huhobotPenguin.spigot.events.GameChat
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.entities.ex.Keyboard
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class HuHoBotSpigot : JavaPlugin(), HuHoBot {
    companion object {
        private var instance: HuHoBotSpigot? = null
        fun getInstance(): HuHoBotSpigot? = instance
    }

    private lateinit var configManager: ConfigManager

    override fun onEnable() {
        instance = this
        configManager = ConfigManager(this)
        configManager.initialize()
        initializeRuntime()
        logCommandExecutor()
        val command = HuHoBotCommand(this)
        getCommand("huhobot")?.apply {
            setExecutor(command)
            tabCompleter = command
        } ?: log_error("无法注册 /huhobot 命令，请检查 plugin.yml")
        server.pluginManager.registerEvents(GameChat(), this)
        val atCommand = AtCommand()
        getCommand("at")?.apply {
            setExecutor(atCommand)
            tabCompleter = atCommand
        }
        val qqBindCommand = QqBindCommand()
        getCommand("qqbind")?.apply {
            setExecutor(qqBindCommand)
        } ?: log_error("无法注册 /qqbind 命令，请检查 plugin.yml")
        log_info("HuHoBot Penguin 已加载")
    }

    override fun onDisable() {
        instance = null
        shutdownRuntime()
        CommandOutputAppender.removeInstance()
    }

    private fun logCommandExecutor() {
        val useHybridExecutor = configManager.commandSender().equals("Hybrid", ignoreCase = true)
        if (useHybridExecutor) {
            log_info("已启用混合控制台命令执行器")
        } else {
            log_info("已启用模拟控制台命令执行器")
        }
    }

    override fun reloadPluginConfig() {
        configManager.reload()
        reloadRuntimeConfig()
        logCommandExecutor()
    }

    override fun createCommandExecutor(): HExecution =
        if (configManager.commandSender().equals("Hybrid", ignoreCase = true)) {
            HybridCommandExecutor(this)
        } else {
            BukkitConsoleSender(this)
        }

    override fun broadcastMessage(msg: String) {
        server.scheduler.runTask(this, Runnable { Bukkit.broadcastMessage(msg) })
    }

    override fun broadcastMessage(msg: String, highlightedPlayers: List<String>) {
        server.scheduler.runTask(this, Runnable {
            Bukkit.broadcastMessage(msg)
            if (highlightedPlayers.isNotEmpty()) {
                for (playerName in highlightedPlayers) {
                    val player = server.getPlayer(playerName) ?: continue
                    if (player.isOnline) {
                        player.playSound(
                            player.location,
                            org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,
                            1.0f,
                            1.0f
                        )
                    }
                }
            }
        })
    }

    /** 注册运行时自定义命令，并按 pushMenu 更新 QQ 命令面板。 */
    @JvmOverloads
    fun registerBotCommand(
        key: String,
        command: String,
        permission: Int = 0,
        pushMenu: Boolean = true
    ): Boolean {
        val registered = CustomCommandRegistry.register(
            CustomCommandDetail(key, command, permission, pushMenu)
        )
        return registered
    }

    /** 注销运行时自定义命令，并按需刷新 QQ 命令面板。 */
    fun unregisterBotCommand(key: String): Boolean {
        val removed = CustomCommandRegistry.unregister(key)
        return removed
    }

    /** 向配置中的所有 QQ 群发送普通文本。 */
    fun sendBotText(text: String) = sendText(text)

    /** 主动向指定 QQ 群发送普通文本。 */
    fun sendBotText(groupOpenId: String, text: String): Boolean =
        QClient.sendTextToGroup(groupOpenId, text).let { true }

    /** 向配置中的所有 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(markdown: String, keyboard: Keyboard? = null) = sendMarkdown(markdown, keyboard)

    /** 主动向指定 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(
        groupOpenId: String,
        markdown: String,
        keyboard: Keyboard? = null
    ): Boolean = QClient.sendMarkdownToGroup(groupOpenId, markdown, keyboard).let { true }

    override fun isAuthenticationEnabled(): Boolean = configManager.isAuthenticationEnabled()
    override fun getCommandMenuList(): Map<String, Boolean> = configManager.commandMenuSwitches()

    override fun submit(task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTask(this, task))

    override fun submitAsync(task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskAsynchronously(this, task))

    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskLater(this, task, delay))

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskTimer(this, task, delay, period))

    override fun getOnlineList(): List<String> = server.onlinePlayers.map { it.name }.toMutableList()
    override fun getConfigFile(): File = configManager.configFile
    override fun getBotAppId(): String = configManager.botAppId()
    override fun getBotSecret(): String = configManager.botSecret()
    override fun getChatFormat(): ChatFormat = configManager.chatFormat()
    override fun getPlayerEventFormat(): PlayerEventFormat = configManager.playerEventFormat()
    override fun getMarkdownFiles(): Map<String, String> = configManager.markdownFiles()
    override fun getMotd(): Motd = configManager.motd()
    override fun getWhiteList(): WhiteList = configManager.whiteList()
    override fun getFilterRegexList(): List<String> = configManager.filterRegexList()
    override fun getAdminMode(): AdminMode = configManager.adminMode()
    override fun getAdminList(): List<String> = configManager.adminOpenIds()
    override fun getGroupOpenIdList(): List<String> = configManager.groupOpenIds()
    override fun shouldSuppressQqBotConsoleOutput(): Boolean =
        configManager.suppressQqBotConsoleOutput()

    override fun getFullAmount(): Boolean = configManager.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = configManager.commandSwitches()
    override fun getAuditBaseUrl(): String? = configManager.auditBaseUrl()
    override fun getAuditApiKey(): String? = configManager.auditApiKey()
    override fun getAuditModel(): String? = configManager.auditModel()
    override fun getCustomCommands(): List<CustomCommandDetail> = configManager.customCommands()
    override fun getBotName(): String = configManager.botName()
    override fun getServerName(): String = configManager.serverName()
    override fun getPlatform(): String = "Spigot"
    override fun getPluginVersion(): String = description.version
    override fun getServerVersion(): String = server.version

    override fun getAgentEnabled(): Boolean = configManager.agentEnabled()
    override fun getAgentBaseUrl(): String? = configManager.agentBaseUrl()
    override fun getAgentApiKey(): String? = configManager.agentApiKey()
    override fun getAgentModel(): String? = configManager.agentModel()
    override fun getAgentCommandMode(): AgentCommandMode = configManager.agentCommandMode()

    override fun getBindingRequireGameVerification(): Boolean = configManager.bindingRequireGameVerification()

    override fun getCommandBlacklist(): List<String> = configManager.commandBlacklist()

    override fun getWebUiConfigValues(): Map<String, Any?> =
        config.getValues(true)

    override fun applyWebUiConfigChanges(changes: JSONObject): Boolean {
        return try {
            changes.forEach { (path, value) ->
                config.set(path, convertJsonValue(value))
            }
            saveConfig()
            reloadPluginConfig()
            true
        } catch (error: Exception) {
            log_error("WebUI 保存配置失败: ${error.message}")
            false
        }
    }

    /** 将 fastjson 值转换为 Bukkit 配置可接受的 Java 类型。 */
    private fun convertJsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.entries.associate { (k, v) -> k to convertJsonValue(v) }
        is JSONArray -> value.map { convertJsonValue(it) }
        else -> value
    }

    override fun getServerPluginList(): List<String> =
        server.pluginManager.plugins.map { it.name }.sorted()

    override fun getServerCommandHelp(pluginName: String?, commandName: String?): String {
        return when {
            commandName != null -> formatSingleCommand(commandName)
            pluginName != null -> formatPluginCommands(pluginName)
            else -> formatAllCommands()
        }
    }

    private fun formatSingleCommand(commandName: String): String {
        val cmd = findCommand(commandName)
            ?: return "未找到命令: /$commandName"
        return formatCommandDetail(cmd)
    }

    /** 查找命令：优先插件命令，其次命令表（兼容原版命令与别名）。 */
    private fun findCommand(commandName: String): Command? {
        server.getPluginCommand(commandName)?.let { return it }
        val commandMap = resolveCommandMapObject() ?: return null
        try {
            (commandMap as? CommandMap)?.getCommand(commandName)?.let { return it }
        } catch (_: Exception) {
        }
        return readKnownCommands(commandMap)?.let { known ->
            known[commandName.lowercase()]
                ?: known.values.firstOrNull { it.name.equals(commandName, true) }
        }
    }

    /** 统一格式化命令详情，兼容插件命令与原版（默认）命令。 */
    private fun formatCommandDetail(cmd: Command): String = buildString {
        appendLine("命令：/${cmd.name}")
        cmd.aliases.takeIf { it.isNotEmpty() }?.let { appendLine("别名：${it.joinToString(", ")}") }
        if (!cmd.description.isNullOrBlank()) appendLine("描述：${cmd.description}")
        val usage = cmd.usage?.trim()
        if (!usage.isNullOrBlank() && usage != "/<command>") appendLine("用法：$usage")
        cmd.permission?.takeIf { it.isNotBlank() }?.let { appendLine("权限：$it") }
        val pluginCommand = cmd as? PluginCommand
        if (pluginCommand != null) {
            pluginCommand.plugin?.let { appendLine("所属插件：${it.name}") }
        } else {
            appendLine("类型：原版命令（服务器版本：${server.version}）")
            appendLine("提示：原版命令的具体参数与语法请按服务器版本使用，可在游戏内执行 /help <命令> 查看。")
        }
    }.trimEnd()

    private fun formatPluginCommands(pluginName: String): String {
        val plugin = server.pluginManager.getPlugin(pluginName)
            ?: return "未找到插件：$pluginName（可用插件：${getServerPluginList().joinToString(", ")}）"

        val commands = plugin.description.commands
        if (commands.isEmpty()) return "插件 ${plugin.name} 没有注册命令。"
        return buildString {
            appendLine("插件 ${plugin.name} 注册的命令：")
            commands.toSortedMap().forEach { (label, meta) ->
                val desc = meta["description"]?.toString()?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                appendLine("/$label$desc")
            }
        }.trimEnd()
    }

    private fun formatAllCommands(): String {
        val commandMap = resolveCommandMapObject()
        val known = commandMap?.let { readKnownCommands(it) }
        val perPlugin = sortedMapOf<String, MutableList<String>>()

        if (known != null) {
            val seen = mutableSetOf<String>()
            known.values.forEach { cmd ->
                val label = cmd.name ?: return@forEach
                if (!seen.add(label)) return@forEach
                val owner = (cmd as? PluginCommand)?.plugin?.name ?: "原版命令"
                perPlugin.getOrPut(owner) { mutableListOf() }.add(label)
            }
        } else {
            server.pluginManager.plugins.forEach { plugin ->
                val labels = plugin.description.commands.keys
                if (labels.isNotEmpty()) {
                    perPlugin.getOrPut(plugin.name) { mutableListOf() }.addAll(labels)
                }
            }
        }

        if (perPlugin.isEmpty()) return "服务器没有可查询的命令。"
        return buildString {
            appendLine("服务器命令概览：")
            perPlugin.forEach { (owner, labels) ->
                appendLine("- $owner：${labels.sorted().joinToString(", ")}")
            }
            if (known == null) appendLine("（仅列出插件命令，原版命令表访问失败）")
        }.trimEnd()
    }

    /** 从命令表对象中读取全部命令映射，兼容不同版本字段名。 */
    private fun readKnownCommands(commandMap: Any): Map<String, Command>? {
        for (fieldName in listOf("knownCommands", "commandMap")) {
            try {
                val field = commandMap.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val map = field.get(commandMap) as? Map<String, Command>
                if (map != null) return map
            } catch (error: Exception) {
                log_debug("Agent 读取 $fieldName 字段失败: ${error.message}")
            }
        }
        try {
            val method = commandMap.javaClass.getMethod("getKnownCommands")
            @Suppress("UNCHECKED_CAST")
            val map = method.invoke(commandMap) as? Map<String, Command>
            if (map != null) return map
        } catch (error: Exception) {
            log_debug("Agent 调用 getKnownCommands 失败: ${error.message}")
        }
        return null
    }

    private fun resolveCommandMapObject(): Any? {
        // 1) 直接调用 getCommandMap()
        try {
            val method = server.javaClass.getMethod("getCommandMap")
            return method.invoke(server) ?: run {
                log_debug("Agent getCommandMap() 返回 null")
                null
            }
        } catch (error: Exception) {
            log_debug("Agent 反射 getCommandMap 失败: ${error.message}")
        }
        // 2) 遍历方法，找返回 CommandMap 的无参方法
        try {
            for (method in server.javaClass.methods) {
                if (method.parameterCount == 0 && CommandMap::class.java.isAssignableFrom(method.returnType)) {
                    val result = method.invoke(server)
                    if (result != null) return result
                }
            }
        } catch (error: Exception) {
            log_debug("Agent 遍历命令表方法失败: ${error.message}")
        }
        log_warning("Agent 无法访问服务器命令表，AI 命令帮助功能将不可用")
        return null
    }

    override fun getServerLogs(lines: Int?, keyword: String?): String {
        val logFile = resolveLogFile() ?: return "无法定位服务端日志文件（logs/latest.log）"
        val allLines = try {
            logFile.readLines(Charsets.UTF_8)
        } catch (error: Throwable) {
            return "读取服务端日志失败：${error.message}"
        }

        val lineCount = (lines ?: 50).coerceIn(1, 500)
        val kw = keyword?.trim()?.takeIf(String::isNotEmpty)
        val context = 5

        val selected: List<String>
        val matchCount: Int?
        if (kw != null) {
            val indices = allLines.indices.filter { allLines[it].contains(kw, ignoreCase = true) }
            if (indices.isEmpty()) {
                return "未在服务端日志中找到包含「$kw」的内容。"
            }
            matchCount = indices.size
            val range = indices.first()..indices.last()
            val from = (range.first - context).coerceAtLeast(0)
            val to = (range.last + context).coerceAtMost(allLines.size - 1)
            selected = allLines.subList(from, to)
        } else {
            matchCount = null
            selected = allLines.takeLast(lineCount)
        }

        val content = selected.joinToString("\n").trimEnd()
        val tip = matchCount?.let { "\n\n(按关键词「$kw」过滤，共匹配 $it 处)" } ?: ""
        return content + tip
    }

    /** 定位服务端日志文件 logs/latest.log。 */
    private fun resolveLogFile(): File? {
        val candidates = listOfNotNull(
            server.getWorldContainer().resolve("logs").resolve("latest.log"),
            server.getWorldContainer().parentFile?.resolve("logs")?.resolve("latest.log"),
            File("logs/latest.log")
        )
        return candidates.firstOrNull { it.isFile }
    }

    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warning(msg)
    override fun log_error(msg: String) = logger.severe(msg)
}
