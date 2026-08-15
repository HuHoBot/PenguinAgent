package cn.huohuas001.huhobotPenguin.velocity

import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.adapter.config.YamlConfig
import cn.huohuas001.huhobotPenguin.proxy.HuHoBotProxy
import cn.huohuas001.huhobotPenguin.proxy.redis.RedisManager
import cn.huohuas001.huhobotPenguin.velocity.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.velocity.commands.VelocityConsoleSender
import cn.huohuas001.huhobotPenguin.velocity.events.GameChat
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import java.nio.file.Path
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.collections.map

class HuHoBotVelocity @Inject constructor(
    val server: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDirectory: Path,
    private val pluginContainer: PluginContainer
) : HuHoBotProxy {
    private lateinit var config: YamlConfig
    override var redisManager: RedisManager? = null

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        config = YamlConfig(dataDirectory.resolve("config.yml").toFile(), 25565, ::log_warning)
        config.initialize { javaClass.classLoader.getResourceAsStream("proxy-config.yml") }
        initializeRedis()
        server.commandManager.register(
            server.commandManager.metaBuilder("huhobot").aliases("hb").build(), HuHoBotCommand(this)
        )
        server.eventManager.register(this, GameChat())
        initializeRuntime()
        log_info("HuHoBotPenguin Velocity 已加载")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        redisManager?.disconnect()
        redisManager = null
        shutdownRuntime()
    }

    override fun reloadPluginConfig() {
        config.reload()
        reconnectRedis()
        reloadRuntimeConfig()
    }

    override fun createCommandExecutor(): HExecution = VelocityConsoleSender(this)

    override fun broadcastMessage(msg: String) {
        server.allPlayers.forEach { it.sendMessage(net.kyori.adventure.text.Component.text(msg)) }
        redisManager?.broadcast(msg)
    }

    private fun initializeRedis() {
        if (!redisEnabled()) {
            log_info("Redis 未启用，带服务器前缀的命令将无法发送到子服务器")
            return
        }
        val manager = RedisManager(this)
        redisManager = manager
        manager.connect(redisHost(), redisPort(), redisPassword())
    }

    override fun reconnectRedis(): Boolean {
        redisManager?.disconnect()
        redisManager = null
        if (!redisEnabled()) return false

        val manager = RedisManager(this)
        redisManager = manager
        manager.connect(redisHost(), redisPort(), redisPassword())
        return manager.isConnected()
    }

    override fun submit(task: Runnable): Cancelable =
        VelocityCancelable(server.scheduler.buildTask(this, task).schedule())

    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        VelocityCancelable(server.scheduler.buildTask(this, task).delay(delay * 50, TimeUnit.MILLISECONDS).schedule())

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable = VelocityCancelable(
        server.scheduler.buildTask(this, task).delay(delay * 50, TimeUnit.MILLISECONDS)
            .repeat(period * 50, TimeUnit.MILLISECONDS).schedule()
    )

    override fun getOnlineList(): List<String> = server.allPlayers.map { it.username }.toMutableList()
    override fun redisEnabled(): Boolean = config.redisEnabled()
    override fun redisHost(): String = config.redisHost()
    override fun redisPort(): Int = config.redisPort()
    override fun redisPassword(): String? = config.redisPassword()
    override fun redisChannel(): String = config.redisChannel()
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
    override fun getPlatform(): String = "Velocity"
    override fun getPluginVersion(): String = pluginContainer.description.version.orElse("unknown")
    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warn(msg)
    override fun log_error(msg: String) = logger.error(msg)
}

private class VelocityCancelable(private val task: com.velocitypowered.api.scheduler.ScheduledTask) : Cancelable {
    override fun cancel() = task.cancel()
}
