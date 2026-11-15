package cn.huohuas001.huhobotPenguin.spigot.manager

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * Folia / Paper / Spigot 统一调度适配。
 *
 * Folia 移除了 BukkitScheduler，只能用 AsyncScheduler / GlobalRegionScheduler（Folia 内测场景）。
 * 这里用反射探测：检测到 Folia 运行时走新调度器，否则走传统 BukkitScheduler。
 * 反射失败时自动回退传统调度，绝不因适配层问题拖垮插件。
 */
object FoliaScheduler {

    /** 是否处于 Folia 运行时（按区域多线程）。 */
    @Volatile
    private var folia: Boolean? = null

    private var asyncRunNow: Method? = null
    private var globalRunDelayed: Method? = null
    private var globalRunAtFixedRate: Method? = null

    fun isFolia(): Boolean {
        if (folia == null) {
            folia = try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
                // Folia 的调度器只在该端存在；双确认避免误判
                Bukkit.getServer().javaClass.getMethod("getAsyncScheduler") != null
            } catch (_: Throwable) {
                false
            }
        }
        return folia!!
    }

    private fun reflectOnce(): Boolean {
        if (asyncRunNow != null) return true
        if (!isFolia()) return false
        return try {
            val server = Bukkit.getServer()
            val async = server.javaClass.getMethod("getAsyncScheduler").invoke(server)
            val global = server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
            val asyncClass = async.javaClass
            val globalClass = global.javaClass
            val pluginConsumer = Consumer<Any?> { } // 仅用于推断 Consumer 泛型载体
            asyncRunNow = asyncClass.methods.firstOrNull {
                it.name == "runNow" && it.parameterCount == 2
            }
            globalRunDelayed = globalClass.methods.firstOrNull {
                it.name == "runDelayed" && it.parameterCount == 3
            }
            globalRunAtFixedRate = globalClass.methods.firstOrNull {
                it.name == "runAtFixedRate" && it.parameterCount == 4
            }
            if (asyncRunNow != null && globalRunDelayed != null && globalRunAtFixedRate != null) {
                // 把调度器实例存进 ThreadLocal 之外的静态 holder
                asyncSchedulerInstance = async
                globalSchedulerInstance = global
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Volatile
    private var asyncSchedulerInstance: Any? = null

    @Volatile
    private var globalSchedulerInstance: Any? = null

    /** Folia 任务句柄（ScheduledTask 有 cancel()）。 */
    private class FoliaTask(val handle: Any) : cn.huohuas001.bot.tools.Cancelable {
        override fun cancel() {
            runCatching { handle.javaClass.getMethod("cancel").invoke(handle) }
        }
    }

    private fun wrap(handle: Any?): cn.huohuas001.bot.tools.Cancelable? = handle?.let { FoliaTask(it) }

    /** 异步执行（无主线程语义）。 */
    fun runAsync(plugin: Plugin, task: Runnable): cn.huohuas001.bot.tools.Cancelable? {
        if (!reflectOnce()) return null
        return try {
            val consumer = Consumer<Any?> { _ -> runCatching { task.run() } }
            wrap(asyncRunNow?.invoke(asyncSchedulerInstance, plugin, consumer))
        } catch (_: Throwable) {
            null
        }
    }

    /** 全局延迟执行（tick 数）。 */
    fun runLater(plugin: Plugin, delayTicks: Long, task: Runnable): cn.huohuas001.bot.tools.Cancelable? {
        if (!reflectOnce()) return null
        return try {
            val consumer = Consumer<Any?> { _ -> runCatching { task.run() } }
            wrap(globalRunDelayed?.invoke(globalSchedulerInstance, plugin, consumer, delayTicks.coerceAtLeast(1L)))
        } catch (_: Throwable) {
            null
        }
    }

    /** 全局定时执行（tick 数，Folia 要求 period >= 1）。 */
    fun runTimer(plugin: Plugin, delayTicks: Long, periodTicks: Long, task: Runnable): cn.huohuas001.bot.tools.Cancelable? {
        if (!reflectOnce()) return null
        return try {
            val consumer = Consumer<Any?> { _ -> runCatching { task.run() } }
            wrap(
                globalRunAtFixedRate?.invoke(
                    globalSchedulerInstance, plugin, consumer,
                    delayTicks.coerceAtLeast(1L), periodTicks.coerceAtLeast(1L)
                )
            )
        } catch (_: Throwable) {
            null
        }
    }
}
