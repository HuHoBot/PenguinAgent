package cn.huohuas001.huhobotPenguin.bungee.commands

import cn.huohuas001.huhobotPenguin.bungee.HuHoBotBungee
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.TabExecutor

class HuHoBotCommand(private val plugin: HuHoBotBungee) :
    Command("huhobot", "huhobot.command", "hb"), TabExecutor {

    override fun execute(sender: CommandSender, args: Array<out String>) {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                plugin.reloadPluginConfig()
                sender.sendText("已重载配置文件；Redis 已按新配置重新连接。")
            }

            "info" -> {
                sender.sendText("平台: ${plugin.getPlatform()}")
                sender.sendText("版本: ${plugin.getPluginVersion()}")
                sender.sendText("Redis: ${redisStatus()}")
            }

            "redis" -> executeRedis(sender, args)
            else -> showHelp(sender)
        }
    }

    private fun executeRedis(sender: CommandSender, args: Array<out String>) {
        when (args.getOrNull(1)?.lowercase()) {
            null, "status" -> {
                sender.sendText("Redis 状态: ${redisStatus()}")
                sender.sendText("频道: ${plugin.redisChannel()}")
            }

            "reconnect" -> {
                sender.sendText(
                    if (plugin.reconnectRedis()) "Redis 重新连接成功。" else "Redis 重新连接失败或未启用。"
                )
            }

            "send" -> {
                if (args.size < 4) {
                    sender.sendText("用法: /huhobot redis send <服务器名|ALL> <命令>")
                    return
                }
                val serverName = args[2]
                val command = args.drop(3).joinToString(" ")
                val sent = plugin.redisManager?.sendCommand(serverName, command) == true
                sender.sendText(if (sent) "命令已发送到 $serverName: $command" else "发送命令失败，Redis 未连接。")
            }

            "exec" -> {
                if (args.size < 4) {
                    sender.sendText("用法: /huhobot redis exec <服务器名> <命令>")
                    return
                }
                val serverName = args[2]
                val command = args.drop(3).joinToString(" ")
                val callback = plugin.redisManager?.takeIf { it.isConnected() }?.commandCallback
                if (callback == null) {
                    sender.sendText("Redis 未连接或回调管理器未初始化。")
                    return
                }

                sender.sendText("正在 $serverName 执行命令: $command")
                callback.executeWithCallback(
                    serverName = serverName,
                    command = command,
                    onOutput = { output -> sender.sendAsync("[$serverName] ${stripColor(output)}") },
                    onComplete = { sender.sendAsync("命令执行完成。") },
                    onTimeout = { sender.sendAsync("命令执行超时或发送失败，目标服务器可能未响应。") }
                )
            }

            else -> sender.sendText("未知的 Redis 子命令。可用: status, reconnect, send, exec")
        }
    }

    override fun onTabComplete(sender: CommandSender, args: Array<out String>): Iterable<String> = when {
        args.size <= 1 -> listOf("reload", "info", "redis")
            .filter { it.startsWith(args.getOrElse(0) { "" }, ignoreCase = true) }

        args.size == 2 && args[0].equals("redis", ignoreCase = true) ->
            listOf("status", "reconnect", "send", "exec")
                .filter { it.startsWith(args[1], ignoreCase = true) }

        args.size == 3 && args[0].equals("redis", ignoreCase = true) &&
                (args[1].equals("send", ignoreCase = true) || args[1].equals("exec", ignoreCase = true)) -> {
            val servers = plugin.proxy.servers.keys.toMutableList()
            if (args[1].equals("send", ignoreCase = true)) servers.add(0, "ALL")
            servers.filter { it.startsWith(args[2], ignoreCase = true) }
        }

        else -> emptyList()
    }

    private fun showHelp(sender: CommandSender) {
        sender.sendText("/huhobot reload - 重载配置并重连 Redis")
        sender.sendText("/huhobot info - 查看适配器与 Redis 状态")
        sender.sendText("/huhobot redis status - 查看 Redis 状态")
        sender.sendText("/huhobot redis reconnect - 重新连接 Redis")
        sender.sendText("/huhobot redis send <服务器|ALL> <命令> - 发送命令，不等待结果")
        sender.sendText("/huhobot redis exec <服务器> <命令> - 执行命令并接收输出")
    }

    private fun redisStatus(): String = when {
        !plugin.redisEnabled() -> "未启用"
        plugin.redisManager?.isConnected() == true -> "已连接"
        else -> "未连接"
    }

    private fun CommandSender.sendText(message: String) {
        sendMessage(TextComponent(message))
    }

    private fun CommandSender.sendAsync(message: String) {
        plugin.submit { sendText(message) }
    }

    private fun stripColor(value: String): String =
        value.replace(Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE), "")
}
