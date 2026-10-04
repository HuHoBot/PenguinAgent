package cn.huohuas001.huhobotPenguin.spigot.manager

/**
 * 配置文件文本级升级器。
 *
 * 为什么不用 Bukkit 的 `config.set(...)` + `saveConfig()`：
 *
 * 1. `config.contains(path)` 会回落到 jar 内置模板的默认值。模板里已经存在的键
 *    （比如后加的 `update-check`、`binding.force-bind`）在用户配置里其实并不存在，
 *    但 `contains` 仍返回 true，于是永远不会被补写进去。
 * 2. `saveConfig()` 由 YAML 序列化器重新输出整个文件，会**丢掉用户文件里的所有注释**，
 *    也会把用户自己的排版打乱。
 *
 * 所以这里直接在原始文本上做增量修改：
 * - 缺失的键按完整路径插入到正确层级，并按 [comments] 补上说明；
 * - 已存在的键若缺少说明，补回注释行（应对 `saveConfig()` 清掉注释的情况）。
 *
 * 只增不删，用户改过的值一律原样保留。
 */
internal object ConfigMigrator {

    data class Result(
        val text: String,
        /** 本次新增的配置键 */
        val addedKeys: List<String>,
        /** 本次补回注释的配置键 */
        val commentedKeys: List<String>
    ) {
        val changed: Boolean get() = addedKeys.isNotEmpty() || commentedKeys.isNotEmpty()
    }

    /**
     * 把 [defaults] 中缺失的键与 [comments] 中缺失的注释补进 [rawText]。
     *
     * @param defaults 键路径 → 默认值（点分路径，如 `binding.force-bind`）
     * @param comments 键路径 → 注释文本；空白注释表示不写注释行
     * @param extraNotes 需要在键上方额外补一行的补充说明（不与已有注释比对）
     */
    fun apply(
        rawText: String,
        defaults: Map<String, Any>,
        comments: Map<String, String>,
        extraNotes: Map<String, String> = emptyMap()
    ): Result {
        val eol = if (rawText.contains("\r\n")) "\r\n" else "\n"
        val hadTrailingNewline = rawText.endsWith("\n")
        val lines = rawText.split(eol).toMutableList()

        val added = mutableListOf<String>()
        val commented = mutableListOf<String>()

        // 先补键：缺失的中间层级会一并建出来
        for ((path, value) in defaults) {
            if (findKeyLine(lines, path) != null) continue
            appendKey(lines, path, value, comments[path])
            added += path
        }

        // 再补注释：saveConfig() 会清掉注释，每次加载都复原一次。
        // 必须重新定位行号——上面的追加操作已经改变了下标。
        for ((path, comment) in comments) {
            if (comment.isBlank()) continue
            val located = locate(lines, path) ?: continue
            if (hasCommentAbove(lines, located.index)) continue
            // 注释与目标键对齐缩进，读起来和模板一致
            lines.add(located.index, " ".repeat(located.indent) + "# $comment")
            commented += path
        }

        // 定点补充：给已有注释的键再加一行说明（如"强制绑定启用时本条配置无效"）。
        // 与上面的补注释不同，这里不受"上方已有注释就跳过"的限制，
        // 但同一行说明已存在时不会重复写入。
        for ((path, note) in extraNotes) {
            if (note.isBlank()) continue
            val located = locate(lines, path) ?: continue
            val line = " ".repeat(located.indent) + "# $note"
            if (lines.contains(line)) continue
            lines.add(located.index, line)
            commented += path
        }

        var text = lines.joinToString(eol)
        if (hadTrailingNewline && !text.endsWith(eol)) text += eol
        return Result(text, added, commented)
    }

    // ---------------------------------------------------------------- 路径定位

    /** 一个键在文件中的位置：行号 + 该行实际使用的缩进宽度。 */
    private data class Located(val index: Int, val indent: Int)

    /**
     * 按完整点分路径查找键。
     *
     * 逐层下降时校验缩进严格递增，避免 `player-events.join.format` 命中
     * `player-events.quit.format` 这种同层同名的情况。
     */
    private fun findKeyLine(lines: List<String>, path: String): Int? = locate(lines, path)?.index

    private fun locate(lines: List<String>, path: String): Located? {
        val segments = path.split('.')
        var current = indexOfTopLevel(lines, segments[0]) ?: return null
        for (level in 1 until segments.size) {
            current = indexOfChild(lines, segments[level], current.index, current.indent) ?: return null
        }
        return current
    }

    /**
     * 顶层键：`key: value` 或 `key:`（段落）。
     *
     * 必须扫描整个文件——顶层键的位置不固定，`config-version` 之类的条目
     * 常常排在 `bot`、`binding` 这些段落前面，只看第一个会误判为不存在。
     */
    private fun indexOfTopLevel(lines: List<String>, key: String): Located? {
        val pattern = Regex("^" + Regex.escape(key) + "\\s*:")
        for (index in lines.indices) {
            val line = lines[index]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (indentOf(line) != 0) continue
            if (pattern.containsMatchIn(line)) return Located(index, 0)
        }
        return null
    }

