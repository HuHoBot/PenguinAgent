package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

class HybridCommandExecutor(private val plugin: HuHoBotSpigot) : HExecution {
    private val sender = BukkitConsoleSender(plugin)
    private val outputAppender = CommandOutputAppender.getInstance()

    override fun execute(command: String): CompletableFuture<HExecution> {
        val result = CompletableFuture<HExecution>()
        sender.clearMessages()
        outputAppender.startCapture()

        plugin.submit {
            try {
                Bukkit.dispatchCommand(sender, command)
                completeAfterCommandOutput(result)
            } catch (error: Exception) {
                outputAppender.stopCapture()
                result.completeExceptionally(error)
            }
        }

        return result
    }

    override fun getRawString(): String = sender.getRawString()

    private fun completeAfterCommandOutput(result: CompletableFuture<HExecution>) {
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                val senderMessages = sender.getAndClearMessages()
                val loggedMessages = outputAppender.stopCapture()

                (senderMessages + loggedMessages)
                    .distinct()
                    .forEach(sender::sendMessage)

                result.complete(sender)
            },
            COMMAND_OUTPUT_DELAY_TICKS
        )
    }

    private companion object {
        const val COMMAND_OUTPUT_DELAY_TICKS = 40L
    }
}
