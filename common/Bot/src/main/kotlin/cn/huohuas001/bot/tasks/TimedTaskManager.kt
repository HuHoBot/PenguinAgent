package cn.huohuas001.bot.tasks

import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.state.SimpleYaml
import java.io.File

/**
 * 定时任务（timed-tasks.yml）。
 *
 * 两种调度写法（二选一）：
 * - interval-seconds: N        每 N 秒执行一次
 * - cron: "m h dom mon dow"    简版 5 段 cron（支持 `*`、数字、`,`、`-`、`星号/n`）
 *
 * 动作（可同时配）：
 * - message: "..."             发送到群（group 空 = bot.groups 全部群）
 * - command: "..."             执行服务器命令（黑名单照常生效）
 *
 * 文件示例：
 * ```yaml
 * tasks:
 *   - name: 早安播报
 *     cron: "0 9 * * *"
 *     group: ""
 *     message: "服务器已开启，祝大家玩得愉快！"
 *   - name: 自动备份
 *     interval-seconds: 21600
 *     command: "save-all"
 * ```
 */
object TimedTaskManager {

    data class Task(
        val name: String,
        val cron: String?,
        val intervalSeconds: Long,
        val group: String,
        val message: String,
        val command: String
    )

    private const val FILE_NAME = "timed-tasks.yml"

    @Volatile
    private var tasks: List<Task> = emptyList()
    @Volatile
    private var file: File? = null
    @Volatile
    private var timerRunning = false

    fun configure(dataFolder: File?) {
        file = dataFolder?.resolve(FILE_NAME)
        reload()
    }

    fun reload() {
        val f = file ?: return
        val loaded: List<Task> = runCatching { loadTasks(f) }
            .onFailure { error ->
                runCatching { BotShared.getPluginOrNull()?.log_error("定时任务配置加载失败: ${error.message}") }
            }
            .getOrElse { emptyList() }
        tasks = loaded
        if (tasks.isNotEmpty()) startTimer()
    }

    private fun loadTasks(f: File): List<Task> {
        if (!f.isFile) {
            SimpleYaml.write(
                f,
                linkedMapOf("tasks" to mutableListOf<Any?>()),
                listOf(
                    "HuHoBot 定时任务配置",
                    "调度：cron(\"分 时 日 月 周\"，支持 * , - */n) 或 interval-seconds(每 N 秒)，二选一",
                    "动作：message(发群消息，group 空=全部群) 和/或 command(执行服务器命令)"
                )
            )
            return emptyList()
        }
        val root = SimpleYaml.read(f)
        val raw = root["tasks"] as? List<*> ?: emptyList<Any?>()
        val out = ArrayList<Task>()
        for (item in raw) {
            val m = item as? Map<*, *> ?: continue
            val name = (m["name"] ?: "").toString().trim()
            val cronRaw = (m["cron"] ?: "").toString().trim()
            val intervalRaw = (m["interval-seconds"] ?: "").toString().toLongOrNull() ?: 0L
            if (name.isEmpty()) continue
            if (cronRaw.isEmpty() && intervalRaw <= 0) continue
            val cron: String? = cronRaw.takeIf { CronLite.isValid(it) }
            out.add(
                Task(
                    name = name,
                    cron = cron,
                    intervalSeconds = intervalRaw,
                    group = (m["group"] ?: "").toString().trim(),
                    message = (m["message"] ?: "").toString(),
                    command = (m["command"] ?: "").toString().trim()
                )
            )
        }
        return out
    }

    fun all(): List<Task> = tasks.toList()

    /** Agent 工具入口：写回文件并热更新。 */
    fun upsert(task: Task): Boolean {
        val f = file ?: return false
        val existing = all().filterNot { it.name.equals(task.name, ignoreCase = true) } + task
        val root = linkedMapOf<String, Any?>(
            "tasks" to existing.map {
                val m = linkedMapOf<String, Any?>("name" to it.name)
                if (it.cron != null) m["cron"] = it.cron else m["interval-seconds"] = it.intervalSeconds
                m["group"] = it.group
                if (it.message.isNotBlank()) m["message"] = it.message
                if (it.command.isNotBlank()) m["command"] = it.command
                m
            }
        )
        SimpleYaml.write(
            f, root,
            listOf("HuHoBot 定时任务配置(可由 AI 通过工具维护)")
        )
        reload()
        return true
    }