    /** 段落内的子键：缩进必须大于父级，且不越过父级段落边界。 */
    private fun indexOfChild(lines: List<String>, key: String, parentIndex: Int, parentIndent: Int): Located? {
        val pattern = Regex("^\\s+" + Regex.escape(key) + "\\s*:")
        var index = parentIndex + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }
            if (line.trimStart().startsWith("#")) {
                index++
                continue
            }
            val indent = indentOf(line)
            // 缩进回退或到达顶层，说明父级段落结束
            if (indent <= parentIndent) return null
            if (pattern.containsMatchIn(line)) return Located(index, indent)
            index++
        }
        return null
    }

    private fun indentOf(line: String): Int {
        var count = 0
        for (char in line) {
            if (char == ' ') count++ else if (char == '\t') count += 2 else break
        }
        return count
    }

    /**
     * 该键上方是否已经有注释。
     *
     * 只判断「有没有」，不比对文字：配置文件里的注释可能来自旧版本模板，措辞与
     * [comments] 里的不完全一致，按文本比对会误判成缺注释，从而追加出重复的一行。
     *
     * 向上跳过空行，落在注释行即认为已注释。
     */
    private fun hasCommentAbove(lines: List<String>, index: Int): Boolean {
        var previous = index - 1
        while (previous >= 0 && lines[previous].isBlank()) previous--
        if (previous < 0) return false
        return lines[previous].trimStart().startsWith("#")
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 追加一个配置键。
     *
     * 中间层级缺失时一并创建，并保证每层缩进比父级多 2 格——
     * 直接追加到文件末尾而不缩进会破坏 YAML 结构。
     */
    private fun appendKey(lines: MutableList<String>, path: String, value: Any?, comment: String?) {
        val segments = path.split('.')
        val indentUnit = detectIndentUnit(lines)

        // 从顶层开始逐层下降，缺失的层级就地创建
        var parent: Located? = null
        for (level in segments.indices) {
            val prefix = segments.subList(0, level + 1).joinToString(".")
            val existing = locate(lines, prefix)
            if (existing != null) {
                parent = existing
                continue
            }

            val childIndent = (parent?.indent ?: -indentUnit) + indentUnit
            val keyIndent = " ".repeat(childIndent.coerceAtLeast(0))
            val insertAt = if (parent == null) appendAtEnd(lines) else insertIntoSection(lines, parent)

            if (level == segments.size - 1) {
                // 叶子节点，带值；注释与键同缩进。
                // 插入位置紧跟在已有内容之后，先看它上方是否已有注释，避免追加出重复行。
                val needComment = !comment.isNullOrBlank() && !hasCommentAbove(lines, insertAt)
                if (needComment) lines.add(insertAt, "$keyIndent# $comment")
                lines.add(insertAt + if (needComment) 1 else 0, "$keyIndent${segments[level]}: ${formatValue(value)}")
            } else {
                // 中间层级，作为段落头
                lines.add(insertAt, "$keyIndent${segments[level]}:")
            }
            parent = Located(insertAt, childIndent)
        }
    }

    /** 追加到文件末尾；与已有内容之间留一个空行。 */
    private fun appendAtEnd(lines: MutableList<String>): Int {
        if (lines.isEmpty()) return 0
        if (lines.last().isNotBlank()) lines.add("")
        return lines.size
    }

    /**
     * 插入到 [parent] 所属段落的末尾。
     *
     * 只扫描缩进大于父级的行，遇到回退即结束；
     * 这样新键不会插到下一个段落的前置注释后面。
     */
    private fun insertIntoSection(lines: MutableList<String>, parent: Located): Int {
        var index = parent.index + 1
        var lastContent = -1
        var insertAt = parent.index + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }
            val indent = indentOf(line)
            if (indent <= parent.indent) break
            // 同级或更深的既有内容：记录位置，新键插在它之后
            lastContent = index
            insertAt = index + 1
            index++
        }
        return if (lastContent >= 0) insertAt else parent.index + 1
    }

    /** 推断文件使用的缩进单位（默认 2 空格）。 */
    private fun detectIndentUnit(lines: List<String>): Int {
        val childIndent = lines.asSequence()
            .map { indentOf(it) }
            .filter { it in 1..8 }
            .minOrNull()
        return childIndent ?: 2
    }

    /**
     * 按 YAML 语法渲染值。
     *
     * 含 `: [ ] { } , & * ! | > ' " % @ \`` 等字符的文本必须加引号，
     * 否则 `[游戏] {name} 加入了服务器` 这类值会被解析成列表或映射。
     */
    private fun formatValue(value: Any?): String = when (value) {
        null -> "\"\""
        is Boolean, is Number -> value.toString()
        is Collection<*> -> if (value.isEmpty()) "[]" else value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.toString()
        else -> {
            val text = value.toString()
            if (needsQuote(text)) "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else text
        }
    }

    private fun needsQuote(text: String): Boolean {
        if (text.isEmpty()) return true
        if (text != text.trim()) return true
        return text.any { it in ":#[]{},&*!|>'\"%@`" }
    }
}