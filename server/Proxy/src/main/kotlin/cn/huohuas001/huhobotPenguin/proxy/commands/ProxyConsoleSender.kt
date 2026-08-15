package cn.huohuas001.huhobotPenguin.proxy.commands

import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.huhobotPenguin.proxy.HuHoBotProxy
import cn.huohuas001.huhobotPenguin.proxy.tools.CommandUtils
import java.util.Collections
import java.util.concurrent.CompletableFuture

/** 支持 `服务器名:命令` Redis 路由语法的 Proxy 命令执行器基类。 */
abstract class ProxyConsoleSender(protected val plugin: HuHoBotProxy) : HExecution {
    @Volatile
    protected var result: String = ""

    abstract val platformName: String

    override fun getRawString(): String = result

    override fun execute(command: String): CompletableFuture<HExecution> {
        val parsed = CommandUtils.splitCommand(command.removePrefix("/"))
        return if (parsed.serverName == null || isLocalPlatform(parsed.serverName)) {
            executeLocal(parsed.command)
        } else {
            executeOnServer(parsed.serverName, parsed.command)
        }
    }

    protected open fun isLocalPlatform(serverName: String): Boolean =
        serverName.equals(platformName, ignoreCase = true)

    protected abstract fun executeLocal(command: String): CompletableFuture<HExecution>

    fun executeOnServer(serverName: String, command: String): CompletableFuture<HExecution> {
        val future = CompletableFuture<HExecution>()
        val output = Collections.synchronizedList(mutableListOf<String>())

        plugin.submit(Runnable {
            try {
                val redis = plugin.redisManager
                if (redis?.isConnected() != true) {
                    result = "Redis 未连接，无法发送命令到子服务器"
                    future.complete(this)
                    return@Runnable
                }

                val callback = redis.commandCallback
                if (serverName.equals("ALL", ignoreCase = true) || callback == null) {
                    result = if (redis.sendCommand(serverName, command)) {
                        "命令已发送到 $serverName: $command"
                    } else {
                        "发送命令到 $serverName 失败"
                    }
                    future.complete(this)
                    return@Runnable
                }

                callback.executeWithCallback(
                    serverName = serverName,
                    command = command,
                    onOutput = { line -> output += line },
                    onComplete = {
                        result = synchronized(output) {
                            output.joinToString("\n").ifBlank {
                                "命令已在 $serverName 执行完成: $command"
                            }
                        }
                        future.complete(this)
                    },
                    onTimeout = {
                        result = synchronized(output) {
                            (output + "命令执行超时或目标服务器未响应").joinToString("\n")
                        }
                        future.complete(this)
                    }
                )
            } catch (error: Exception) {
                result = "执行命令异常: ${error.message}"
                future.completeExceptionally(error)
            }
        })
        return future
    }
}
