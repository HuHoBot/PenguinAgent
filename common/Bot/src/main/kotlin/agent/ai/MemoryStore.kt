package agent.ai

import agent.provider.BotShared
import java.io.File

/**
 * AI 长期记忆（按群存储，YAML 行格式持久化，适配面板服——不依赖数据库）。
 *
 * 文件位置：data/agent-memory 目录，每个群一个 YAML 文件。
 * 用户可在 QQ 群里用「记忆」命令分页查看、「记忆删除」削减；AI 通过
 * save_memory / recall_memory 工具随时写入和调取远古记忆。
 *
 * 文件格式（YAML 子集，零依赖手写解析）：
 *   - 第一行：条目文本（转义后）
 */
object MemoryStore {

    data class Entry(val text: String, val at: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, MutableList<Entry>>()
    private val lock = Any()

    private fun maxItems(): Int =
        runCatching { BotShared.getPlugin().getAgentMemoryMaxItems() }.getOrDefault(200).coerceIn(10, 2000)

    fun file(groupId: String): File {
        val dir = BotShared.getPlugin().getDataDirectory()?.resolve("agent-memory") ?: File("agent-memory")
        if (!dir.isDirectory) dir.mkdirs()
        val safe = groupId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, safe + ".yml")
    }

    fun list(groupId: String): MutableList<Entry> = synchronized(lock) {
        cache.getOrPut(groupId) { load(groupId).toMutableList() }
    }

    fun add(groupId: String, text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        synchronized(lock) {
            val items = list(groupId)
            if (items.any { it.text == clean }) return false
            items.add(Entry(clean.take(500), System.currentTimeMillis()))
            while (items.size > maxItems()) items.removeAt(0)
            save(groupId)
            return true
        }
    }

    fun removeAt(groupId: String, index1Based: Int): Boolean = synchronized(lock) {
        val items = list(groupId)
        val idx = index1Based - 1
        if (idx < 0 || idx >= items.size) return false
        items.removeAt(idx)
        save(groupId)
        return true
    }

    fun clear(groupId: String) = synchronized(lock) {
        list(groupId).clear()
        save(groupId)
    }

    fun search(groupId: String, keyword: String): List<Entry> =
        list(groupId).filter { keyword.isNotBlank() && it.text.contains(keyword, ignoreCase = true) }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\\\", "\\")

    private fun load(groupId: String): List<Entry> = runCatching {
        val f = file(groupId)
        if (!f.isFile) return emptyList()
        val out = mutableListOf<Entry>()
        f.readLines(Charsets.UTF_8).forEach { line ->
            val t = line.trim()
            if (t.startsWith("- ") || t.startsWith("-")) {
                val body = t.removePrefix("- ").trim()
                if (body.isNotEmpty()) {
                    val sep = body.lastIndexOf('|')
                    val at = if (sep > 0) body.substring(sep + 1).toLongOrNull() ?: 0L else 0L
                    val text = if (sep > 0) unescape(body.substring(0, sep)) else unescape(body)
                    if (text.isNotBlank()) out.add(Entry(text, at))
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun save(groupId: String) = runCatching {
        val sb = StringBuilder("# HuHoBot AI 记忆（每行一条；文本|时间戳）\n")
        list(groupId).forEach { e -> sb.append("- ").append(escape(e.text)).append('|').append(e.at).append('\n') }
        file(groupId).writeText(sb.toString(), Charsets.UTF_8)
    }
}
