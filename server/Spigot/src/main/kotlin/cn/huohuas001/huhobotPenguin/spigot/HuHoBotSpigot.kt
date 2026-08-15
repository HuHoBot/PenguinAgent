package cn.huohuas001.huhobotPenguin.spigot

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.spigot.commands.BukkitConsoleSender
import cn.huohuas001.huhobotPenguin.spigot.commands.CommandOutputAppender
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.HybridCommandExecutor
import cn.huohuas001.huhobotPenguin.spigot.events.GameChat
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class HuHoBotSpigot : JavaPlugin(), HuHoBot {
    private lateinit var configManager: ConfigManager

    override fun onEnable() {
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
        log_info("HuHoBot Penguin 已加载")
    }

    override fun onDisable() {
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

    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warning(msg)
    override fun log_error(msg: String) = logger.severe(msg)
}
