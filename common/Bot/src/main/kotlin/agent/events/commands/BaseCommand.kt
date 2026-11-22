package agent.events.commands

import agent.HuHoBot
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
        private val allRegisteredCommands = java.util.concurrent.ConcurrentHashMap<String,RegisteredCommand>()

        fun allCommands(): List<RegisteredCommand> = allRegisteredCommands.values.sortedBy { it.command }
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
        commandMap.values.forEach { allRegisteredCommands[it.metadata.command]=it.metadata }
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
        var cleaned = if (isSlashCommand) mentionStripped.removePrefix("/").trimStart() else mentionStripped
        // 命令映射/切换：把映射名/切换名规范为原名；被切换掉的原名直接拒绝
        if (cleaned.isNotEmpty()) {
            val firstWord = cleaned.split(Regex("\\s+"), 2)[0]
            val resolved = agent.state.CommandMappingStore.resolve(firstWord)
            if (resolved == null) {
                val groupId = event.groupOpenId ?: event.groupId
                val alias = agent.state.CommandMappingStore.aliasOf(firstWord)
                if (!groupId.isNullOrBlank()) {
                    agent.QClient.sendTextToGroup(
                        groupId,
                        "命令 /$firstWord 已切换为 /$alias，请使用新命令"
                    )
                }
                return DispatchResult.HANDLED
            } else if (resolved != firstWord) {
                cleaned = resolved + cleaned.removePrefix(firstWord)
            }
        }
        // 按指令名长度降序匹配,避免短指令抢先(如 "发" 抢走 "发信息")
        for (command in commandMap.keys.sortedByDescending { it.length }) {
            if (cleaned.equals(command, true) || cleaned.startsWith("$command ", true)) {
                val groupId = event.groupOpenId ?: event.groupId
                if (plugin.getCommandList(groupId)[command] == false) {
                    agent.QClient.sendTextToGroup(
                        groupId, agent.messages.Messages.t("command.disabled")
                    )
                    return DispatchResult.HANDLED
                }
                // 权限组：组成员仅可用组内开放的命令
                val groupMemberOpenId = event.sender?.openid ?: event.sender?.id ?: ""
                if (agent.state.PermissionGroupsStore.commandAllowed(groupMemberOpenId, command) == false) {
                    agent.QClient.sendTextToGroup(
                        groupId, agent.messages.Messages.t("command.no-permission-group")
                    )
                    return DispatchResult.HANDLED
                }
                val handler = commandMap[command]!!
                if (handler.metadata.onlyAdmin) {
                    val isAdmin = agent.events.commands.CommandSupport.checkAdmin(plugin, event)
                    if (!isAdmin) {
                        agent.QClient.sendTextToGroup(
                            groupId, agent.messages.Messages.t("command.no-admin")
                        )
                        return DispatchResult.HANDLED
                    }
                }
                val params = cleaned.substring(command.length).trim()
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
            agent.QClient.sendTextToGroup(
                event.groupOpenId ?: event.groupId, "未找到该命令"
            )
            return DispatchResult.HANDLED
        }

        val targetCommand = if (customCommand.permission > 0) "管理员执行" else "执行"
        val handler = commandMap[targetCommand] ?: return DispatchResult.NOT_HANDLED
        val groupId = event.groupOpenId ?: event.groupId
        if (plugin.getCommandList(groupId)[targetCommand] == false) {
            agent.QClient.sendTextToGroup(
                groupId, "此命令已被管理员关闭"
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
