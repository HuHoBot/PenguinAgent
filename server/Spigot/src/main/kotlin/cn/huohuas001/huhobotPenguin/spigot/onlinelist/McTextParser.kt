package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import java.awt.Color

/** 解析后的一段文本（颜色已解析为具体颜色值）。 */
class TextSegment(
    val text: String,
    val color: Color,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false
)

/**
 * Minecraft 富文本解析器：同时支持
 * - 传统 §/& 颜色代码（0-9a-f、l/m/n/o/k/r）
 * - 十六进制颜色：§x§R§R§G§G§B§B、&#RRGGBB、§#RRGGBB
 * - MiniMessage 常见子集：<red>/<color:red>/<#ff8800>/<color:#ff8800>、
 *   <bold>/<b>、<italic>/<i>、<underlined>/<u>、<strikethrough>/<st>、<reset>、
 *   <gradient:#a:#b>…</gradient>（逐字符插值）
 * 未知标签忽略原文，绝不抛异常。
 */
object McTextParser {

    private val NAMED_COLORS = mapOf(
        "black" to 0x000000, "dark_blue" to 0x0000AA, "dark_green" to 0x00AA00,
        "dark_aqua" to 0x00AAAA, "dark_red" to 0xAA0000, "dark_purple" to 0xAA00AA,
        "gold" to 0xFFAA00, "gray" to 0xAAAAAA, "grey" to 0xAAAAAA,
        "dark_gray" to 0x555555, "dark_grey" to 0x555555, "blue" to 0x5555FF,
        "green" to 0x55FF55, "aqua" to 0x55FFFF, "red" to 0xFF5555,
        "light_purple" to 0xFF55FF, "yellow" to 0xFFFF55, "white" to 0xFFFFFF
    )

    private fun legacyColor(code: Char): Int? = when (code.lowercaseChar()) {
        '0' -> 0x000000; '1' -> 0x0000AA; '2' -> 0x00AA00; '3' -> 0x00AAAA
        '4' -> 0xAA0000; '5' -> 0xAA00AA; '6' -> 0xFFAA00; '7' -> 0xAAAAAA
        '8' -> 0x555555; '9' -> 0x5555FF; 'a' -> 0x55FF55; 'b' -> 0x55FFFF
        'c' -> 0xFF5555; 'd' -> 0xFF55FF; 'e' -> 0xFFFF55; 'f' -> 0xFFFFFF
        else -> null
    }

