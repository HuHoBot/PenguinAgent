package agent.state

import java.io.File

/**
 * 极简 YAML 读写（受限子集：缩进嵌套 map / 标量 / `- item` 列表 / 空容器 {} []）。
 *
 * 供 permission-groups.yml / timed-tasks.yml / 群配置等 common 层配置文件共用，
 * 不依赖任何平台（Bukkit snakeyaml 在 common 层拿不到）。
 */
object SimpleYaml {

    fun read(file: File): MutableMap<String, Any?> {
        require(file.length() <= 2L*1024*1024) { "YAML 超过 2 MiB" }
        return parse(file.readText(Charsets.UTF_8))
    }

    /** The small subset written by this class: block mappings, sequences and inline scalar lists. */
    fun parse(text: String): MutableMap<String, Any?> {
        data class Row(val indent: Int, val value: String, val line: Int)
        val rows = text.removePrefix("\uFEFF").lineSequence().mapIndexedNotNull { i, raw ->
            val content = stripComment(raw).trimEnd()
            if (content.isBlank() || content.trim() in setOf("---", "...")) null else {
                val indent = content.takeWhile { it == ' ' }.length
                require(content.getOrNull(indent) != '\t') { "第 ${i+1} 行缩进请用空格" }
                Row(indent,content.drop(indent),i+1)
            }
        }.toList()
        var cursor=0
        fun isItem(s: String)=s=="-" || s.startsWith("- ")
        lateinit var readBlock: (Int, Int) -> Any
        fun entry(row: Row, into: MutableMap<String, Any?>, depth: Int) {
            val colon = delimiter(row.value, ':')
            require(colon>0) { "第 ${row.line} 行不是键值项" }
            val key = parseScalar(row.value.substring(0,colon).trim()).toString()
            require(!into.containsKey(key)) { "第 ${row.line} 行存在重复键 $key" }
            val rest = row.value.substring(colon+1).trim()
            val next = rows.getOrNull(cursor)
            into[key] = when {
                rest.isNotEmpty() -> parseScalar(rest)
                next!=null && (next.indent>row.indent || (next.indent==row.indent && isItem(next.value))) -> readBlock(next.indent,depth+1)
                else -> null
            }
        }
        readBlock = fun(indent: Int, depth: Int): Any {
            require(depth<=32) { "YAML 嵌套超过 32 层" }
            if (isItem(rows[cursor].value)) {
                val list=ArrayList<Any?>()
                while (cursor<rows.size && rows[cursor].indent==indent && isItem(rows[cursor].value)) {
                    val row=rows[cursor++]
                    val item=row.value.drop(1).trimStart()
                    if (item.isEmpty()) {
                        val next=rows.getOrNull(cursor)
                        list.add(if(next!=null&&next.indent>indent)readBlock(next.indent,depth+1) else null)
                    } else if (delimiter(item, ':')>0) {
                        val map=LinkedHashMap<String, Any?>()
                        val keyIndent=indent+row.value.indexOfFirst { it!='-' && !it.isWhitespace() }
                        entry(Row(keyIndent,item,row.line),map,depth)
                        while(cursor<rows.size && rows[cursor].indent==keyIndent && !isItem(rows[cursor].value)) {
                            entry(rows[cursor++],map,depth)
                        }
                        list.add(map)
                    } else list.add(parseScalar(item))
                }
                return list
            }
            val map=LinkedHashMap<String, Any?>()
            while(cursor<rows.size && rows[cursor].indent==indent && !isItem(rows[cursor].value)) {
                entry(rows[cursor++],map,depth)
            }
            return map
        }
        if(rows.isEmpty())return linkedMapOf()
        val result=readBlock(rows.first().indent,0)
        require(cursor==rows.size && result is MutableMap<*,*>) { "第 ${rows.getOrNull(cursor)?.line ?: 1} 行的 YAML 结构不受支持" }
        @Suppress("UNCHECKED_CAST") return result as MutableMap<String,Any?>
    }

    private fun stripComment(text: String): String {
        val at=delimiter(text,'#')
        return if(at<0)text else text.substring(0,at)
    }

    private fun delimiter(text: String, wanted: Char): Int {
        var quote: Char?=null
        var ignoreNext=false
        for (i in text.indices) {
            val c=text[i]
            if (ignoreNext) { ignoreNext=false;continue }
            if (quote!=null) {
                if (quote=='"' && c=='\\')ignoreNext=true
                else if(c==quote)quote=null
            } else if ((c=='\'' || c=='"') && (i==0 || text[i-1].isWhitespace() || text[i-1] in "[:,")) quote=c
            else if (c==wanted && when(wanted) {
                '#' -> i==0 || text[i-1].isWhitespace()
                ':' -> i==text.lastIndex || text[i+1].isWhitespace()
                else -> true
            }) return i
        }
        return -1
    }

    private fun inlineScalars(text: String): List<Any?> {
        var rest=text.trim()
        val values=ArrayList<Any?>()
        while(rest.isNotEmpty()) {
            val at=delimiter(rest,',')
            if(at<0) { values.add(parseScalar(rest));break }
            values.add(parseScalar(rest.substring(0,at).trim()))
            rest=rest.substring(at+1).trimStart()
        }
        return values
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
        s.startsWith('[') && s.endsWith(']') -> inlineScalars(s.substring(1,s.length-1))
        s.startsWith('"') && s.endsWith('"') && s.length>=2 ->
            runCatching { com.alibaba.fastjson.JSON.parse(s) as String }.getOrElse { s.substring(1,s.length-1) }
        s.startsWith('\'') && s.endsWith('\'') && s.length>=2 -> s.substring(1,s.length-1).replace("''", "'")
        else -> s
    }

    fun formatScalar(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is String -> {
            if (v.isEmpty() || v == "true" || v == "false" || v.matches(Regex("-?\\d+")) ||
                v.contains(':') || v.contains('#') || v.contains('\n') || v.startsWith(' ') || v.endsWith(' '))
                com.alibaba.fastjson.JSON.toJSONString(v)
            else v
        }
        else -> "\"" + v.toString().replace("\"", "\\\"") + "\""
    }
}
