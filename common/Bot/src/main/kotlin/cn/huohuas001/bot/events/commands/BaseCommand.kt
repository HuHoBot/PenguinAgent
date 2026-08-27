package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 指令处理基类
 *
 * 子类中通过 [Commands] 注解声明指令处理方法,收到群消息后调用 [handleMessage] 分发:
 * 1. 去掉消息中的 @提及(支持 `<@id>` 与 `<@!id>` 格式)
 * 2. 去掉前导 `/` 后优先匹配所有内置指令
 * 3. 所有内置指令均未命中时，再将 `/test` 按自定义命令 key 路由到 `执行` 或 `管理员执行`
 *
 * 方法签名约定(按需取前几个参数,顺序固定):
 * ```
 * @Commands("发信息", "发送消息到游戏")
 * fun sendGameMessage(api: HuHoBot, message: GroupMessageEvent, params: String?)
 * ```
 * 方法可为普通函数或 `suspend` 函数。
 */
abstract class BaseCommand {
    enum class DispatchResult {
        NOT_HANDLED,
        HANDLED,
        CUSTOM_COMMAND
    }

    companion object {
        private val allRegisteredCommands = mutableListOf<RegisteredCommand>()

        fun allCommands(): List<RegisteredCommand> = allRegisteredCommands.toList()
    }

    private data class CommandHandler(
        val metadata: RegisteredCommand,
        val method: Method
    )

    // 指令名 -> 指令元数据及处理方法
    private val commandMap = mutableMapOf<String, CommandHandler>()

    init {
        // 反射扫描本类所有带 @Commands 注解的 public 方法
        for (method in this.javaClass.methods) {
            val annotation = method.getAnnotation(Commands::class.java) ?: continue
            val command = annotation.command.trim()
            require(command.isNotEmpty()) { "指令方法 ${method.name} 的 command 不能为空" }
            commandMap[command] = CommandHandler(
                metadata = RegisteredCommand(
                    command = command,
                    describe = annotation.describe.trim(),
                    onlyAdmin = annotation.onlyAdmin
                ),
                method = method
            )
        }
        allRegisteredCommands.addAll(commandMap.values.map { it.metadata })
    }

    /** 返回本处理器实际扫描并注册成功的指令元数据。 */
    fun registeredCommands(): List<RegisteredCommand> = commandMap.values
        .map { it.metadata }
        .sortedBy { it.command }

    /**
     * 处理群消息
     *
     * @return DispatchResult 表示消息是否被消费
     */
    fun handleMessage(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        allowCustomFallback: Boolean = true
    ): DispatchResult {
        val content = event.rawMessage.content ?: return DispatchResult.NOT_HANDLED
        // 去掉 @提及(支持 <@id> 和 <@!id> 格式),保留前导 / 以便识别自定义命令快捷方式。
        val mentionStripped = Regex("<@!?[^>]+>").replace(content, "").trim()
        val isSlashCommand = mentionStripped.startsWith("/")
        val cleaned = if (isSlashCommand) mentionStripped.removePrefix("/").trimStart() else mentionStripped
        // 按指令名长度降序匹配,避免短指令抢先(如 "发" 抢走 "发信息")
        for (command in commandMap.keys.sortedByDescending { it.length }) {
            if (cleaned == command || cleaned.startsWith("$command ")) {
                if (plugin.getCommandList()[command] == false) {
                    cn.huohuas001.bot.QClient.sendTextToGroup(
                        event.groupOpenId ?: event.groupId, "此命令已被管理员关闭"
                    )
                    return DispatchResult.HANDLED
                }
                val handler = commandMap[command]!!
                if (handler.metadata.onlyAdmin) {
                    val isAdmin = cn.huohuas001.bot.events.commands.CommandSupport.checkAdmin(plugin, event)
                    if (!isAdmin) {
                        cn.huohuas001.bot.QClient.sendTextToGroup(
                            event.groupOpenId ?: event.groupId, "你没有执行此命令的管理员权限"
                        )
                        return DispatchResult.HANDLED
                    }
                }
                val params = cleaned.removePrefix(command).trim()
                invokeMethod(plugin, event, handler.method, params)
                return DispatchResult.HANDLED
            }
        }

        if (isSlashCommand && allowCustomFallback) {
            return handleCustomCommandShortcut(plugin, event, mentionStripped)
        }
        return DispatchResult.NOT_HANDLED
    }

    /**
     * 将 `/test` 这种快捷写法转发到现有的 `执行 test` 或 `管理员执行 test` 处理器。
     */
    private fun handleCustomCommandShortcut(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        content: String
    ): DispatchResult {
        val invocation = content.removePrefix("/").trim()
        val key = invocation.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
        val customCommand = CustomCommandRegistry.find(key)
        if (customCommand == null) {
            cn.huohuas001.bot.QClient.sendTextToGroup(
                event.groupOpenId ?: event.groupId, "未找到该命令"
            )
            return DispatchResult.HANDLED
        }

        val targetCommand = if (customCommand.permission > 0) "管理员执行" else "执行"
        val handler = commandMap[targetCommand] ?: return DispatchResult.NOT_HANDLED
        if (plugin.getCommandList()[targetCommand] == false) {
            cn.huohuas001.bot.QClient.sendTextToGroup(
                event.groupOpenId ?: event.groupId, "此命令已被管理员关闭"
            )
            return DispatchResult.HANDLED
        }

        invokeMethod(plugin, event, handler.method, invocation)
        return DispatchResult.CUSTOM_COMMAND
    }

    /**
     * 通过反射调用指令方法,自动适配方法参数数量与 suspend 特性
     */
    private fun invokeMethod(plugin: HuHoBot, event: GroupMessageEvent, method: Method, params: String) {
        val isSuspend = method.parameterTypes.lastOrNull() == Continuation::class.java
        // 构造真实参数(不含 Continuation),按签名约定最多支持 (api, message, params)
        val argCount = method.parameterCount - if (isSuspend) 1 else 0
        val args: Array<Any?> = when (argCount) {
            3 -> arrayOf(plugin, event, params)
            2 -> arrayOf(event, params)
            1 -> arrayOf(event)
            0 -> emptyArray()
            else -> {
                plugin.log_error("指令方法 ${method.name} 参数数量(${method.parameterCount})不受支持,最多支持 (api, message, params)")
                return
            }
        }

        try {
            if (isSuspend) {
                val continuation = object : Continuation<Any?> {
                    override val context: CoroutineContext = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Any?>) {
                        result.exceptionOrNull()?.let {
                            plugin.log_error("指令方法 ${method.name} 执行异常: $it")
                        }
                    }
                }
                method.invoke(this, *args, continuation)
            } else {
                method.invoke(this, *args)
            }
        } catch (e: InvocationTargetException) {
            plugin.log_error("指令方法 ${method.name} 执行异常: ${e.targetException}")
        } catch (e: Exception) {
            plugin.log_error("指令方法 ${method.name} 调用失败: $e")
        }
    }
}
