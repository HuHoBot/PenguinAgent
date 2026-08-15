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
 * 2. 去掉前导空白与 `/`
 * 3. 若消息以某个指令名开头,则提取指令后面的内容作为 `params`,调用对应方法
 *
 * 方法签名约定(按需取前几个参数,顺序固定):
 * ```
 * @Commands("发信息")
 * fun sendGameMessage(api: HuHoBot, message: GroupMessageEvent, params: String?)
 * ```
 * 方法可为普通函数或 `suspend` 函数。
 */
abstract class BaseCommand {
    // 指令名 -> 处理方法
    private val commandMap = mutableMapOf<String, Method>()

    init {
        // 反射扫描本类所有带 @Commands 注解的 public 方法
        for (method in this.javaClass.methods) {
            val annotation = method.getAnnotation(Commands::class.java) ?: continue
            for (command in annotation.value) {
                commandMap[command] = method
            }
        }
    }

    /**
     * 处理群消息
     *
     * @return true 表示消息被某个指令消费;false 表示没有匹配的指令
     */
    fun handleMessage(plugin: HuHoBot, event: GroupMessageEvent): Boolean {
        val content = event.rawMessage.content ?: return false
        // 去掉 @提及(支持 <@id> 和 <@!id> 格式),再去掉前导空白和 /
        val cleaned = Regex("<@!?[^>]+>").replace(content, "").trim().trimStart('/')
        // 按指令名长度降序匹配,避免短指令抢先(如 "发" 抢走 "发信息")
        for (command in commandMap.keys.sortedByDescending { it.length }) {
            if (cleaned == command || cleaned.startsWith("$command ")) {
                if (plugin.getCommandList()[command] == false) {
                    event.sendMessage("此命令已被管理员关闭")
                    return true
                }
                val params = cleaned.removePrefix(command).trim()
                invokeMethod(plugin, event, commandMap[command]!!, params)
                return true
            }
        }
        return false
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
                // suspend 函数在 JVM 层面表现为多一个 Continuation 参数,手动传入即可驱动
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
