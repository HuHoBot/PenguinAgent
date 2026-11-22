package agent.velocity.commands

import agent.provider.HExecution
import agent.proxy.commands.ProxyConsoleSender
import agent.velocity.HuHoBotVelocity
import java.util.concurrent.CompletableFuture

class VelocityConsoleSender(private val velocityPlugin: HuHoBotVelocity) : ProxyConsoleSender(velocityPlugin) {
    override val platformName: String = "velocity"

    override fun executeLocal(command: String): CompletableFuture<HExecution> {
        val future = CompletableFuture<HExecution>()
        velocityPlugin.server.commandManager.executeAsync(
            velocityPlugin.server.consoleCommandSource,
            command.removePrefix("/")
        ).whenComplete { handled, error ->
            if (error != null) {
                result = "执行命令异常: ${error.message}"
                future.completeExceptionally(error)
            } else {
                result = if (handled == true) {
                    "命令已在 Velocity 执行: $command"
                } else {
                    "Velocity 不存在该命令: $command"
                }
                future.complete(this)
            }
        }
        return future
    }
}
