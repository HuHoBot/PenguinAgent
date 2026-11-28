package agent.spigot.onlinelist

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One download per skin per day; deleting the directory forces an immediate refresh. */
object SkinDiskCache {
    @Volatile private var directory: File? = null
    private val failed = ConcurrentHashMap<String, Long>()

    fun configure(dataFolder: File?) {
        val dir = dataFolder?.resolve("skin-cache")
        if (dir != null) dir.mkdirs()
        directory = dir
    }

    private fun key(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun sameDay(file: File): Boolean {
        val a = java.util.Calendar.getInstance().apply { timeInMillis = file.lastModified() }
        val b = java.util.Calendar.getInstance()
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    }

    fun get(url: String): ByteArray? {
        val dir = directory ?: return null
        if (failed[url]?.let { System.currentTimeMillis() - it < 60_000 } == true) return null
        val file = File(dir, key(url) + ".bin")
        if (!file.isFile || file.length() > 4L * 1024 * 1024) return null
        if (!sameDay(file)) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun put(url: String, bytes: ByteArray) {
        val dir = directory ?: return
        if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return
        runCatching {
            val target = File(dir, key(url) + ".bin")
            val temp = File(dir, target.name + ".tmp")
            Files.write(temp.toPath(), bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target.setLastModified(System.currentTimeMillis())
            // Keep the cache bounded: drop entries older than a week.
            val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        }.onFailure { failed[url] = System.currentTimeMillis() }
    }
}