    /** 解析整段文本为若干段（默认颜色由调用方传入）。 */
    fun parse(input: String, defaultColor: Color = Color.WHITE): List<TextSegment> {
        val out = mutableListOf<TextSegment>()
        val buffer = StringBuilder()

        var color = defaultColor
        var bold = false
        var italic = false
        var underline = false
        var strike = false

        // gradient 状态
        var gradient: Pair<Color, Color>? = null
        var gradientDone = -1  // 渐变已消费的字符数（用于插值）
        var gradientTotal = 0

        fun flush() {
            if (buffer.isEmpty()) return
            if (gradient != null) {
                // 渐变：逐字符插值输出
                val (from, to) = gradient!!
                for (ch in buffer.toString()) {
                    val ratio = if (gradientTotal <= 1) 0.0
                    else (gradientDone.toDouble() / (gradientTotal - 1)).coerceIn(0.0, 1.0)
                    val c = Color(
                        (from.red + (to.red - from.red) * ratio).toInt(),
                        (from.green + (to.green - from.green) * ratio).toInt(),
                        (from.blue + (to.blue - from.blue) * ratio).toInt()
                    )
                    out.add(TextSegment(ch.toString(), c, bold, italic, underline, strike))
                    gradientDone++
                }
            } else {
                out.add(TextSegment(buffer.toString(), color, bold, italic, underline, strike))
            }
            buffer.setLength(0)
        }

        var i = 0
        while (i < input.length) {
            val ch = input[i]
            // ── 传统 §/& 代码 ──
            if ((ch == '§' || ch == '&') && i + 1 < input.length) {
                val next = input[i + 1]
                // §x§R§R§G§G§B§B 标准 hex 序列
                if ((next == 'x' || next == 'X') && i + 13 < input.length) {
                    val hexChars = StringBuilder()
                    var j = i + 2
                    var ok = true
                    repeat(6) {
                        if (j + 1 >= input.length || (input[j] != '§' && input[j] != '&')) { ok = false; return@repeat }
                        hexChars.append(input[j + 1])
                        j += 2
                    }
                    if (ok && hexChars.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                        flush()
                        color = Color(hexChars.toString().toInt(16))
                        gradient = null
                        i = j
                        continue
                    }
                }
                // &#RRGGBB / §#RRGGBB
                if (next == '#') {
                    if (i + 8 <= input.length) {
                        val hex = input.substring(i + 2, i + 8)
                        if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                            flush()
                            color = Color(hex.toInt(16))
                            gradient = null
                            i += 8
                            continue
                        }
                    }
                }
                // 单字符代码
                val named = legacyColor(next)
                if (named != null) {
                    flush()
                    color = Color(named)
                    gradient = null
                    i += 2
                    continue
                }
                when (next.lowercaseChar()) {
                    'l' -> { flush(); bold = true }
                    'm' -> { flush(); strike = true }
                    'n' -> { flush(); underline = true }
                    'o' -> { flush(); italic = true }
                    'r' -> { flush(); color = defaultColor; bold = false; italic = false; underline = false; strike = false; gradient = null }
                    else -> Unit
                }
                i += 2
                continue
            }
            // ── MiniMessage 标签 ──
            if (ch == '<') {
                val close = input.indexOf('>', i)
                if (close > i) {
                    val tag = input.substring(i + 1, close).trim()
                    flush()
                    when {
                        tag.equals("bold", true) || tag.equals("b", true) -> bold = true
                        tag.equals("italic", true) || tag.equals("i", true) || tag.equals("em", true) -> italic = true
                        tag.equals("underlined", true) || tag.equals("u", true) -> underline = true
                        tag.equals("strikethrough", true) || tag.equals("st", true) -> strike = true
                        tag.equals("reset", true) -> {
                            color = defaultColor; bold = false; italic = false; underline = false; strike = false; gradient = null
                        }
                        tag.startsWith("/") -> {
                            // 闭合标签：只把对应的样式关掉
                            when (tag.removePrefix("/").lowercase()) {
                                "bold", "b" -> bold = false
                                "italic", "i", "em" -> italic = false
                                "underlined", "u" -> underline = false
                                "strikethrough", "st" -> strike = false
                                "gradient" -> gradient = null
                                else -> Unit
                            }
                        }
                        tag.startsWith("gradient:", true) -> {
                            val parts = tag.removePrefix("gradient:").removePrefix("gradient:").split(":")
                            if (parts.size >= 2) {
                                val from = parseColorToken(parts[0])
                                val to = parseColorToken(parts[1])
                                if (from != null && to != null) {
                                    gradient = from to to
                                    gradientDone = 0
                                    // 总字符数在 tag 解析时未知：用剩余文本估计
                                    gradientTotal = input.indexOf("</gradient>", close).let { end ->
                                        if (end < 0) input.length - close - 1 else end - close - 1
                                    }
                                }
                            }
                        }
                        tag.startsWith("color:", true) -> parseColorToken(tag.substringAfter(':').trim())?.let {
                            color = it; gradient = null
                        }
                        else -> parseColorToken(tag)?.let { color = it; gradient = null }
                    }
                    i = close + 1
                    continue
                }
            }
            buffer.append(ch)
            i++
        }
        flush()
        return out
    }

    /** <color:#RRGGBB>、<#RRGGBB>、<red> 等 token → 颜色。 */
    private fun parseColorToken(token: String): Color? {
        val t = token.trim().removePrefix("#")
        if (t.length == 6 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return runCatching { Color(t.toInt(16)) }.getOrNull()
        }
        val named = NAMED_COLORS[token.trim().lowercase()]
        return named?.let { Color(it) }
    }

    /** 去掉所有格式标签后的纯文本（用于截断测量等）。 */
    fun plainText(input: String): String =
        parse(input).joinToString("") { it.text }
}
