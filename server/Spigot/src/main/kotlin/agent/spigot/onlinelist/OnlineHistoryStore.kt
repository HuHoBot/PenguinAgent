package agent.spigot.onlinelist

import com.alibaba.fastjson.JSON
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 进出服立即追加样本，不覆盖峰值；周期采样只做心跳。磁盘写入在单独线程合并进行。 */
object OnlineHistoryStore {
    private data class Point(val at: Long, val online: Int)
    private val points = java.util.ArrayDeque<Point>()
    private var file: File? = null
    private val pending = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var writer = newWriter()
    private fun newWriter()=Executors.newSingleThreadExecutor { r -> Thread(r,"Agent-History").apply { isDaemon=true } }
    private const val KEEP = 72 * 3600000L
    @Volatile private var configureGeneration = 0L

    /** 同步加载只供确定性单元测试和离线工具使用。 */
    @Synchronized fun configure(dataFolder: File) {
        val setup = beginConfigure(dataFolder) ?: return
        val loaded = parse(setup.first)
        points.clear(); loaded.forEach(points::add)
        // The synchronous/offline API promises that legacy migration is complete before return.
        if (setup.second && points.isNotEmpty()) save()
    }

    /** 服务端启动/重载使用：磁盘读取与 JSON 解析在历史记录工作线程完成。 */
    @Synchronized fun configureAsync(dataFolder: File) {
        val setup = beginConfigure(dataFolder) ?: return
        val generation = configureGeneration
        runCatching { writer.execute {
            val loaded = parse(setup.first)
            synchronized(this) {
                if (generation != configureGeneration || closed) return@synchronized
                loaded.forEach { point -> if (points.none { it.at == point.at }) points.add(point) }
                val ordered = points.toList().sortedBy { it.at }.takeLast(10000)
                points.clear(); ordered.forEach(points::add)
                if (setup.second && points.isNotEmpty()) writer.execute { save() }
            }
        } }
    }

    private fun beginConfigure(dataFolder: File): Pair<File,Boolean>? {
        if (writer.isShutdown) writer = newWriter()
        closed = false
        val next = dataFolder.resolve("online-history.json")
        if (file?.absolutePath == next.absolutePath) return null
        configureGeneration++
        points.clear(); file = next
        val oldNested = dataFolder.resolve("data/online-history.json")
        val oldRoot = dataFolder.parentFile?.resolve("online-history.json")
        val source = if (next.isFile) next else listOfNotNull(oldNested, oldRoot).firstOrNull { it.isFile } ?: next
        return source to (source != next && source.isFile)
    }

    private fun parse(source: File): List<Point> {
        if (!source.isFile || source.length() > 4 * 1024 * 1024) return emptyList()
        return runCatching {
            val array = JSON.parseArray(source.readText()) ?: return emptyList()
            val now = System.currentTimeMillis()
            (0 until array.size).mapNotNull { i ->
                val row = array.getJSONArray(i) ?: return@mapNotNull null
                if (row.size < 2) return@mapNotNull null
                val at = row.getLongValue(0); val online = row.getIntValue(1)
                if (at in (now-KEEP)..(now+60000) && online in 0..1000000) Point(at,online) else null
            }.sortedBy { it.at }.takeLast(10000)
        }.getOrDefault(emptyList())
    }
    @JvmOverloads @Synchronized fun record(online: Int, at: Long = System.currentTimeMillis()) {
        if (closed || online < 0) return
        val last = points.peekLast()
        // 同人数仅周期心跳，人数变化永不覆写已有采样，连进连退也保留峰值。
        if (last != null && last.online == online && at - last.at < 60000) return
        points.add(Point(maxOf(at, (last?.at ?: (at-1))+1),online))
        while (points.peekFirst()?.at?.let { it < at-KEEP } == true) points.pollFirst()
        if (points.size > 10000) compact()
        if (file != null && pending.compareAndSet(false,true)) runCatching { writer.execute {
            try { save() } finally { pending.set(false) }
        } }.onFailure { pending.set(false) }
    }
    @Synchronized private fun compact() {
        val grouped = points.toList().chunked(8)
        points.clear()
        grouped.forEach { bucket ->
            listOf(bucket.first(),bucket.minBy { it.online },bucket.maxBy { it.online },bucket.last())
                .distinct().sortedBy { it.at }.forEach { points.add(it) }
        }
    }
    @JvmOverloads @Synchronized fun recent(hours: Int = 48): List<Pair<Long,Int>> {
        val cutoff = System.currentTimeMillis() - hours.coerceIn(1,72)*3600000L
        return points.filter { it.at >= cutoff }.map { it.at to it.online }
    }
    private fun save() {
        val snapshot = synchronized(this) {
            val target = file ?: return
            target to points.map { listOf(it.at,it.online) }
        }
        runCatching {
            val target = snapshot.first
            target.parentFile.mkdirs()
            val tmp = Files.createTempFile(target.parentFile.toPath(), "history-", ".tmp")
            try {
                Files.write(tmp, JSON.toJSONString(snapshot.second).toByteArray(Charsets.UTF_8))
                Files.move(tmp,target.toPath(),StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(tmp) }
        }
    }
    fun flush() = save()

    /** Stop background disk work before this plugin's classloader is closed. configure() reopens on enable. */
    /** Hot reload uses this non-blocking shutdown; queued flush completes on the daemon writer. */
    fun closeAsync() {
        closed=true
        val active=writer
        runCatching { active.execute { save() } }
        active.shutdown()
    }

    fun close() {
        closed=true
        val active=writer
        runCatching { active.execute { save() } }
        active.shutdown()
        runCatching { active.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS) }
    }
}
