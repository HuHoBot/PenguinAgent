package agent.spigot.manager

import agent.tools.Cancelable
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Owns scheduling and cancellation; a failed native dispatch must never fall through to another scheduler. */
object FoliaScheduler {
    private val context = ThreadLocal<Boolean>()
    private val native by lazy {
        runCatching { Class.forName(ServerModeDetector.REGION_CLASS); true }.getOrDefault(false)
    }
    @Volatile private var detected: ServerMode? = null
    @Volatile private var configured: ServerMode? = null
    private val noop = object : Cancelable { override fun cancel() {} }

    /** auto keeps the runtime answer; explicit values let a hybrid owner choose scheduling. */
    fun configure(mode: ServerMode?) { configured = mode }

    @JvmStatic fun mode(): ServerMode = configured ?: detected ?: detectMode().also { detected = it }

    private fun detectMode(): ServerMode {
        // Probe existence, not the name constant: comparing strings would report FOLIA everywhere.
        val present = runCatching { Class.forName(ServerModeDetector.REGION_CLASS) }.isSuccess
        return ServerModeDetector.detect { present }
    }

    @JvmStatic fun detectRuntime(): Boolean = native
    @JvmStatic fun isFolia(): Boolean = mode() != ServerMode.PAPER

    /** Regionised call with a hybrid-only fallback; a strict Folia failure is never silently downgraded. */
    private inline fun <T> scheduling(regionised: () -> T, legacy: () -> T): T {
        if (mode() == ServerMode.PAPER) return legacy()
        return try { regionised() } catch (error: Throwable) {
            if (mode() == ServerMode.HYBRID) legacy() else throw error
        }
    }
    @JvmStatic fun isGlobalThread(): Boolean = if (!native) Bukkit.isPrimaryThread() else
        runCatching { Bukkit::class.java.getMethod("isGlobalTickThread").invoke(null) as Boolean }.getOrDefault(false)
    @JvmStatic fun isTickThread(): Boolean {
        if (context.get() == true || Bukkit.isPrimaryThread()) return true
        if (!native) return false
        for (name in listOf("ca.spottedleaf.moonrise.common.util.TickThread", "io.papermc.paper.util.TickThread")) {
            val answer = runCatching { Class.forName(name).getMethod("isTickThread").invoke(null) as Boolean }.getOrNull()
            if (answer != null) return answer
        }
        return isGlobalThread()
    }
    @JvmStatic fun owns(entity: Entity): Boolean = if (!native) Bukkit.isPrimaryThread() else
        Bukkit::class.java.getMethod("isOwnedByCurrentRegion", Entity::class.java).invoke(null, entity) as Boolean
    private fun invoke(target: Any, api: String, name: String, vararg args: Any?): Any? {
        val type = Class.forName("io.papermc.paper.threadedregions.scheduler.$api")
        val method = type.methods.firstOrNull { m -> m.name == name && m.parameterCount == args.size &&
            m.parameterTypes.zip(args).all { (t, v) -> v == null || t.isInstance(v) || t.isPrimitive && v is Number } }
            ?: throw IllegalStateException("Missing scheduler operation $name/${args.size}")
        return try { method.invoke(target, *args) } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    }
    private fun scheduler(name: String): Any = Bukkit.getServer().javaClass.getMethod(name).invoke(Bukkit.getServer())
    private fun wrap(handle: Any?): Cancelable = if (handle == null) noop else object : Cancelable {
        override fun cancel() { invoke(handle, "ScheduledTask", "cancel") }
    }
    private fun consumer(plugin: Plugin, task: Runnable, tick: Boolean): Consumer<Any?> = Consumer {
        if (!plugin.isEnabled) return@Consumer
        val previous = context.get()
        if (tick) context.set(true)
        try { task.run() }
        catch (error: Throwable) { plugin.logger.log(java.util.logging.Level.SEVERE, "Scheduled operation failed", error) }
        finally { if (previous == null) context.remove() else context.set(previous) }
    }
    @JvmStatic fun runNow(plugin: Plugin, task: Runnable): Cancelable = scheduling(
        { wrap(invoke(scheduler("getGlobalRegionScheduler"), "GlobalRegionScheduler", "run", plugin, consumer(plugin, task, true))) },
        { agent.spigot.HuHoBotTask(Bukkit.getScheduler().runTask(plugin, task)) })
    @JvmStatic fun runLater(plugin: Plugin, delayTicks: Long, task: Runnable): Cancelable = scheduling(
        { wrap(invoke(scheduler("getGlobalRegionScheduler"), "GlobalRegionScheduler", "runDelayed", plugin, consumer(plugin, task, true), delayTicks.coerceAtLeast(1))) },
        { agent.spigot.HuHoBotTask(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks.coerceAtLeast(1))) })
    @JvmStatic fun runTimer(plugin: Plugin, delayTicks: Long, periodTicks: Long, task: Runnable): Cancelable = scheduling(
        { wrap(invoke(scheduler("getGlobalRegionScheduler"), "GlobalRegionScheduler", "runAtFixedRate", plugin, consumer(plugin, task, true), delayTicks.coerceAtLeast(1), periodTicks.coerceAtLeast(1))) },
        { agent.spigot.HuHoBotTask(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks.coerceAtLeast(1), periodTicks.coerceAtLeast(1))) })
    @JvmStatic fun runAsync(plugin: Plugin, task: Runnable): Cancelable = scheduling(
        { wrap(invoke(scheduler("getAsyncScheduler"), "AsyncScheduler", "runNow", plugin, consumer(plugin, task, false))) },
        { agent.spigot.HuHoBotTask(Bukkit.getScheduler().runTaskAsynchronously(plugin, task)) })
    @JvmStatic fun runAsyncTimer(plugin: Plugin, delayTicks: Long, periodTicks: Long, task: Runnable): Cancelable = scheduling(
        { wrap(invoke(scheduler("getAsyncScheduler"), "AsyncScheduler", "runAtFixedRate", plugin, consumer(plugin, task, false), delayTicks.coerceAtLeast(1)*50, periodTicks.coerceAtLeast(1)*50, TimeUnit.MILLISECONDS)) },
        { agent.spigot.HuHoBotTask(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks.coerceAtLeast(1), periodTicks.coerceAtLeast(1))) })
    @JvmStatic fun runForEntity(plugin: Plugin, entity: Entity, task: Runnable): Cancelable =
        runForEntityLater(plugin, entity, 1, task, Runnable {})
    @JvmStatic fun runForEntityLater(plugin: Plugin, entity: Entity, delay: Long, task: Runnable, retired: Runnable): Cancelable {
        if (mode() == ServerMode.PAPER) return runLater(plugin, delay, task)
        val owner = entity.javaClass.getMethod("getScheduler").invoke(entity)
        return wrap(invoke(owner, "EntityScheduler", "runDelayed", plugin, consumer(plugin, task, true), retired, delay.coerceAtLeast(1)))
    }
    @JvmStatic fun runAt(plugin: Plugin, location: Location, task: Runnable): Cancelable = scheduling(
        { wrap(invoke(scheduler("getRegionScheduler"), "RegionScheduler", "run", plugin, location, consumer(plugin, task, true))) },
        { runNow(plugin, task) })
    @JvmStatic fun forEntity(plugin: Plugin, entity: Entity, task: Runnable) {
        if (owns(entity)) task.run() else runForEntity(plugin, entity, task)
    }
    fun <T> entityFuture(plugin: Plugin, entity: Entity, action: () -> T): CompletableFuture<T?> {
        val result = CompletableFuture<T?>()
        val run = Runnable { try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) } }
        try { if (owns(entity)) run.run() else runForEntityLater(plugin, entity, 1, run, Runnable { result.complete(null) }) }
        catch (error: Throwable) { result.completeExceptionally(error) }
        return result.orTimeout(5, TimeUnit.SECONDS)
    }
    fun <T> globalFuture(plugin: Plugin, action: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        val run = Runnable { try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) } }
        try { if (isGlobalThread()) run.run() else runNow(plugin, run) } catch (error: Throwable) { result.completeExceptionally(error) }
        return result.orTimeout(5, TimeUnit.SECONDS)
    }
}
