package cn.huohuas001.huhobotPenguin.spigot

import cn.huohuas001.bot.tools.Cancelable
import org.bukkit.scheduler.BukkitTask

class HuHoBotTask(
    private val task: BukkitTask
) : Cancelable {
    override fun cancel() {
        task.cancel()
    }
}
