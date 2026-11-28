package agent.spigot.chatlog

import agent.chatlog.ChatLogHandler
import agent.chatlog.ChatLogReply
import agent.spigot.HuHoBotSpigot
import agent.spigot.manager.ConfigManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the render worker and per-group cooldown; image encoding never runs on a server thread. */
class ChatLogManager(
    private val plugin: HuHoBotSpigot,
    private val config: ConfigManager
) : ChatLogHandler {
    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private var executor: ExecutorService? = null
    @Volatile private var stopped = false

    fun start() {
        stopped = false
        ChatLogStore.configure(config.chatLogKeep())
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "HuHoBot-ChatLog").apply { isDaemon = true }
        }
    }

    fun stop() {
        stopped = true
        executor?.shutdownNow(); executor = null
        inFlight.clear(); lastRequestAt.clear()
    }

    override fun handle(groupOpenId: String, playerFilter: String?, page: Int, reply: ChatLogReply): Boolean {
        if (!config.chatLogEnabled()) {
            reply.text("聊天记录查询已在配置中关闭（chat-log.enabled=false）。")
            return true
        }
        val now = System.currentTimeMillis()
        val cooldown = config.chatLogCooldownSeconds() * 1000
        lastRequestAt[groupOpenId]?.let { if (now - it < cooldown) { reply.busy(); return true } }
        if (!inFlight.add(groupOpenId)) { reply.busy(); return true }
        lastRequestAt[groupOpenId] = now
        val worker = executor ?: run { inFlight.remove(groupOpenId); reply.text("聊天记录功能未就绪。"); return true }
        val pageSize = config.chatLogPageSize()
        val maxLength = config.chatLogMaxLength()
        val serverName = config.serverName()
        try {
            worker.execute {
                try {
                    if (stopped) return@execute
                    val result = ChatLogStore.page(playerFilter, page, pageSize, maxLength)
                    if (result.totalPages >= 1 && page > result.totalPages) {
                        reply.outOfRange(page, result.totalPages); return@execute
                    }
                    val png = ChatLogRenderer.render(
                        serverName, result.entries, result.page, result.totalPages,
                        result.totalEntries, playerFilter, maxLength
                    )
                    val caption = " "
                    reply.image(png, caption)
                } catch (error: Throwable) {
                    plugin.log_error("聊天记录渲染失败: ${error.message}")
                    reply.failed(error.message ?: "unknown")
                } finally { inFlight.remove(groupOpenId) }
            }
        } catch (error: Throwable) {
            inFlight.remove(groupOpenId)
            reply.failed(error.message ?: "queue closed")
        }
        return true
    }
}
