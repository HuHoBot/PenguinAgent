package cn.huohuas001.huhobotPenguin.velocity.commands

import cn.huohuas001.huhobotPenguin.velocity.HuHoBotVelocity
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import net.kyori.adventure.text.Component

class HuHoBotCommand(private val plugin: HuHoBotVelocity) : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                plugin.reloadPluginConfig()
                source.sendText("已重载配置文件；Redis 已按新配置重新连接。")
            }

            "info" -> {
                source.sendText("平台: ${plugin.getPlatform()}")
                source.sendText("版本: ${plugin.getPluginVersion()}")
                source.sendText("Redis: ${redisStatus()}")
            }

            "redis" -> executeRedis(source, args)
            else -> showHelp(source)
        }
    }

    private fun executeRedis(source: CommandSource, args: Array<String>) {
        when (args.getOrNull(1)?.lowercase()) {
            null, "status" -> {
                source.sendText("Redis 状态: ${redisStatus()}")
                source.sendText("频道: ${plugin.redisChannel()}")
            }

            "reconnect" -> {
                source.sendText(
                    if (plugin.reconnectRedis()) "Redis 重新连接成功。" else "Redis 重新连接失败或未启用。"
                )
            }

            "send" -> {
                if (args.size < 4) {
                    source.sendText("用法: /huhobot redis send <服务器名|ALL> <命令>")
                    return
                }
                val serverName = args[2]
                val command = args.drop(3).joinToString(" ")
                val sent = plugin.redisManager?.sendCommand(serverName, command) == true
                source.sendText(if (sent) "命令已发送到 $serverName: $command" else "发送命令失败，Redis 未连接。")
            }

            "exec" -> {
                if (args.size < 4) {
                    source.sendText("用法: /huhobot redis exec <服务器名> <命令>")
                    return
                }
                val serverName = args[2]
                val command = args.drop(3).joinToString(" ")
                val callback = plugin.redisManager?.takeIf { it.isConnected() }?.commandCallback
                if (callback == null) {
                    source.sendText("Redis 未连接或回调管理器未初始化。")
                    return
                }

                source.sendText("正在 $serverName 执行命令: $command")
                callback.executeWithCallback(
                    serverName = serverName,
                    command = command,
                    onOutput = { output -> source.sendAsync("[$serverName] ${stripColor(output)}") },
                    onComplete = { source.sendAsync("命令执行完成。") },
                    onTimeout = { source.sendAsync("命令执行超时或发送失败，目标服务器可能未响应。") }
                )
            }

            else -> source.sendText("未知的 Redis 子命令。可用: status, reconnect, send, exec")
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        return when {
            args.size <= 1 -> listOf("reload", "info", "redis")
                .filter { it.startsWith(args.getOrElse(0) { "" }, ignoreCase = true) }

            args.size == 2 && args[0].equals("redis", ignoreCase = true) ->
                listOf("status", "reconnect", "send", "exec")
                    .filter { it.startsWith(args[1], ignoreCase = true) }

            args.size == 3 && args[0].equals("redis", ignoreCase = true) &&
                    (args[1].equals("send", ignoreCase = true) || args[1].equals("exec", ignoreCase = true)) -> {
                val servers = plugin.server.allServers.map { it.serverInfo.name }.toMutableList()
                if (args[1].equals("send", ignoreCase = true)) servers.add(0, "ALL")
                servers.filter { it.startsWith(args[2], ignoreCase = true) }
            }

            else -> emptyList()
        }
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        invocation.source().hasPermission("huhobot.command")

    private fun showHelp(source: CommandSource) {
        source.sendText("/huhobot reload - 重载配置并重连 Redis")
        source.sendText("/huhobot info - 查看适配器与 Redis 状态")
        source.sendText("/huhobot redis status - 查看 Redis 状态")
        source.sendText("/huhobot redis reconnect - 重新连接 Redis")
        source.sendText("/huhobot redis send <服务器|ALL> <命令> - 发送命令，不等待结果")
        source.sendText("/huhobot redis exec <服务器> <命令> - 执行命令并接收输出")
    }

    private fun redisStatus(): String = when {
        !plugin.redisEnabled() -> "未启用"
        plugin.redisManager?.isConnected() == true -> "已连接"
        else -> "未连接"
    }

    private fun CommandSource.sendText(message: String) {
        sendMessage(Component.text(message))
    }

    private fun CommandSource.sendAsync(message: String) {
        plugin.submit { sendText(message) }
    }

    private fun stripColor(value: String): String =
        value.replace(Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE), "")
}
