package cn.huohuas001.bot.state

import java.io.File

/**
 * 极简 YAML 读写（受限子集：缩进嵌套 map / 标量 / `- item` 列表 / 空容器 {} []）。
 *
 * 供 permission-groups.yml / timed-tasks.yml / 群配置等 common 层配置文件共用，
 * 不依赖任何平台（Bukkit snakeyaml 在 common 层拿不到）。
 */
object SimpleYaml {

    fun read(file: File): MutableMap<String, Any?> {
        val lines = file.readLines(Charsets.UTF_8)
        val result = LinkedHashMap<String, Any?>()
        // 栈：(缩进, 容器)；-1 为根
        val stack = ArrayDeque<Pair<Int, Any?>>()
        stack.addLast(-1 to result)
        // 最近一个列表项的挂载位置：file 行 -> (列表, 缩进)
        var pendingListKey: String? = null
        var pendingListIndent = -1

        for (raw in lines) {
            val trimmedStart = raw.trimStart()
            if (trimmedStart.isEmpty() || trimmedStart.startsWith('#')) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val content = raw.trim()

            if (content.startsWith("- ") || content == "-") {
                // 列表项：挂到最近一次出现的键上
                val key = pendingListKey ?: continue
                val itemText = content.removePrefix("-").trim()
                when (val container = stack.last().second) {
                    is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val map = container as MutableMap<String, Any?>
                        val existing = map[key]
                        val list = (existing as? MutableList<Any?>)
                            ?: mutableListOf<Any?>().also { map[key] = it }
                        if (itemText.isEmpty()) {
                            list.add(LinkedHashMap<String, Any?>())
                            // 进入列表项的嵌套 map
                            stack.addLast(indent to list.last())
                        } else if (itemText.contains(':')) {
                            val child = LinkedHashMap<String, Any?>()
                            parseInlineMapping(itemText, child)
                            list.add(child)
                            stack.addLast(indent to child)
                        } else {
                            list.add(parseScalar(itemText))
                        }
                    }
                    is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = container as MutableList<Any?>
                        if (itemText.isEmpty()) {
                            list.add(LinkedHashMap<String, Any?>())
                            stack.addLast(indent to list.last())
                        } else if (itemText.contains(':')) {
                            val child = LinkedHashMap<String, Any?>()
                            parseInlineMapping(itemText, child)
                            list.add(child)
                            stack.addLast(indent to child)
                        } else {
                            list.add(parseScalar(itemText))
                        }
                    }
                }
                continue
            }

            // 普通键值行：回退到比当前缩进更浅的容器
            val colon = content.indexOf(':')
            if (colon <= 0) continue
            val key = content.substring(0, colon).trim()
            val rest = content.substring(colon + 1).trim()
            while (stack.size > 1 && indent <= stack.last().first) stack.removeLast()
            // 列表容器属于上一个键：若上一个容器是 list 且缩进 <= 它，回退到持有该 list 的 map
            pendingListKey = key
            pendingListIndent = indent
            val parent = stack.last().second
            when (parent) {
                is MutableMap<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = parent as MutableMap<String, Any?>
                    if (rest.isEmpty()) {
                        val child = LinkedHashMap<String, Any?>()
                        map[key] = child
                        stack.addLast(indent to child)
                    } else {
                        map[key] = parseScalar(rest)
                    }
                }
                is MutableList<*> -> {
                    // 异常形态：忽略
                }
            }
        }
        return result
    }

    /** 解析 "- key: value" 形态的单行映射进 child。 */
    private fun parseInlineMapping(text: String, child: LinkedHashMap<String, Any?>) {
        val colon = text.indexOf(':')
        if (colon <= 0) return
        child[text.substring(0, colon).trim()] = parseScalar(text.substring(colon + 1).trim())
    }

    fun write(file: File, values: Map<String, Any?>, header: List<String> = emptyList()) {
        val sb = StringBuilder()
        header.forEach { sb.appendLine("# $it") }
        writeMap(sb, values, 0)
        file.parentFile?.mkdirs()
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeMap(sb: StringBuilder, map: Map<String, Any?>, indent: Int) {
        val pad = "  ".repeat(indent)
        for ((k, v) in map) {
            when (v) {
                is Map<*, *> -> {
                    val m = v as Map<String, Any?>
                    if (m.isEmpty()) sb.appendLine("$pad$k: {}")
                    else {
                        sb.appendLine("$pad$k:")
                        writeMap(sb, m, indent + 1)
                    }
                }
                is List<*> -> {
                    if (v.isEmpty()) sb.appendLine("$pad$k: []")
                    else {
                        sb.appendLine("$pad$k:")
                        for (item in v) {
                            if (item is Map<*, *>) {
                                val first = (item as Map<String, Any?>).entries.firstOrNull()
                                if (first == null) sb.appendLine("$pad  - {}")
                                else {
                                    sb.appendLine("$pad  - ${first.key}: ${formatScalar(first.value)}")
                                    for ((ik, iv) in item) {
                                        if (ik == first.key) continue
                                        writeEntry(sb, "$pad    ", ik.toString(), iv)
                                    }
                                }
                            } else sb.appendLine("$pad  - ${formatScalar(item)}")
                        }
                    }
                }
                else -> writeEntry(sb, pad, k, v)
            }
        }
    }

    private fun writeEntry(sb: StringBuilder, pad: String, key: String, value: Any?) {
        when (value) {
            is Map<*, *> -> {
                val m = value as Map<String, Any?>
                if (m.isEmpty()) sb.appendLine("$pad$key: {}")
                else {
                    sb.appendLine("$pad$key:")
                    writeMap(sb, m, pad.length / 2 + 1)
                }
            }
            is List<*> -> {
                if (value.isEmpty()) sb.appendLine("$pad$key: []")
                else {
                    sb.appendLine("$pad$key:")
                    value.forEach { sb.appendLine("$pad  - ${formatScalar(it)}") }
                }
            }
            else -> sb.appendLine("$pad$key: ${formatScalar(value)}")
        }
    }

    fun parseScalar(s: String): Any? = when {
        s == "true" -> true
        s == "false" -> false
        s == "null" || s == "~" -> null
        s == "{}" -> LinkedHashMap<String, Any?>()
        s == "[]" -> mutableListOf<Any?>()
        s.matches(Regex("-?\\d+")) -> s.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: s
        (s.startsWith('"') && s.endsWith('"') && s.length >= 2) || (s.startsWith('\'') && s.endsWith('\'') && s.length >= 2) ->
            s.substring(1, s.length - 1)
        else -> s
    }

    fun formatScalar(v: Any?): String = when (v) {
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
