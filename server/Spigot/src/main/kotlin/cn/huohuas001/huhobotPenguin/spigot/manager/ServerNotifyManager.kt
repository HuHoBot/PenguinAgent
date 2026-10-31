package cn.huohuas001.huhobotPenguin.spigot.manager

import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.Bukkit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.management.ManagementFactory
import java.lang.reflect.Method
import java.util.Properties

/**
 * 服务器关机 / 重启 / 意外关闭的 QQ 群通知。
 *
 * Bukkit 里「服务器停止」和「插件被重载」都会走一次 onDisable，只看 onDisable 无法区分；
 * 「关机」和「重启」在插件视角下也完全一样（都是先 onDisable，再由新进程 onEnable）。
 * 所以这里用一个状态文件跨进程记录上一次运行是怎么结束的：
 *
 * - 每次 onEnable 写入 `clean=false`，表示「这次运行还没正常收尾」；
 * - onDisable 写入 `clean=true`：真实关服时同时记录停止时间，插件重载则停止时间留空；
 * - 下次启动时读取：
 *   - `clean=false` → 上次是崩溃 / kill -9 / 断电，发「意外关闭」；
 *   - `clean=true` 且距停止时间不超过判定窗口 → 视为重启，发「重启完成」；
 *   - 其他情况 → 普通开机，不发送。
 *
 * 崩溃通知只能在下次启动时补发，这是进程已经死掉后唯一可行的时机。
 */
