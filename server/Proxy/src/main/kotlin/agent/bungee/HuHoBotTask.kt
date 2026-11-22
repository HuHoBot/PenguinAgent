package agent.bungee

import agent.tools.Cancelable
import net.md_5.bungee.api.scheduler.ScheduledTask

class HuHoBotTask(private val task: ScheduledTask) : Cancelable {
    override fun cancel() = task.cancel()
}
