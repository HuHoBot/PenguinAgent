package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 在线人数历史（用于查在线图片的折线图）。
 *
 * 每 5 分钟采样一次，保留 72 小时；数据落盘到
 * `plugins/HuHoBotPenguin/online-history.json`，重启后仍可展示趋势。
 */
object OnlineHistoryStore {

    private const val SAMPLE_INTERVAL_MS = 5 * 60_000L
    private const val RETENTION_MS = 72 * 3_600_000L
    private const val SAVE_INTERVAL_MS = 10 * 60_000L

    private val points = ConcurrentLinkedDeque<LongArray>()  // [timestamp, online]
    @Volatile
    private var file: File? = null
    @Volatile
    private var lastSaveAt = 0L

    fun configure(dataFolder: File) {
        file = dataFolder.resolve("online-history.json")
        load()
    }

    /** 记录一次采样（内部节流：距上次采样不足 5 分钟时只更新最后一点）。 */
    fun record(online: Int) {
        val now = System.currentTimeMillis()
        val last = points.peekLast()
        if (last == null || now - last[0] >= SAMPLE_INTERVAL_MS) {
            points.addLast(longArrayOf(now, online.toLong()))
        } else {
            last[1] = online.toLong()
        }
        val cutoff = now - RETENTION_MS
        while (true) {
            val head = points.peekFirst() ?: break
            if (head[0] < cutoff) points.pollFirst() else break
        }
        if (now - lastSaveAt >= SAVE_INTERVAL_MS) save()
    }

    /** 最近 hours 小时内的采样点（时间升序）。 */
    fun recent(hours: Int = 48): List<Pair<Long, Int>> {
        val cutoff = System.currentTimeMillis() - hours * 3_600_000L
        return points.filter { it[0] >= cutoff }.map { it[0] to it[1].toInt() }
    }

    private fun load() {
        val target = file ?: return
        if (!target.isFile) return
        runCatching {
            val array = JSON.parseArray(target.readText(Charsets.UTF_8)) ?: return
            points.clear()
            for (index in 0 until array.size) {
                val item = array.getJSONArray(index) ?: continue
                if (item.size < 2) continue
                points.addLast(longArrayOf(item.getLongValue(0), item.getLongValue(1)))
            }
        }
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            val array = JSONArray()
            points.forEach { array.add(JSONArray().apply { add(it[0]); add(it[1]) }) }
            target.parentFile?.mkdirs()
            target.writeText(array.toJSONString(), Charsets.UTF_8)
            lastSaveAt = System.currentTimeMillis()
        }
    }

    fun flush() = save()
}