class ServerNotifyManager(
    private val plugin: HuHoBotSpigot,
    private val config: ConfigManager
) {

    /** 当前实例是否还需要继续等待 QQ 就绪；插件重载或服务器停止时置为 false。 */
    @Volatile
    private var active = true

    @Volatile
    private var startupNotifier: Thread? = null

    private val stateFile: File
        get() = File(plugin.dataFolder, STATE_FILE_NAME)

    /** Paper 等平台提供 Server#isStopping()；Spigot 没有，只能退到 NMS 的 isRunning()。 */
    private val isStoppingMethod: Method? by lazy {
        runCatching { Bukkit.getServer().javaClass.getMethod("isStopping") }.getOrNull()
    }

    /** Spigot 的 CraftServer#getServer() 返回 NMS 服务端实例，其 isRunning() 在停止时已是 false。 */
    private val nmsServerHandle: Any? by lazy {
        runCatching {
            Bukkit.getServer().javaClass.getMethod("getServer").invoke(Bukkit.getServer())
        }.getOrNull()
    }

    private val nmsIsRunningMethod: Method? by lazy {
        runCatching { nmsServerHandle?.javaClass?.getMethod("isRunning") }.getOrNull()
    }

    /** 服务器启动完成、QQ 客户端已开始异步登录后调用。 */
    fun onServerStarted() {
        val previous = readState()
        val currentJvmStart = ManagementFactory.getRuntimeMXBean().startTime
        val previousJvmStart = previous?.getProperty(KEY_JVM_START)?.toLongOrNull()
        val previousClean = previous?.getProperty(KEY_CLEAN)?.toBooleanStrictOrNull() ?: false
        val previousStopTime = previous?.getProperty(KEY_STOP_TIME)?.toLongOrNull() ?: 0L

        // 同一个 JVM 内重新 onEnable 属于插件重载，不能按「上一次运行」来判断。
        if (previousJvmStart != null && previousJvmStart > 0 && previousJvmStart != currentJvmStart) {
            val now = System.currentTimeMillis()
            val restartWindowMs = config.serverNotifyRestartWindow() * 1000L
            when {
                !previousClean -> {
                    plugin.log_info("检测到上次服务器未正常关闭，将在 QQ 就绪后发送意外关闭通知")
                    notifyAfterStartup(
                        config.serverNotifyCrashEnabled(),
                        config.serverNotifyCrashMessage()
                    )
                }

                previousStopTime > 0 && now - previousStopTime in 0..restartWindowMs -> {
                    notifyAfterStartup(
                        config.serverNotifyRestartEnabled(),
                        config.serverNotifyRestartMessage()
                    )
                }
            }
        }

        // 先标记为「未收尾」，之后无论崩溃还是正常停止都会覆盖这个状态。
        writeState(clean = false, stopTime = 0L, jvmStart = currentJvmStart)
    }

    /**
     * 平台 onDisable 的最开始调用，此时 QQ 客户端仍可用。
     *
     * 插件重载 / 手动停用时 [isRealServerStop] 会返回 false：不发关机消息，也不记停止时间，
     * 但仍然记一次正常收尾，避免之后服务器正常关服时被误报成「意外关闭」。
     */
    fun onServerStopping() {
        active = false
        startupNotifier?.interrupt()
        startupNotifier = null

        val realStop = isRealServerStop()
        if (realStop) {
            try {
                val message = config.serverNotifyShutdownMessage()
                if (config.serverNotifyShutdownEnabled() && message.isNotBlank() && QClient.isReady()) {
                    val text = formatMessage(message)
                    plugin.getGroupOpenIdList().forEach { groupId ->
                        QClient.sendMarkdownToGroup(groupId, text)
                    }
                }
            } catch (error: Throwable) {
                // 通知失败不能阻断插件卸载流程，但正常收尾状态仍要记录。
                plugin.log_warning("发送关机通知失败: ${error.message}")
            }
        } else {
            plugin.log_info("检测到插件重载或手动停用（服务器仍在运行），跳过关机通知")
        }

        // 无论是否真的关服都要记一次正常收尾，避免「手动停用插件后再关服」被误报成崩溃。
        // 只有真实停止才写入停止时间，插件重载不会被算成一次重启。
        writeState(
            clean = true,
            stopTime = if (realStop) System.currentTimeMillis() else 0L,
            jvmStart = ManagementFactory.getRuntimeMXBean().startTime
        )
    }

    /**
     * QQ 是异步登录的，启动后立刻发送通常还没鉴权成功。
     * 这里用一个守护线程轮询 [QClient.isReady]，就绪后再发，避免错过通知。
     */
    private fun notifyAfterStartup(enabled: Boolean, message: String) {
        if (!enabled || message.isBlank()) return
        val groups = plugin.getGroupOpenIdList()
        if (groups.isEmpty()) {
            plugin.log_info("未配置 QQ 群，跳过服务器状态通知")
            return
        }

        val text = formatMessage(message)
        val thread = Thread({
            var waited = 0
            while (active && !QClient.isReady()) {
                if (waited++ >= READY_MAX_ATTEMPTS) {
                    plugin.log_warning("服务器状态通知未发送：等待 QQ 鉴权超时")
                    return@Thread
                }
                try {
                    Thread.sleep(RETRY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            if (!active) return@Thread

            repeat(SEND_MAX_ATTEMPTS) { attempt ->
                val sent = groups.count { QClient.sendMarkdownToGroup(it, text) != null }
                if (sent > 0) return@Thread
                if (attempt < SEND_MAX_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(RETRY_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            plugin.log_warning("服务器状态通知发送失败：$text")
        }, "HuHoBot-ServerNotify")
        thread.isDaemon = true
        startupNotifier = thread
        thread.start()
    }

    /**
     * 判断本次 onDisable 是否由服务器停止触发。
     *
     * 依次尝试：
     * 1. Paper 的 `Server#isStopping()`；
     * 2. Spigot 的 `CraftServer#getServer()` → NMS `MinecraftServer#isRunning()`，停止流程里它已为 false；
     * 3. 都拿不到时按真实停止处理（插件热重载会多发一次关机通知，但不会漏发关服通知）。
     */
    private fun isRealServerStop(): Boolean {
        isStoppingMethod?.let { method ->
            runCatching { method.invoke(Bukkit.getServer()) as? Boolean }.getOrNull()?.let { return it }
        }
        val handle = nmsServerHandle
        val method = nmsIsRunningMethod
        if (handle != null && method != null) {
            runCatching { method.invoke(handle) as? Boolean }.getOrNull()?.let { return !it }
        }
        return true
    }

    private fun formatMessage(raw: String): String = raw
        .replace("{server}", plugin.getServerName())
        .replace("{platform}", plugin.getPlatform())
        .trim()

    private fun readState(): Properties? {
        val file = stateFile
        if (!file.isFile) return null
        return runCatching {
            Properties().apply { FileInputStream(file).use { load(it) } }
        }.getOrNull()
    }

    private fun writeState(clean: Boolean, stopTime: Long, jvmStart: Long) {
        val properties = Properties().apply {
            setProperty(KEY_JVM_START, jvmStart.toString())
            setProperty(KEY_STOP_TIME, stopTime.toString())
            setProperty(KEY_CLEAN, clean.toString())
        }
        runCatching {
            stateFile.parentFile?.mkdirs()
            FileOutputStream(stateFile).use { properties.store(it, "HuHoBot server notify state") }
        }.onFailure { error ->
            plugin.log_warning("写入服务器状态文件失败: ${error.message}")
        }
    }

    private companion object {
        const val STATE_FILE_NAME = "server-notify-state.properties"
        const val KEY_JVM_START = "jvm-start"
        const val KEY_STOP_TIME = "stop-time"
        const val KEY_CLEAN = "clean"

        /** 等待 QQ 鉴权的轮询间隔。 */
        const val RETRY_INTERVAL_MS = 2000L

        /** 最多等待 60 秒让 QQ 完成鉴权。 */
        const val READY_MAX_ATTEMPTS = 30

        /** 鉴权成功后发送失败时的重试次数。 */
        const val SEND_MAX_ATTEMPTS = 3
    }
}
