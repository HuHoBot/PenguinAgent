package cn.huohuas001.bot.state

import cn.huohuas001.bot.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 按群存储「指令面板与可用指令」的覆盖配置。
 *
 * 存储位置：`<插件数据目录>/groups/<groupId>/config.yml`（JSON/YAML 兼容的简单键值写法，便于跨平台；
 * 实际写入为 YAML 风格的扁平/嵌套结构由各平台也可覆盖；这里使用 JSON 文件 `config.json` 作为通用回退？
 * 不——用户明确要求 `config.yml`）。
 *
 * 为了在 common 层可用且不依赖 Bukkit，这里用最小 YAML 读写（仅支持本功能需要的布尔/数字/嵌套 map）。
 * Spigot 侧如需更完整行为可通过 [HuHoBot] 覆盖读写。
 *
 * 仅覆盖与命令面板/可用指令相关的键：
 * - `commands.<name>.enable` / `commands.<name>.pushMenu` / `commands.<name>.priority`
 * - `command-panel.show-admin-commands`
 */
object GroupCommandConfigStore {

    /** 允许按群覆盖的配置键前缀/精确键。 */
    val OVERRIDABLE_PATHS = setOf(
        "command-panel.show-admin-commands"
    )
    const val COMMANDS_PREFIX = "commands."

    private val cache = ConcurrentHashMap<String, MutableMap<String, Any?>>()
    @Volatile
    private var groupsRoot: File? = null

    /** 显式配置群配置目录根（`.../groups`）。应在插件数据目录可用时尽早调用。 */
    fun configure(groupsDir: File?) {
        groupsRoot = groupsDir
    }

    /** 便捷：根据插件数据目录配置（`dataDir/groups`）。 */
    fun configureDataDir(dataDir: File?) {
        groupsRoot = dataDir?.resolve("groups")
    }

    fun isOverridable(path: String): Boolean =
        path == "command-panel.show-admin-commands" ||
            path == "commands" ||
            path.startsWith(COMMANDS_PREFIX) ||
            path == "command-panel" ||
            path.startsWith("command-panel.")

    private fun groupsDir(): File? {
        groupsRoot?.let { return it }
        val plugin = BotShared.getPluginOrNull() ?: return null
        return plugin.getConfigFile()?.parentFile?.resolve("groups")
    }

    private fun logError(message: String) {
        BotShared.getPluginOrNull()?.log_error(message) ?: System.err.println("[HuHoBot] $message")
    }

    fun groupDir(groupId: String): File? {
        val id = sanitize(groupId)
        if (id.isEmpty()) return null
        return groupsDir()?.resolve(id)
    }

    fun configFile(groupId: String): File? = groupDir(groupId)?.resolve("config.yml")

    fun sanitize(groupId: String): String =
        groupId.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun load(groupId: String): MutableMap<String, Any?> {
        val id = groupId.trim()
        if (id.isEmpty()) return mutableMapOf()
        cache[id]?.let { return LinkedHashMap(it) }
        val file = configFile(id) ?: return mutableMapOf()
        val values = if (file.isFile) readSimpleYaml(file) else mutableMapOf()
        cache[id] = values
        return LinkedHashMap(values)
    }

