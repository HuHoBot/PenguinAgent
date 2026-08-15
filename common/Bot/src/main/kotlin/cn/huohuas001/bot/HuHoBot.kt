package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.events.commands.SensitiveFilter
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.utils.LoggerImpl
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * 所有服务器端实现共享的 HuHoBot 基类接口。
 *
 * 平台端只需实现日志、配置、调度、广播和 [createCommandExecutor] 等原语；
 * 配置状态、自定义命令解析及敏感词审核由这里统一提供。
 */
interface HuHoBot : LoggerProvider, ConfigProvider, CommandProvider, SchedulerProvider, MessageProvider {

    /** QQ 开放平台 AppID；留空时公共运行时不会启动 QQ 客户端。 */
    fun getBotAppId(): String

    /** QQ 开放平台 Secret；留空时公共运行时不会启动 QQ 客户端。 */
    fun getBotSecret(): String

    /** 创建当前服务端平台的原生命令执行器。 */
    fun createCommandExecutor(): HExecution

    /** 重读当前适配器配置，并刷新公共运行时配置。 */
    fun reloadPluginConfig()

    /** 向配置中的所有 QQ 群发送自定义 Markdown。 */
    override fun sendMarkdown(markdownContent: String, keyboard: Keyboard?) {
        QClient.sendMarkdown(markdownContent, keyboard)
    }

    /** 回复触发消息所在的 QQ 群，发送自定义 Markdown。 */
    override fun replyMarkdown(
        event: GroupMessageEvent,
        markdownContent: String,
        keyboard: Keyboard?
    ) {
        QClient.replyMarkdown(event, markdownContent, keyboard)
    }

    /** 回复触发消息所在的 QQ 群，同时发送文本和网络图片。 */
    override fun replyWithImg(
        event: GroupMessageEvent,
        text: String,
        imgUrl: String
    ) {
        QClient.replyWithImg(event, text, imgUrl)
    }

    /**
     * QQ SDK 的按日日志文件格式。
     * 默认放到当前客户端配置目录的 logs 下，仍允许平台覆盖或返回 null 禁用文件日志。
     */
    fun getQqBotLogFilePattern(): String? =
        getConfigFile()?.parentFile?.resolve("logs/Bot-%s.log")?.path

    /**
     * 平台启动时调用：注册平台实例、初始化公共状态并异步启动 QQ 客户端。
     */
    fun initializeRuntime() {
        BotShared.setInstance(this)
        val logger = this
        LoggerImpl.setLogSink(object : LoggerImpl.LogSink {
            override fun log(message: String, level: Int) {
                when (level) {
                    LoggerImpl.LogSink.ERROR_LEVEL -> logger.log_error(message)
                    LoggerImpl.LogSink.DEBUG_LEVEL -> logger.log_debug(message)
                    else -> logger.log_info(message)
                }
            }
        })
        CommandRepositories.initialize(getConfigFile()?.parentFile)
        reloadRuntimeConfig()
        launchQqClient()
    }

    /** 平台停止时调用，释放 SDK 日志桥接和公共运行时资源。 */
    fun shutdownRuntime() {
        try {
            QClient.shutdown()
        } finally {
            LoggerImpl.clearLogSink()
        }
    }

    /** 配置重载后调用，使公共自定义命令表立即更新。 */
    fun reloadRuntimeConfig() {
        initializeMarkdownTemplates()
        CustomCommandRegistry.replace(getCustomCommands())
    }

    /** 创建 Markdown 目录，并补充不存在的内置模板；不会覆盖用户已经编辑的文件。 */
    fun initializeMarkdownTemplates() {
        val configDirectory = getConfigFile()?.absoluteFile?.parentFile
        if (configDirectory == null) {
            log_warning("无法确定插件配置目录，未初始化 Markdown 模板")
            return
        }

        val markdownDirectory = configDirectory.resolve("Markdown")
        if (!markdownDirectory.isDirectory && !markdownDirectory.mkdirs()) {
            log_warning("无法创建 Markdown 目录: ${markdownDirectory.path}")
            return
        }

        DEFAULT_MARKDOWN_TEMPLATES.forEach { (fileName, resourcePath) ->
            val target = markdownDirectory.resolve(fileName)
            if (target.exists()) return@forEach

            val resource = HuHoBot::class.java.classLoader.getResourceAsStream(resourcePath)
            if (resource == null) {
                log_warning("找不到内置 Markdown 模板资源: $resourcePath")
                return@forEach
            }

            try {
                resource.use { Files.copy(it, target.toPath()) }
                log_info("已初始化 Markdown 模板: ${target.path}")
            } catch (_: FileAlreadyExistsException) {
                // 另一个初始化流程已经创建了文件，保留现有内容。
            } catch (error: Exception) {
                log_warning("初始化 Markdown 模板 ${target.path} 失败: ${error.message}")
            }
        }
    }

    /** 使用当前平台的命令执行器执行原生命令。 */
    override fun dispatchCommand(command: String): CompletableFuture<HExecution> =
        createCommandExecutor().execute(command.removePrefix("/"))

    /** 异步启动 QQ 客户端，避免阻塞各服务端平台的主线程。 */
    fun launchQqClient() {
        val appId = getBotAppId()
        val secret = getBotSecret()
        if (appId.isBlank() || secret.isBlank()) {
            log_warning("未配置 bot.app-id 或 bot.secret，QQ 机器人未启动")
            return
        }

        submitAsync {
            try {
                QClient.launchClient(appId, secret, getQqBotLogFilePattern())
            } catch (error: Exception) {
                log_error("QQ 机器人启动失败: ${error.message}")
            }
        }
    }

    /**
     * 统一命令入口。
     *
     * 普通服务器命令直接交给平台；`huhobot run/adminrun` 会先解析
     * `custom-commands`，完成权限和占位符替换后再交给平台执行。
     */
    fun sendCommand(command: String): CompletableFuture<HExecution> {
        val resolved = CustomCommandRegistry.resolve(command)
        if (resolved.error != null) {
            return CompletableFuture.completedFuture(TextExecution(resolved.error, this))
        }
        return dispatchCommand(resolved.command!!)
    }

    /** 统一执行正则过滤、本地敏感词首检和可选 AI 二审。 */
    fun auditText(text: String): String = SensitiveFilter.filter(
        value = filterText(text),
        baseUrl = getAuditBaseUrl(),
        apiKey = getAuditApiKey(),
        model = getAuditModel(),
        words = getSensitiveWords()
    )

    fun getOnlineList(): List<String>
}

private val DEFAULT_MARKDOWN_TEMPLATES = mapOf(
    "online.md" to "Markdown/online.md"
)

private class TextExecution(
    private val text: String,
    private val bot: HuHoBot
) : HExecution {
    override fun getRawString(): String = text
    override fun execute(command: String): CompletableFuture<HExecution> = bot.sendCommand(command)
}