    fun remove(name: String): Boolean {
        val f = file ?: return false
        val remaining = all().filterNot { it.name.equals(name, ignoreCase = true) }
        if (remaining.size == tasks.size) return false
        val root = linkedMapOf<String, Any?>("tasks" to remaining)
        SimpleYaml.write(f, root)
        reload()
        return true
    }

    /** 轻量秒级巡检：每 30 秒检查一次任务是否到期（cron 到分钟粒度）。 */
    private fun startTimer() {
        if (timerRunning) return
        timerRunning = true
        val plugin = BotShared.getPluginOrNull() ?: run { timerRunning = false; return }
        var lastMinuteKey = ""
        val intervalTicker = mutableListOf<Pair<String, Long>>() // name -> 下次执行时间戳
        plugin.submitTimer(30_000L, 30_000L) {
            if (tasks.isEmpty()) return@submitTimer
            val now = System.currentTimeMillis()
            // cron 任务：每分钟检查一次
            val minuteKey = (now / 60_000L).toString()
            if (minuteKey != lastMinuteKey) {
                lastMinuteKey = minuteKey
                val calendar = java.util.Calendar.getInstance()
                val fields = intArrayOf(
                    calendar.get(java.util.Calendar.MINUTE),
                    calendar.get(java.util.Calendar.HOUR_OF_DAY),
                    calendar.get(java.util.Calendar.DAY_OF_MONTH),
                    calendar.get(java.util.Calendar.MONTH) + 1,
                    calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1 // 0=周日
                )
                tasks.filter { it.cron != null && CronLite.matches(it.cron!!, fields) }.forEach { run(it) }
            }
            // interval 任务
            for (task in tasks.filter { it.cron == null && it.intervalSeconds >= 10 }) {
                val idx = intervalTicker.indexOfFirst { it.first == task.name }
                if (idx < 0) {
                    intervalTicker.add(task.name to now + task.intervalSeconds * 1000)
                } else if (now >= intervalTicker[idx].second) {
                    intervalTicker[idx] = task.name to now + task.intervalSeconds * 1000
                    run(task)
                }
            }
        }
    }

    private fun run(task: Task) {
        val plugin = BotShared.getPluginOrNull() ?: return
        try {
            if (task.command.isNotBlank()) {
                plugin.log_info("定时任务「${task.name}」执行命令: ${task.command}")
                plugin.sendCommand(task.command)
            }
            if (task.message.isNotBlank()) {
                val groups = if (task.group.isNotBlank()) listOf(task.group) else plugin.getGroupOpenIdList()
                groups.forEach { QClient.sendMarkdownToGroup(it, task.message) }
            }
        } catch (error: Throwable) {
            plugin.log_error("定时任务「${task.name}」执行失败: ${error.message}")
        }
    }
}

/** 极简 5 段 cron 匹配：分 时 日 月 周(0=周日)。支持 `*`、数字、`,`、`-`、`星号/n`。 */
object CronLite {
    fun isValid(expr: String): Boolean = expr.trim().split(Regex("\\s+")).size == 5

    fun matches(expr: String, fields: IntArray): Boolean {
        val parts = expr.trim().split(Regex("\\s+"))
        if (parts.size != 5 || fields.size != 5) return false
        // 周字段支持 7=周日
        val week = if (fields[4] == 0) 7 else fields[4]
        val values = intArrayOf(fields[0], fields[1], fields[2], fields[3], week)
        for (i in 0 until 5) {
            if (!fieldMatches(parts[i], values[i], i == 2 || i == 3)) return false
        }
        return true
    }

    private fun fieldMatches(part: String, value: Int, allowDomWeek: Boolean): Boolean {
        val upper = part.trim()
        if (upper == "*") return true
        if (upper.startsWith("*/")) {
            val step = upper.substring(2).toIntOrNull() ?: return false
            return step > 0 && value % step == 0
        }
        return upper.split(',').any { single ->
            val range = single.split('-')
            when (range.size) {
                1 -> range[0].toIntOrNull() == value
                2 -> {
                    val lo = range[0].toIntOrNull() ?: return@any false
                    val hi = range[1].toIntOrNull() ?: return@any false
                    value in lo..hi
                }
                else -> false
            }
        }
    }
}
