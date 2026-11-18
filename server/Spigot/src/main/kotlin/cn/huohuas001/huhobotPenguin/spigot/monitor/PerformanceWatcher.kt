package cn.huohuas001.huhobotPenguin.spigot.monitor

import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot

/**
 * 性能预警：周期性检查 TPS / 内存，连续异常时主动通报 QQ 群，
 * 恢复后通报一次。用于在卡顿、内存濒临耗尽（OOM 前）提前告知管理员，
 * 而不是等服务器崩溃后才由重启窗口被动发现。
 */
class PerformanceWatcher(private val plugin: HuHoBotSpigot) {

    private var timer: Cancelable? = null
    private var lowStreak = 0
    private var alerted = false
    private var lastAlertAt = 0L

    fun start() {
        stop()
        // 每 20 秒检查一次（性能告警仍按连续 3 次判定；在线人数采样更实时）
        timer = runCatching { plugin.submitTimer(400L, 400L) { tick() } }.getOrNull()
    }

    fun stop() {
        runCatching { timer?.cancel() }
        timer = null
    }

    private fun tick() {
        runCatching {
            // 在线人数采样（供查在线图片的趋势图）
            runCatching { cn.huohuas001.huhobotPenguin.spigot.onlinelist.OnlineHistoryStore.record(plugin.server.onlinePlayers.size) }
            if (!plugin.isPerformanceNotifyEnabled()) return
            val tps = readTps()
            val runtime = Runtime.getRuntime()
            val memRatio = if (runtime.maxMemory() > 0) {
                (runtime.totalMemory() - runtime.freeMemory()).toDouble() / runtime.maxMemory()
            } else 0.0
            val lowTps = tps != null && tps < plugin.getPerformanceTpsThreshold()
            val highMem = memRatio >= plugin.getPerformanceMemoryThreshold()
            if (lowTps || highMem) {
                lowStreak++
                if (lowStreak >= 3) maybeAlert(tps, memRatio)
            } else {
                lowStreak = 0
                if (alerted) {
                    alerted = false
                    QClient.sendTextToGroups(
                        "**服务器性能已恢复**\nTPS: ${tps?.let { String.format("%.1f", it) } ?: "—"} · " +
                            "内存占用: ${(memRatio * 100).toInt()}%",
                        "性能恢复通知"
                    )
                }
            }
        }.onFailure { plugin.log_warning("性能检查失败: ${it.message}") }
    }

    private fun maybeAlert(tps: Double?, memRatio: Double) {
        val now = System.currentTimeMillis()
        if (now - lastAlertAt < 30 * 60_000L) return
        lastAlertAt = now
        alerted = true
        val memory = Runtime.getRuntime()
        val memUsed = (memory.totalMemory() - memory.freeMemory()) / 1_048_576L
        val memMax = memory.maxMemory() / 1_048_576L
        plugin.log_warning("服务器性能持续异常：TPS=${tps ?: "?"}, 内存=$memUsed/$memMax MB")
        QClient.sendTextToGroups(
            "**服务器性能预警**\n" +
                "TPS: ${tps?.let { String.format("%.1f", it) } ?: "—"}（阈值 ${plugin.getPerformanceTpsThreshold()}）\n" +
                "内存: $memUsed / $memMax MB（${(memRatio * 100).toInt()}%）\n" +
                "已连续 3 分钟异常，请管理员检查服务器负载。若内存接近上限，插件可能随时因 OOM 停止运行。",
            "性能预警通知"
        )
    }

    private fun readTps(): Double? = runCatching {
        val method = plugin.server.javaClass.methods.firstOrNull { it.name == "getTPS" } ?: return null
        (method.invoke(plugin.server) as? DoubleArray)?.firstOrNull()
    }.getOrNull()
}
