package agent.spigot.manager

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 配置升级只做三件事：安全引用服务器名、补齐缺少的默认项、更新结构版本。
 * 已有值、未知项、废弃项和服主注释都保留；任何解析错误都在写盘前抛出。
 */
object ConfigUpgrade {
    const val VERSION = 19

    private data class Field(val path: String, val key: String, val keyLine: Int, val value: Node, val indent: Int)
    private data class Document(val root: Node, val fields: Map<String, Field>, val mappings: Map<String, MappingNode>)

    private fun document(text: String): Document {
        val root = try { Yaml().compose(StringReader(text)) } catch (error: org.yaml.snakeyaml.error.MarkedYAMLException) {
            val mark = error.problemMark
            throw IllegalArgumentException("YAML 语法错误（第 ${mark?.line?.plus(1) ?: 1} 行，第 ${mark?.column?.plus(1) ?: 1} 列）；请检查缩进、引号和括号")
        } ?: throw IllegalArgumentException("配置文件为空")
        val fields = LinkedHashMap<String, Field>()
        val mappings = LinkedHashMap<String, MappingNode>()
        var visits = 0
        val ancestors = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Node, Boolean>())
        fun visit(node: Node, prefix: String, depth: Int = 0) {
            require(++visits < 10000 && depth < 64 && ancestors.add(node)) { "配置包含循环别名或嵌套过深：$prefix" }
            if (node is MappingNode) {
                mappings[prefix] = node
                val keys = HashSet<String>()
                for (pair in node.value) {
                    val scalar = pair.keyNode as? ScalarNode ?: continue
                    val key = scalar.value
                    require(keys.add(key)) { "配置重复键 $key（第 ${scalar.startMark.line + 1} 行），请合并后再重载" }
                    val path = if (prefix.isEmpty()) key else "$prefix.$key"
                    val line = scalar.startMark.line
                    fields[path] = Field(path, key, line, pair.valueNode,
                        scalar.startMark.column.coerceAtLeast(0))
                    visit(pair.valueNode, path, depth + 1)
                }
            } else if (node is SequenceNode) {
                node.value.forEachIndexed { index, child -> visit(child, "$prefix[$index]", depth + 1) }
            }
            ancestors.remove(node)
        }
        require(root is MappingNode) { "配置文件顶层必须是键值映射，不能是列表或纯文本" }
        visit(root, "")
        return Document(root, fields, mappings)
    }

    /** YAML treats an unquoted leading & as an anchor, not a Minecraft colour code. */
    private fun quoteServerName(text: String): String {
        val rows = text.split('\n').toMutableList()
        // Only the real root key is eligible. Never rewrite prompt bodies or multiline YAML scalars.
        val pattern = Regex("^(serverName[ \t]*:[ \t]*)(.*?)([ \t]+#.*)?$")
        for (i in rows.indices) {
            val match = pattern.matchEntire(rows[i]) ?: continue
            val value = match.groupValues[2].trim()
            if (!value.startsWith("&") || value.length < 2 || value[1].lowercaseChar() !in "0123456789abcdefklmnorx#") continue
            rows[i] = match.groupValues[1] + "'" + value.replace("'", "''") + "'" + match.groupValues[3]
        }
        return rows.joinToString("\n")
    }

    private fun indentAt(line: String): Int = line.takeWhile { it == ' ' }.length

    /** Return the source key plus its adjacent documentation comments and complete value block. */
    private fun sourceBlock(lines: List<String>, field: Field): List<String> {
        val keyLine = field.keyLine.coerceIn(0, lines.lastIndex)
        val keyIndent = indentAt(lines[keyLine])
        var start = keyLine
        var cursor = keyLine - 1
        while (cursor >= 0 && lines[cursor].trimStart().startsWith("#") && indentAt(lines[cursor]) == keyIndent) {
            start = cursor
            cursor--
        }
        var end = keyLine + 1
        while (end < lines.size) {
            val row = lines[end]
            if (row.isNotBlank() && !row.trimStart().startsWith("#")) {
                val rowIndent = indentAt(row)
                if (rowIndent < keyIndent || (rowIndent == keyIndent && !row.trimStart().startsWith("- "))) break
            }
            end++
        }
        while (end > keyLine + 1 && (lines[end - 1].isBlank() ||
            (lines[end - 1].trimStart().startsWith("#") && indentAt(lines[end - 1]) <= keyIndent))) end--
        return lines.subList(start, end)
    }

    private val aliases = mapOf(
        "online-list.custom-lines" to listOf("online-list.display.custom-lines", "online-list.render.custom-lines"),
        "online-list.columns" to listOf("online-list.render.columns"),
        "online-list.address" to listOf("online-list.display.address"),
        "online-list.footer-text" to listOf("online-list.render.footer-text"),
        "update-check" to listOf("update"),
        "serverName" to listOf("server-name", "server.name"),
        "webui-port" to listOf("webui.port", "webui.port.port"),
        "motd.server-ip" to listOf("motd.serverIP"),
        "language" to listOf("lang", "locale"),
        "command-sender" to listOf("sender-mode")
    )

    private fun insertChild(text: String, defaultLines: List<String>, parentPath: String, child: Field, current: Document): String {
        val parent = current.mappings[parentPath] ?: return text
        val rows = text.split('\n').toMutableList()
        val existingParentField = current.fields[parentPath]
        if (existingParentField != null && rows[existingParentField.keyLine].substringAfter(':').trim().startsWith("{")) {
            fun plain(value: Any?): Any? = when (value) {
                is org.bukkit.configuration.ConfigurationSection -> value.getValues(false).mapValues { plain(it.value) }
                is Map<*,*> -> value.entries.associate { it.key.toString() to plain(it.value) }
                is List<*> -> value.map { plain(it) }
                else -> value
            }
            val present = org.bukkit.configuration.file.YamlConfiguration().apply { loadFromString(text) }
            val base = org.bukkit.configuration.file.YamlConfiguration().apply { loadFromString(defaultLines.joinToString("\n")) }
            val merged = LinkedHashMap(present.getConfigurationSection(parentPath)!!.getValues(false).mapValues { plain(it.value) })
            merged[child.key] = plain(base.get(child.path))
            return patch(text, mapOf(parentPath to merged))
        }
        val targetIndent = if (parentPath.isEmpty()) 0 else (current.fields[parentPath]?.indent ?: 0) + 2
        val parentIndent = if (parentPath.isEmpty()) -1 else current.fields[parentPath]?.indent ?: 0
        val parentLine = if (parentPath.isEmpty()) -1 else current.fields[parentPath]?.keyLine ?: -1
        var at = if (parentPath.isEmpty()) rows.size else parentLine + 1
        while (at < rows.size) {
            val row = rows[at]
            if (row.isNotBlank()) {
                val rowIndent = indentAt(row)
                if (row.trimStart().startsWith("#")) {
                    if (rowIndent <= parentIndent) break
                } else if (rowIndent <= parentIndent) break
            }
            at++
        }
        val legacy = aliases[child.path].orEmpty().firstNotNullOfOrNull { current.fields[it] }
        val sourceField = legacy ?: child
        val sourceLines = if (legacy == null) defaultLines else rows
        val block = sourceBlock(sourceLines, sourceField).toMutableList()
        if (legacy != null) {
            val keyIndex = block.indexOfFirst { !it.trimStart().startsWith("#") && it.isNotBlank() }
            if (keyIndex >= 0) {
                val row = block[keyIndex]
                block[keyIndex] = row.takeWhile { it == ' ' } + child.key + row.substring(row.indexOf(':'))
            }
        }
        val shift = targetIndent - sourceField.indent
        val moved = block.map { row ->
            if (row.isBlank()) row else " ".repeat((indentAt(row) + shift).coerceAtLeast(0)) + row.drop(indentAt(row))
        }.toMutableList()
        if (at > 0 && rows.getOrNull(at - 1)?.isNotBlank() == true && moved.firstOrNull()?.trimStart()?.startsWith("#") == true) {
            moved.add(0, "")
        }
        rows.addAll(at, moved)
        return rows.joinToString("\n")
    }

    private fun mergeMissing(raw: String, defaults: String): String {
        var text = raw
        val defaultDoc = document(defaults)
        // Recompose after each insertion so source positions remain correct. The default tree is small.
        repeat(512) {
            val current = document(text)
            fun findMissing(parentPath: String, defaultParent: MappingNode, currentParent: MappingNode): Pair<String, Field>? {
                for (pair in defaultParent.value) {
                    val keyNode = pair.keyNode as? ScalarNode ?: continue
                    val key = keyNode.value
                    val path = if (parentPath.isEmpty()) key else "$parentPath.$key"
                    val existing = currentParent.value.firstOrNull {
                        (it.keyNode as? ScalarNode)?.value == key
                    }?.valueNode
                    val sourceField = defaultDoc.fields[path] ?: continue
                    if (existing == null) return parentPath to sourceField
                    val defaultMapping = pair.valueNode as? MappingNode
                    val currentMapping = existing as? MappingNode
                    if (defaultMapping != null && currentMapping != null) {
                        val nested = findMissing(path, defaultMapping, currentMapping)
                        if (nested != null) return nested
                    }
                    // Explicit scalar/list/section values belong to the administrator. Never replace them.
                }
                return null
            }
            val missing = findMissing("", defaultDoc.root as? MappingNode ?: return text,
                current.root as? MappingNode ?: return text) ?: return text
            text = insertChild(text, defaults.split('\n'), missing.first, missing.second, current)
        }
        throw IllegalStateException("补齐配置默认项超过安全上限")
    }

    private fun attachDefaultComments(raw: String, defaults: String): String {
        val defaultDoc = document(defaults)
        var text = raw
        val candidates = defaultDoc.fields.values
            .filter { it.path.isNotEmpty() && !it.path.contains("[") }
            .sortedByDescending { it.keyLine }
        for (field in candidates) {
            val lines = text.split('\n').toMutableList()
            val current = runCatching { document(text) }.getOrNull() ?: continue
            val existing = current.fields[field.path] ?: continue
            val defaultBlock = sourceBlock(defaults.split('\n'), field)
            val keyIndent = field.indent
            val comments = defaultBlock.takeWhile { it.trimStart().startsWith("#") }
            if (comments.isEmpty()) continue
            var cursor = existing.keyLine - 1
            while (cursor >= 0 && lines[cursor].isBlank()) cursor--
            if (cursor >= 0 && lines[cursor].trimStart().startsWith("#")) continue
            val moved = comments.map { row -> " ".repeat(keyIndent) + row.trimStart() }
            lines.addAll(existing.keyLine, moved)
            text = lines.joinToString("\n")
        }
        return text
    }

    private fun setVersion(text: String): String {
        val rows = text.split('\n').toMutableList()
        val line = rows.indexOfFirst { it.matches(Regex("^\\s*['\"]?config-version['\"]?\\s*:.*$")) }
        if (line < 0) return "config-version: $VERSION\n$text"
        val old = rows[line]
        val match = Regex("^(\\s*['\"]?config-version['\"]?\\s*:\\s*)([^#]*?)(\\s+#.*)?$").matchEntire(old)
        rows[line] = if (match == null) "config-version: $VERSION" else
            match.groupValues[1] + VERSION + match.groupValues[3]
        return rows.joinToString("\n")
    }

    fun transform(raw: String, defaults: String): String {
        // Protect only Minecraft colour prefixes. All other syntax errors fail closed before any write.
        val safeRaw = quoteServerName(raw)
        val current = document(safeRaw)
        val version = (current.fields["config-version"]?.value as? ScalarNode)?.value?.toIntOrNull() ?: 0
        if (version > VERSION) return raw
        var merged = mergeMissing(safeRaw, defaults)
        merged = setVersion(merged)
        // 1.24: the old default omitted the dead player's name; custom formats stay untouched.
        if (current.fields["player-events.death.format"]?.let { (it.value as? ScalarNode)?.value } == "[游戏] {message}") {
            merged = patch(merged, mapOf("player-events.death.format" to "[游戏] {name} {message}"))
        }
        merged = attachDefaultComments(merged, defaults)
        document(merged) // Validate the final document too.
        return merged
    }

    /** Patch only requested value spans; all other text and comments remain byte-for-byte intact. */
    fun patch(raw: String, changes: Map<String, Any?>): String {
        var text = quoteServerName(raw)
        for ((path, value) in changes) {
            require(path.isNotBlank() && path.split('.').all { it.isNotBlank() }) { "配置路径无效：$path" }
            val doc = document(text)
            val field = doc.fields[path]
            val json = com.alibaba.fastjson.JSON.toJSONString(value)
            if (field != null) {
                require(field.value.startMark.line >= field.keyLine) { "不能直接覆盖 YAML 别名引用：$path" }
                // Block sequences/maps/scalars (including zero-indent lists emitted by other tools)
                // cannot be replaced by an inline value at their first character.
                if (field.value.endMark.line > field.keyLine) {
                    val rows = text.split('\n').toMutableList()
                    val keyLine = field.keyLine.coerceIn(0, rows.lastIndex)
                    val endLine = field.value.endMark.line.coerceIn(keyLine, rows.lastIndex)
                    val indent = rows[keyLine].takeWhile { it == ' ' }.length
                    rows[keyLine] = " ".repeat(indent) + field.key + ": " + json
                    for (line in endLine downTo keyLine + 1) rows.removeAt(line)
                    text = rows.joinToString("\n")
                    document(text)
                    continue
                }
                val start = text.offsetByCodePoints(0, field.value.startMark.index)
                val end = text.offsetByCodePoints(0, field.value.endMark.index)
                // A block scalar/map can consume the newline before the next key. Preserve that boundary.
                val suffix = if (end > start && text.substring(start, end).endsWith("\n")) "\n" else ""
                text = text.substring(0, start) + json + suffix + text.substring(end)
            } else {
                val parts = path.split('.')
                var parentPath = parts.dropLast(1).joinToString(".")
                while (parentPath.isNotEmpty() && parentPath !in doc.mappings) {
                    require(parentPath !in doc.fields) { "配置父项不是映射：$parentPath" }
                    parentPath = parentPath.substringBeforeLast('.', "")
                }
                val consumed = if (parentPath.isEmpty()) 0 else parentPath.split('.').size
                val remainder = parts.drop(consumed)
                var nested: Any? = value
                for (key in remainder.drop(1).asReversed()) nested = linkedMapOf(key to nested)
                val parent = doc.mappings.getValue(parentPath)
                val rows = text.split('\n').toMutableList()
                val parentField = doc.fields[parentPath]
                require(parentPath.isEmpty() || rows[parentField!!.keyLine].substringAfter(':').trim().isEmpty()) {
                    "请先将内联配置段改为多行 YAML 后再新增子项：$parentPath"
                }
                val indent = if (parentPath.isEmpty()) 0 else parentField!!.indent + 2
                var at = if (parentPath.isEmpty()) rows.size else parentField!!.keyLine + 1
                while (at < rows.size) {
                    if (rows[at].isNotBlank() && indentAt(rows[at]) < indent) break
                    at++
                }
                rows.add(at, " ".repeat(indent) + com.alibaba.fastjson.JSON.toJSONString(remainder.first()) + ": " +
                    com.alibaba.fastjson.JSON.toJSONString(nested))
                text = rows.joinToString("\n")
            }
            document(text)
        }
        return text
    }

    @Synchronized fun apply(file: File, defaults: String) {
        val raw = file.readText(Charsets.UTF_8)
        writeIfChanged(file, raw, transform(raw, defaults))
    }

    @Synchronized fun update(file: File, defaults: String, changes: Map<String, Any?>) {
        val raw = file.readText(Charsets.UTF_8)
        writeIfChanged(file, raw, transform(patch(raw, changes), defaults))
    }

    private fun writeIfChanged(file: File, raw: String, next: String) {
        if (raw == next) return
        val backupDir = File(file.parentFile, "data/backups").apply { mkdirs() }
        val backup = Files.createTempFile(backupDir.toPath(), "config-", ".yml")
        Files.write(backup, raw.toByteArray(Charsets.UTF_8))
        val tmp = Files.createTempFile(file.parentFile.toPath(), "config-", ".tmp")
        try {
            Files.write(tmp, next.toByteArray(Charsets.UTF_8))
            check(file.readText(Charsets.UTF_8) == raw) { "配置在后台准备期间被修改，已保留最新原文件，请重试" }
            try { Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tmp) }
    }
}
