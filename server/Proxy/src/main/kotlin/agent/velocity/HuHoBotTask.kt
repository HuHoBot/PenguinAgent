package agent.velocity

import agent.tools.Cancelable
import com.velocitypowered.api.scheduler.ScheduledTask

class HuHoBotTask(private val task: ScheduledTask) : Cancelable {
    override fun cancel() = task.cancel()
}
