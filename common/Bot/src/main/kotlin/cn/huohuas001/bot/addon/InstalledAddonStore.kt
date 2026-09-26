package cn.huohuas001.bot.addon

import cn.huohuas001.bot.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.io.File

/**
 * 附属插件安装记录，持久化到 installed-addons.json。
 *
 * 下载后插件要重启才会加载，仅靠已加载插件列表无法判断，
 * 因此额外记录已下载的文件，用于 WebUI 展示与删除。
 */
object InstalledAddonStore {
    private const val FILE_NAME = "installed-addons.json"

    data class Record(
        val id: String,
        val name: String,
        val version: String,
        val file: String,
        val installedAt: Long
    )

    private val records = LinkedHashMap<String, Record>()

    private fun dataFile(): File? {
        val plugin = try { BotShared.getPlugin() } catch (_: Exception) { return null }
        return plugin.getConfigFile()?.parentFile?.resolve(FILE_NAME)
    }

    @Synchronized
    fun load() {
        val file = dataFile() ?: return
        if (!file.isFile) return
        try {
            val array = JSON.parseArray(file.readText(Charsets.UTF_8)) ?: return
            records.clear()
            array.forEach { item ->
                val json = item as? JSONObject ?: return@forEach
                val id = json.getString("id")?.takeIf { it.isNotBlank() } ?: return@forEach
                records[id] = Record(
                    id = id,
                    name = json.getString("name").orEmpty(),
                    version = json.getString("version").orEmpty(),
                    file = json.getString("file").orEmpty(),
                    installedAt = json.getLongValue("installedAt")
                )
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun save() {
        val file = dataFile() ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(JSON.toJSONString(records.values), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun all(): List<Record> = records.values.toList()

    @Synchronized
    fun record(id: String, name: String, version: String, file: String) {
        if (id.isBlank()) return
        records[id] = Record(id, name, version, file, System.currentTimeMillis())
        save()
    }

    /** 按安装文件删除记录，返回被删除的记录。 */
    @Synchronized
    fun removeByFile(fileName: String): Record? {
        val target = records.values.firstOrNull { it.file.equals(fileName, ignoreCase = true) } ?: return null
        records.remove(target.id)
        save()
        return target
    }

    @Synchronized
    fun files(): Set<String> = records.values.map { it.file }.filter { it.isNotBlank() }.toSet()
}
