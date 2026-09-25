package cn.huohuas001.bot.state

import cn.huohuas001.bot.provider.BotShared
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 群 OpenID → 群名称缓存，持久化到 group-names.dat。
 *
 * 群名称仅用于 WebUI 展示，OpenID 仍是唯一标识；查询失败时保留上次结果。
 */
object GroupDirectory {
    private const val CACHE_TTL_MILLIS = 30 * 60 * 1000L

    private data class Entry(val name: String, val fetchedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun getDataFile(): File? {
        val plugin = try { BotShared.getPlugin() } catch (_: Exception) { return null }
        return plugin.getConfigFile()?.parentFile?.resolve("group-names.dat")
    }

    fun load() {
        val file = getDataFile() ?: return
        if (!file.exists()) return
        try {
            file.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split("\t", limit = 3)
                if (parts.size == 3) {
                    entries[parts[0]] = Entry(parts[1], parts[2].toLongOrNull() ?: 0L)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun save() {
        val file = getDataFile() ?: return
        try {
            file.parentFile?.mkdirs()
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                for ((openId, entry) in entries) {
                    writer.write("$openId\t${entry.name}\t${entry.fetchedAt}")
                    writer.newLine()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun put(openId: String, name: String) {
        if (openId.isBlank() || name.isBlank()) return
        entries[openId] = Entry(name, System.currentTimeMillis())
    }

    /** 新增群时清掉旧名称，等待下次刷新重新获取。 */
    fun markAdded(openId: String) {
        entries.remove(openId)
        save()
    }

    fun nameOf(openId: String): String? = entries[openId]?.name

    fun snapshot(): Map<String, String> = entries.mapValues { it.value.name }

    fun isStale(openId: String): Boolean {
        val entry = entries[openId] ?: return true
        return System.currentTimeMillis() - entry.fetchedAt >= CACHE_TTL_MILLIS
    }

    fun knownOpenIds(): Set<String> = entries.keys.toSet()
}
