package agent.spigot.onlinelist

import java.awt.Color
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage

/** 使用真正的 Adventure MiniMessage；保留嵌套、渐变、HEX、斜体、下划线及换行。 */
data class TextSegment(val text: String, val color: Color, val bold: Boolean = false,
    val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false)

object McTextParser {
    private val mini = MiniMessage.miniMessage()
    private val colours = listOf("000000","0000aa","00aa00","00aaaa","aa0000","aa00aa","ffaa00","aaaaaa",
        "555555","5555ff","55ff55","55ffff","ff5555","ff55ff","ffff55","ffffff")
    /** Legacy -> MiniMessage，不把普通 & 或未知转义吞掉。 */
    fun normalise(input: String): String {
        val out = StringBuilder(); var i = 0
        while (i < input.length) {
            val ch = input[i]
            if ((ch == '§' || ch == '&') && i + 1 < input.length) {
                val c = input[i + 1].lowercaseChar()
                var hex: String? = null; var consumed = 0
                if (c == '#' && i + 8 <= input.length) { hex = input.substring(i+2, i+8); consumed = 8 }
                if (c == 'x' && i + 14 <= input.length) {
                    val seq = input.substring(i+2, i+14)
                    if ((0..5).all { seq[it*2] == '§' || seq[it*2] == '&' }) {
                        hex = (0..5).map { seq[it*2+1] }.joinToString(""); consumed = 14
                    }
                }
                if (hex != null && hex.matches(Regex("[0-9a-fA-F]{6}"))) {
                    out.append("<reset><color:#").append(hex).append('>'); i += consumed; continue
                }
                val n = "0123456789abcdef".indexOf(c)
                val tag = if (n >= 0) "<reset><color:#${colours[n]}>" else when(c) {
                    'l' -> "<bold>"; 'm' -> "<strikethrough>"; 'n' -> "<underlined>"
                    'o' -> "<italic>"; 'k' -> ""; 'r' -> "<reset>"; else -> null
                }
                if (tag != null) { out.append(tag); i += 2; continue }
            }
            out.append(ch); i++
        }
        return out.toString()
    }
    fun parse(input: String, defaultColor: Color = Color(35, 47, 65)): List<TextSegment> {
        val component = runCatching { mini.deserialize(normalise(input)) }.getOrElse { Component.text(input) }
        return flatten(component, defaultColor)
    }
    fun flatten(component: Component, defaultColor: Color = Color(35,47,65)): List<TextSegment> {
        val out = mutableListOf<TextSegment>()
        fun walk(c: Component, inherited: Style) {
            val st = c.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            val text = if (c is TextComponent) c.content() else ""
            if (text.isNotEmpty()) out += TextSegment(text, st.color()?.let { Color(it.value()) } ?: defaultColor,
                st.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE,
                st.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE,
                st.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE,
                st.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE)
            c.children().forEach { walk(it, st) }
        }
        walk(component, Style.empty()); return out
    }
    fun plainText(input: String): String = parse(input).joinToString("") { it.text }
}
