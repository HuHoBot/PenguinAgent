package cn.huohuas001.huhobotPenguin.proxy.redis

import cn.huohuas001.huhobotPenguin.proxy.HuHoBotProxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 管理 Redis 远程命令与子服务器返回结果之间的关联。 */
class CommandCallback(private val plugin: HuHoBotProxy) {
    private val pendingCallbacks = ConcurrentHashMap<String, CallbackContext>()
    private val defaultTimeoutMillis = 10_000L

    /**
     * 发送带任务 ID 的远程命令，并在 10 秒内接收 `[OUTPUT]`、`[COMPLETE]` 或 `[ERROR]` 回调。
     */
    fun executeWithCallback(
        serverName: String,
        command: String,
        onOutput: (String) -> Unit,
        onComplete: () -> Unit = {},
        onTimeout: () -> Unit = {}
    ): String {
        val taskId = UUID.randomUUID().toString()
        pendingCallbacks[taskId] = CallbackContext(
            taskId = taskId,
            serverName = serverName,
            command = command,
            onOutput = onOutput,
            onComplete = onComplete,
            onTimeout = onTimeout,
            outputBuffer = StringBuilder(),
            startTime = System.currentTimeMillis()
        )

        val sent = plugin.redisManager?.sendCommandWithCallback(serverName, taskId, command) == true
        if (!sent) {
            pendingCallbacks.remove(taskId)?.onTimeout?.invoke()
            return taskId
        }

        plugin.submitLater(defaultTimeoutMillis / 50) {
            pendingCallbacks.remove(taskId)?.onTimeout?.invoke()
        }
        return taskId
    }

    /** 回调格式：`taskId|[OUTPUT|COMPLETE|ERROR]|content`。 */
    fun handleCallback(message: String) {
        val parts = message.split("|", limit = 3)
        if (parts.size < 3) {
            plugin.log_warning("收到格式错误的 Redis 命令回调: $message")
            return
        }

        val taskId = parts[0]
        val type = parts[1]
        val content = parts[2]
        val context = pendingCallbacks[taskId] ?: return

        when (type) {
            "[OUTPUT]" -> {
                synchronized(context.outputBuffer) { context.outputBuffer.appendLine(content) }
                context.onOutput(content)
            }

            "[COMPLETE]" -> {
                pendingCallbacks.remove(taskId)
                context.onComplete()
            }

            "[ERROR]" -> {
                synchronized(context.outputBuffer) { context.outputBuffer.appendLine("错误: $content") }
                context.onOutput("错误: $content")
                pendingCallbacks.remove(taskId)
                context.onComplete()
            }

            else -> plugin.log_warning("收到未知类型的 Redis 命令回调: $type")
        }
    }

    fun getTaskOutput(taskId: String): String? = pendingCallbacks[taskId]?.let { context ->
        synchronized(context.outputBuffer) { context.outputBuffer.toString() }
    }

    fun cancelTask(taskId: String) {
        pendingCallbacks.remove(taskId)
    }

    fun getPendingCount(): Int = pendingCallbacks.size

    fun cancelAll() {
        pendingCallbacks.clear()
    }

    data class CallbackContext(
        val taskId: String,
        val serverName: String,
        val command: String,
        val onOutput: (String) -> Unit,
        val onComplete: () -> Unit,
        val onTimeout: () -> Unit,
        val outputBuffer: StringBuilder,
        val startTime: Long
    )
}
