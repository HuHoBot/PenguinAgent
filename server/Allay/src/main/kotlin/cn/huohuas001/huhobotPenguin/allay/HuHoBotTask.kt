package cn.huohuas001.huhobotPenguin.allay

import cn.huohuas001.bot.tools.Cancelable
import org.allaymc.api.scheduler.Task

class HuHoBotTask(private val task: Runnable) : Task {
    override fun onRun(): Boolean {
        task.run()
        return true
    }
}

class NoopAllayCancelable : Cancelable {
    override fun cancel() = Unit
}
