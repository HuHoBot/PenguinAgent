package agent.spigot

import agent.tools.Cancelable
import org.bukkit.scheduler.BukkitTask

class HuHoBotTask(
    private val task: BukkitTask
) : Cancelable {
    override fun cancel() {
        task.cancel()
    }
}
