package cn.huohuas001.huhobotPenguin.nukkit

import cn.huohuas001.bot.tools.Cancelable
import cn.nukkit.scheduler.PluginTask
import cn.nukkit.scheduler.TaskHandler

class NukkitTask(owner: HuHoBotNukkit, private val task: Runnable) : PluginTask<HuHoBotNukkit>(owner) {
    override fun onRun(currentTick: Int) = task.run()
}
class NukkitTaskCancelable(private val task: TaskHandler) : Cancelable { override fun cancel() = task.cancel() }
