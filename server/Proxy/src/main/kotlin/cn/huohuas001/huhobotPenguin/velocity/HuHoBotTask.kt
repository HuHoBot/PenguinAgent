package cn.huohuas001.huhobotPenguin.velocity

import cn.huohuas001.bot.tools.Cancelable
import com.velocitypowered.api.scheduler.ScheduledTask

class HuHoBotTask(private val task: ScheduledTask) : Cancelable {
    override fun cancel() = task.cancel()
}
