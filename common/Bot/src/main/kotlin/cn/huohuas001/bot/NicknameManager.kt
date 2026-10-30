package cn.huohuas001.bot

import cn.huohuas001.bot.provider.BotShared
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 QQ 群成员昵称 ↔ openid 的双向映射，持久化到 nicknames.dat。
 */
object NicknameManager {

    private val nicknameToOpenId = ConcurrentHashMap<String, String>()
    private val openIdToNickname = ConcurrentHashMap<String, String>()

    private fun getDataFile(): File? {
        val plugin = try { BotShared.getPlugin() } catch (_: Exception) { return null }
        return plugin.getConfigFile()?.parentFile?.resolve("nicknames.dat")
    }

    fun load() {
        val file = getDataFile() ?: return
        if (!file.exists()) return
        try {
            file.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2) {
                    val nick = parts[0]
                    val oid = parts[1]
                    // 跳过昵称就是 openid 的脏数据
                    if (isOpenId(nick) && nick.equals(oid, ignoreCase = true)) return@forEach
                    nicknameToOpenId[nick.lowercase()] = oid
                    openIdToNickname[oid] = nick
                }
            }
        } catch (_: Exception) {}
    }

    fun save() {
        val file = getDataFile() ?: return
        try {
            file.parentFile?.mkdirs()
            file.bufferedWriter(Charsets.UTF_8).use { w ->
                for ((nick, oid) in openIdToNickname) {
                    w.write("$nick\t$oid")
                    w.newLine()
                }
            }
        } catch (_: Exception) {}
    }

    private fun isOpenId(s: String): Boolean = s.matches(Regex("[0-9A-Fa-f]{20,}"))

    /**
     * 记录昵称与 openid 的映射。
     *
     * @return 映射是否发生了变化；调用方据此决定要不要 [save]。
     *         注意不要先 put 再比较——那样读到的必然是刚写进去的值，永远判不出变化。
     */
    fun put(nickname: String, openId: String): Boolean {
        if (nickname.isBlank() || openId.isBlank()) return false
        val previousOpenId = nicknameToOpenId[nickname.lowercase()]
        val previousNickname = openIdToNickname[openId]
        nicknameToOpenId[nickname.lowercase()] = openId
        openIdToNickname[openId] = nickname
        return previousOpenId != openId || previousNickname != nickname
    }

    fun getOpenId(nickname: String): String? = nicknameToOpenId[nickname.lowercase()]

    fun getNickname(openId: String): String? = openIdToNickname[openId]

    fun matchByPrefix(prefix: String): List<Pair<String, String>> {
        val lower = prefix.lowercase()
        return nicknameToOpenId.entries
            .filter { it.key.startsWith(lower) }
            .map { (nick, oid) -> (openIdToNickname[oid] ?: nick) to oid }
            .filter { (nick, oid) -> !isOpenId(nick) }
            .sortedBy { it.first }
    }

    fun all(): List<Pair<String, String>> {
        return openIdToNickname.entries
            .filter { (oid, nick) -> !isOpenId(nick) }
            .map { (oid, nick) -> nick to oid }
            .sortedBy { it.first }
    }

    fun clear() {
        nicknameToOpenId.clear()
        openIdToNickname.clear()
    }

    fun size(): Int = nicknameToOpenId.size
}
