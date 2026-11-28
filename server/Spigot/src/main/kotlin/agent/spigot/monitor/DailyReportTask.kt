package agent.spigot.monitor

import agent.QClient
import agent.spigot.HuHoBotSpigot
import agent.spigot.onlinelist.OnlineHistoryStore
import agent.tools.Cancelable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 每日群报：到设定时间向所有配置群推送过去 24 小时的服务器摘要
 * （在线峰值/均值/当前在线/TPS/内存/运行时长）。
 * 调度每分钟检查一次，一天只发一次；重启后当日内不会重复发。
 */
object DailyReportTask {
    private const val CONFIG_FILE = "daily-report-state.properties"

    @Volatile private var task: Cancelable? = null

    fun start(plugin: HuHoBotSpigot) {
        stop()
        task = plugin.submitTimer(60_000L, 60_000L) { tick(plugin) }
    }

    fun stop() { task?.cancel(); task = null }

    private fun tick(plugin: HuHoBotSpigot) {
        try {
            if (!plugin.isDailyReportEnabled()) return
            val target = runCatching { LocalTime.parse(plugin.getDailyReportTime()) }.getOrNull() ?: return
            val now = LocalTime.now().withSecond(0)
            if (now.isBefore(target) || now.isAfter(target.plusMinutes(2))) return
            val today = LocalDate.now().toString()
            val state = plugin.getDataDirectory().resolve(CONFIG_FILE)
            if (state.isFile && state.readText().trim() == today) return
            val text = buildReport(plugin, today) ?: return
            QClient.sendTextToGroups(text, "发送每日群报")
            runCatching { state.writeText(today) }
            plugin.log_info("每日群报已发送")
        } catch (error: Throwable) {
            plugin.log_warning("每日群报生成失败: ${error.message}")
        }
    }

    fun buildReport(plugin: HuHoBotSpigot, today: String): String? {
        val history = OnlineHistoryStore.recent(48)
            .filter { it.first >= System.currentTimeMillis() - 24 * 3600_000L }
        val peak = history.maxOfOrNull { it.second } ?: -1
        val avg = if (history.isEmpty()) 0.0 else history.map { it.second }.average()
        val diagnostics = plugin.getServerDiagnostics()
        return buildString {
            appendLine("**📊 每日群报 · $today**")
            if (peak >= 0) {
                appendLine("过去 24 小时在线峰值：**$peak 人**")
                appendLine("平均在线：%.1f 人".format(avg))
            }
            appendLine("当前在线：${plugin.getOnlineList().size} 人")
            diagnostics?.lineSequence()?.forEach { line ->
                if (line.startsWith("TPS") || line.startsWith("平均") || line.startsWith("内存") || line.startsWith("运行时长")) appendLine(line)
            }
            appendLine("—— Powered by HuHoBot")
        }.trim()
    }
}
