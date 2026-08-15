package cn.huohuas001.huhobotPenguin.nukkit

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.adapter.config.YamlConfig
import cn.huohuas001.huhobotPenguin.nukkit.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.nukkit.events.PlayerEvents
import cn.huohuas001.huhobotPenguin.nukkit.tools.NukkitConsoleSender
import cn.nukkit.plugin.PluginBase
import cn.nukkit.plugin.PluginLogger
import java.io.File
import java.util.concurrent.CompletableFuture

class HuHoBotNukkit : PluginBase(), HuHoBot {
    private lateinit var config: YamlConfig
    private lateinit var pluginLogger: PluginLogger

    override fun onEnable() {
        pluginLogger = logger
        config = YamlConfig(File(dataFolder, "config.yml"), 19132, ::log_warning)
        config.initialize { javaClass.classLoader.getResourceAsStream("config.yml") }
        server.commandMap.register("huhobot", HuHoBotCommand(this))
        server.pluginManager.registerEvents(PlayerEvents(this), this)
        initializeRuntime()
        log_info("HuHoBotPenguin Nukkit 已加载")
    }

    override fun onDisable() = shutdownRuntime()

    override fun reloadPluginConfig() {
        config.reload()
        reloadRuntimeConfig()
    }

    override fun createCommandExecutor(): HExecution = NukkitExecution(this)
    override fun broadcastMessage(msg: String) {
        server.broadcastMessage(msg)
    }

    override fun submit(task: Runnable): Cancelable =
        NukkitTaskCancelable(server.scheduler.scheduleTask(NukkitTask(this, task)))

    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        NukkitTaskCancelable(server.scheduler.scheduleDelayedTask(NukkitTask(this, task), delay.toInt()))

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        NukkitTaskCancelable(
            server.scheduler.scheduleDelayedRepeatingTask(
                NukkitTask(this, task),
                delay.toInt(),
                period.toInt()
            )
        )

    override fun getOnlineList(): List<String> = server.onlinePlayers.map { it.value.username }.toMutableList()
    override fun getConfigFile(): File = config.file
    override fun getBotAppId(): String = config.botAppId()
    override fun getBotSecret(): String = config.botSecret()
    override fun getChatFormat(): ChatFormat = config.chatFormat()
    override fun getPlayerEventFormat(): PlayerEventFormat = config.playerEventFormat()
    override fun getMarkdownFiles(): Map<String, String> = config.markdownFiles()
    override fun getMotd(): Motd = config.motd()
    override fun getWhiteList(): WhiteList = config.whiteList()
    override fun getFilterRegexList(): List<String> = config.filterRegexList()
    override fun getAdminMode(): AdminMode = config.adminMode()
    override fun getAdminList(): List<String> = config.adminOpenIds()
    override fun getGroupOpenIdList(): List<String> = config.groupOpenIds()
    override fun shouldSuppressQqBotConsoleOutput(): Boolean = config.suppressQqBotConsoleOutput()
    override fun getFullAmount(): Boolean = config.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = config.commandSwitches()
    override fun getAuditBaseUrl(): String? = config.auditBaseUrl()
    override fun getAuditApiKey(): String? = config.auditApiKey()
    override fun getAuditModel(): String? = config.auditModel()
    override fun getCustomCommands(): List<CustomCommandDetail> = config.customCommands()
    override fun getBotName(): String = config.botName()
    override fun getServerName(): String = config.serverName()
    override fun getPlatform(): String = "Nukkit"
    override fun getPluginVersion(): String = description.version
    override fun log_info(msg: String) = pluginLogger.info(msg)
    override fun log_warning(msg: String) = pluginLogger.warning(msg)
    override fun log_error(msg: String) = pluginLogger.error(msg)

}

private class NukkitExecution(private val plugin: HuHoBotNukkit) : HExecution {
    private val sender = NukkitConsoleSender(plugin.server.consoleSender, plugin)
    override fun getRawString(): String = sender.output.toString()
    override fun execute(command: String): CompletableFuture<HExecution> {
        val future = CompletableFuture<HExecution>()
        plugin.submit {
            try {
                plugin.server.dispatchCommand(sender, command.removePrefix("/"))
                future.complete(this)
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future
    }
}
