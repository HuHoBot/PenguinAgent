package cn.huohuas001.huhobotPenguin.bungee.commands

import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.huhobotPenguin.bungee.HuHoBotBungee
import cn.huohuas001.huhobotPenguin.proxy.commands.ProxyConsoleSender
import net.md_5.bungee.api.ProxyServer
import java.util.concurrent.CompletableFuture

class BungeeConsoleSender(plugin: HuHoBotBungee) : ProxyConsoleSender(plugin) {
    override val platformName: String = "bungeecord"

    override fun isLocalPlatform(serverName: String): Boolean =
        super.isLocalPlatform(serverName) || serverName.equals("bungee", ignoreCase = true)

    override fun executeLocal(command: String): CompletableFuture<HExecution> {
        val future = CompletableFuture<HExecution>()
        plugin.submit {
            try {
                val handled = ProxyServer.getInstance().pluginManager.dispatchCommand(
                    ProxyServer.getInstance().console,
                    command.removePrefix("/")
                )
                result = if (handled) {
                    "命令已在 BungeeCord 执行: $command"
                } else {
                    "BungeeCord 不存在该命令: $command"
                }
                future.complete(this)
            } catch (error: Exception) {
                result = "执行命令异常: ${error.message}"
                future.completeExceptionally(error)
            }
        }
        return future
    }
}