    fun save(groupId: String, values: Map<String, Any?>): Boolean {
        val id = groupId.trim()
        val file = configFile(id) ?: return false
        val normalized = normalizeForSave(values)
        return try {
            file.parentFile?.mkdirs()
            // 记录真实 openId，便于文件夹名被清洗时仍可识别
            normalized["group-open-id"] = id
            writeSimpleYaml(file, normalized)
            cache[id] = normalized
            true
        } catch (e: Exception) {
            logError("保存群配置失败 ($id): ${e.message}")
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalizeForSave(values: Map<String, Any?>): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        // 展开 dotted 键 + 保留嵌套结构
        for ((k, v) in values) {
            if (k == "group-open-id") continue
            if (k == "commands" || k.startsWith(COMMANDS_PREFIX)) {
                if (k == "commands" && v is Map<*, *>) {
                    val dst = (out.getOrPut("commands") { LinkedHashMap<String, Any?>() } as MutableMap<String, Any?>)
                    deepMerge(dst, v as Map<String, Any?>)
                } else if (k.startsWith(COMMANDS_PREFIX)) {
                    setDotted(out, k, v)
                }
            } else if (k == "command-panel.show-admin-commands" || k == "command-panel" || k.startsWith("command-panel.")) {
                if (k == "command-panel.show-admin-commands") setDotted(out, k, v)
                else if (k == "command-panel" && v is Map<*, *>) {
                    val show = v["show-admin-commands"]
                    if (show != null) setDotted(out, "command-panel.show-admin-commands", show)
                } else if (k == "command-panel.show-admin-commands".replace("command-panel.", "command-panel.")) {
                    // unreachable; keep explicit
                    setDotted(out, k, v)
                }
            }
        }
        // 只保留实际允许的叶子，避免写入意外字段
        val cleaned = LinkedHashMap<String, Any?>()
        val commands = out["commands"] as? Map<*, *>
        if (commands != null && commands.isNotEmpty()) cleaned["commands"] = sanitizeCommands(commands)
        val showAdmin = getDotted(out, "command-panel.show-admin-commands")
        if (showAdmin != null) {
            cleaned["command-panel"] = linkedMapOf("show-admin-commands" to coerceBoolean(showAdmin))
        }
        return cleaned
    }

    @Suppress("UNCHECKED_CAST")
    private fun sanitizeCommands(commands: Map<*, *>): LinkedHashMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((nameAny, raw) in commands) {
            val name = nameAny?.toString()?.trim().orEmpty()
            if (name.isEmpty()) continue
            val entry = LinkedHashMap<String, Any?>()
            when (raw) {
                is Map<*, *> -> {
                    raw["enable"]?.let { entry["enable"] = coerceBoolean(it) }
                    raw["pushMenu"]?.let { entry["pushMenu"] = coerceBoolean(it) }
                    raw["priority"]?.let { coerceInt(it)?.coerceIn(0, 999)?.let { p -> entry["priority"] = p } }
                }
                is Boolean -> entry["enable"] = raw
            }
            if (entry.isNotEmpty()) result[name] = entry
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun deepMerge(dst: MutableMap<String, Any?>, src: Map<String, Any?>) {
        for ((k, v) in src) {
            val key = k?.toString() ?: continue
            val existing = dst[key]
            if (existing is MutableMap<*, *> && v is Map<*, *>) {
                deepMerge(existing as MutableMap<String, Any?>, v as Map<String, Any?>)
            } else if (v is Map<*, *>) {
                val child = LinkedHashMap<String, Any?>()
                deepMerge(child, v as Map<String, Any?>)
                dst[key] = child
            } else {
                dst[key] = v
            }
        }
    }

    private fun coerceBoolean(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.toBooleanStrictOrNull() ?: v == "1"
        else -> false
    }

    private fun coerceInt(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    fun applyChanges(groupId: String, changes: Map<String, Any?>): Boolean {
        val current = load(groupId)
        changes.forEach { (path, value) ->
            if (isOverridable(path)) setDotted(current, path, value)
        }
        // 移除空的 commands 子项？保留即可
        return save(groupId, current)
    }

    fun clear(groupId: String): Boolean {
        val id = groupId.trim()
        cache.remove(id)
        val dir = groupDir(id) ?: return false
        return try {
            if (dir.exists()) dir.deleteRecursively()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearCache(groupId: String? = null) {
        if (groupId == null) cache.clear() else cache.remove(groupId.trim())
    }

    /** 保证 bot.groups 中的群有目录，移除不在列表中的群目录。 */
    fun syncFolders(groupIds: Collection<String>) {
        val root = groupsDir() ?: return
        val wanted = groupIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val wantedDirs = wanted.map { sanitize(it) }.toSet()
        root.mkdirs()
        for (id in wanted) {
            val dir = groupDir(id) ?: continue
            if (!dir.exists()) {
                dir.mkdirs()
                // 创建空的默认配置文件，明确该群使用全局配置
                val file = dir.resolve("config.yml")
                if (!file.exists()) {
                    save(id, mutableMapOf("group-open-id" to id))
                }
            } else {
                // 确保记录 openId
                val current = load(id)
                if (current["group-open-id"] != id) {
                    current["group-open-id"] = id
                    save(id, current)
                }
            }
        }
        root.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            if (child.name !in wantedDirs) {
                // 也检查目录内 config 的 group-open-id，避免误删手动重命名的
                val meta = child.resolve("config.yml")
                val owner = if (meta.isFile) readSimpleYaml(meta)["group-open-id"]?.toString() else null
                if (owner == null || owner !in wanted) {
                    try { child.deleteRecursively() } catch (_: Exception) {}
                    cache.keys.removeAll { sanitize(it) == child.name || it == owner }
                }
            }
        }
    }

    fun hasOverrides(groupId: String): Boolean {
        val values = load(groupId)
        if (getDotted(values, "command-panel.show-admin-commands") != null) return true
        val commands = getDotted(values, "commands") as? Map<*, *> ?: return false
        return commands.isNotEmpty()
    }

    // ---------------- effective resolution helpers ----------------

    fun effectiveBoolean(groupId: String?, path: String, global: Boolean): Boolean {
        if (groupId.isNullOrBlank()) return global
        val v = getDotted(load(groupId), path) ?: return global
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.toBooleanStrictOrNull() ?: global
            else -> global
        }
    }

    fun effectiveInt(groupId: String?, path: String, global: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int {
        if (groupId.isNullOrBlank()) return global.coerceIn(min, max)
        val v = getDotted(load(groupId), path) ?: return global.coerceIn(min, max)
        val n = when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: global
            else -> global
        }
        return n.coerceIn(min, max)
    }

    fun effectiveCommandSwitches(groupId: String?, global: Map<String, Boolean>): Map<String, Boolean> {
        if (groupId.isNullOrBlank()) return global
        val overrides = load(groupId)
        val commands = getDotted(overrides, "commands") as? Map<*, *> ?: return global
        if (commands.isEmpty()) return global
        val result = global.toMutableMap()
        for ((nameAny, value) in commands) {
            val name = nameAny?.toString() ?: continue
            val enabled = when (value) {
                is Boolean -> value
                is Map<*, *> -> {
                    val e = value["enable"]
                    when (e) {
                        is Boolean -> e
                        is Number -> e.toInt() != 0
                        is String -> e.toBooleanStrictOrNull()
                        else -> null
                    }
                }
                else -> null
            }
            if (enabled != null) result[name] = enabled
        }
        return result
    }

    fun effectiveCommandMenuSwitches(groupId: String?, global: Map<String, Boolean>): Map<String, Boolean> {
        if (groupId.isNullOrBlank()) return global
        val overrides = load(groupId)
        val commands = getDotted(overrides, "commands") as? Map<*, *> ?: return global
        if (commands.isEmpty()) return global
        val result = global.toMutableMap()
        for ((nameAny, value) in commands) {
            val name = nameAny?.toString() ?: continue
            if (value !is Map<*, *>) continue
            val pm = value["pushMenu"] ?: continue
            val push = when (pm) {
                is Boolean -> pm
                is Number -> pm.toInt() != 0
                is String -> pm.toBooleanStrictOrNull()
                else -> null
            }
            if (push != null) result[name] = push
        }
        return result
    }

    fun effectiveCommandMenuPriorities(groupId: String?, global: Map<String, Int>): Map<String, Int> {
        if (groupId.isNullOrBlank()) return global
        val overrides = load(groupId)
        val commands = getDotted(overrides, "commands") as? Map<*, *> ?: return global
        if (commands.isEmpty()) return global
        val result = global.toMutableMap()
        for ((nameAny, value) in commands) {
            val name = nameAny?.toString() ?: continue
            if (value !is Map<*, *>) continue
            val p = value["priority"] ?: continue
            val pr = when (p) {
                is Number -> p.toInt()
                is String -> p.toIntOrNull()
                else -> null
            }
            if (pr != null) result[name] = pr.coerceIn(0, 999)
        }
        return result
    }

    // ---------------- dotted path helpers ----------------

    @Suppress("UNCHECKED_CAST")
    fun getDotted(map: Map<String, Any?>, path: String): Any? {
        if (map.containsKey(path)) return map[path]
        var cur: Any? = map
        for (part in path.split('.')) {
            cur = (cur as? Map<String, Any?>)?.get(part) ?: return null
        }
        return cur
    }

    @Suppress("UNCHECKED_CAST")
    fun setDotted(map: MutableMap<String, Any?>, path: String, value: Any?) {
        val parts = path.split('.')
        var cur: MutableMap<String, Any?> = map
        for (part in parts.dropLast(1)) {
            val next = cur[part]
            cur = if (next is MutableMap<*, *>) next as MutableMap<String, Any?>
            else LinkedHashMap<String, Any?>().also { cur[part] = it }
        }
        cur[parts.last()] = value
    }

    // ---------------- minimal YAML (subset) ----------------

    private fun readSimpleYaml(file: File): MutableMap<String, Any?> {
        // 解析本 store 写出的受限 YAML：缩进嵌套、标量值、空 map/list 用 {} / []
        val lines = file.readLines(Charsets.UTF_8)
        val result = LinkedHashMap<String, Any?>()
        val stack = ArrayDeque<Pair<Int, MutableMap<String, Any?>>>()
        stack.addLast(-1 to result)
        for (raw in lines) {
            if (raw.trim().isEmpty() || raw.trimStart().startsWith('#')) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val content = raw.trim()
            val colon = content.indexOf(':')
            if (colon <= 0) continue
            val key = content.substring(0, colon).trim()
            val rest = content.substring(colon + 1).trim()
            while (stack.size > 1 && indent <= stack.last().first) stack.removeLast()
            val parent = stack.last().second
            if (rest.isEmpty()) {
                val child = LinkedHashMap<String, Any?>()
                parent[key] = child
                stack.addLast(indent to child)
            } else {
                parent[key] = parseScalar(rest)
            }
        }
        return result
    }

    private fun parseScalar(s: String): Any? = when {
        s == "true" -> true
        s == "false" -> false
        s == "null" || s == "~" -> null
        s == "{}" -> LinkedHashMap<String, Any?>()
        s == "[]" -> mutableListOf<Any?>()
        s.matches(Regex("-?\\d+")) -> s.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: s
        (s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\'')) ->
            s.substring(1, s.length - 1)
        else -> s
    }

    private fun writeSimpleYaml(file: File, values: Map<String, Any?>) {
        val sb = StringBuilder()
        sb.appendLine("# HuHoBot 群配置（指令面板与可用指令覆盖）。")
        sb.appendLine("# 删除本文件或通过 WebUI 选择「全局配置」后保存空覆盖，可恢复使用全局设置。")
        writeYamlMap(sb, values, 0)
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeYamlMap(sb: StringBuilder, map: Map<String, Any?>, indent: Int) {
        val pad = "  ".repeat(indent)
        for ((k, v) in map) {
            when (v) {
                is Map<*, *> -> {
                    val m = v as Map<String, Any?>
                    if (m.isEmpty()) sb.appendLine("$pad$k: {}")
                    else {
                        sb.appendLine("$pad$k:")
                        writeYamlMap(sb, m, indent + 1)
                    }
                }
                is List<*> -> {
                    if (v.isEmpty()) sb.appendLine("$pad$k: []")
                    else {
                        sb.appendLine("$pad$k:")
                        v.forEach { sb.appendLine("$pad  - ${formatScalar(it)}") }
                    }
                }
                else -> sb.appendLine("$pad$k: ${formatScalar(v)}")
            }
        }
    }

    private fun formatScalar(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is String -> {
            if (v.isEmpty() || v == "true" || v == "false" || v.matches(Regex("-?\\d+")) ||
                v.contains(':') || v.contains('#') || v.contains('\n') || v.startsWith(' ') || v.endsWith(' '))
                "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else v
        }
        else -> "\"" + v.toString().replace("\"", "\\\"") + "\""
    }
}
