package agent.spigot.chatlog

import agent.spigot.manager.FontManager
import agent.spigot.onlinelist.McTextParser
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.geom.RoundRectangle2D
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO

/** Chat cards deliberately reuse the online-list palette, fonts and rounded geometry. */
object ChatLogRenderer {
    const val WIDTH = 1200
    private const val M = 28
    private const val CARD_H = 62
    private const val GAP = 8
    private val INK = Color(33, 48, 68)
    private val MUTED = Color(96, 113, 134)
    private val ACCENT = Color(38, 150, 168)

    fun render(
        serverName: String,
        entries: List<ChatLogStore.Entry>,
        page: Int,
        totalPages: Int,
        totalEntries: Int,
        playerFilter: String?,
        maxLength: Int
    ): ByteArray {
        val family = FontManager.chooseFont("")
        val title = if (playerFilter.isNullOrBlank()) "服务器聊天记录" else "$playerFilter 的聊天记录"
        val header = 112
        val perMessageWidth = WIDTH - M * 2 - 40
        val lineCounts = entries.map { messageLines(it.message, perMessageWidth) }
        val listH = lineCounts.sumOf { cardHeight(it) + GAP }.coerceAtLeast(cardHeight(1))
        val footer = 74
        val height = header + listH + footer
        val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, Color(243, 249, 253), WIDTH.toFloat(), height.toFloat(), Color(235, 240, 252))
            g.fillRect(0, 0, WIDTH, height)
            g.paint = GradientPaint(0f, 0f, Color(232, 247, 250, 120), WIDTH.toFloat(), 0f, Color(242, 236, 250, 120))
            g.fillRect(0, 0, WIDTH, header)

            drawRich(g, serverName, M, 44, WIDTH - M * 2, 22, family, 1)
            g.font = font(family, Font.BOLD, 26); g.color = ACCENT
            g.drawString(title, M, 84)
            g.font = font(family, Font.PLAIN, 15); g.color = MUTED
            val range = if (totalEntries == 0) "暂无记录" else "第 $page / $totalPages 页 · 共 $totalEntries 条"
            g.drawString(range, WIDTH - M - g.fontMetrics.stringWidth(range), 84)

            var y = header
            if (entries.isEmpty()) {
                card(g, M, y, WIDTH - M * 2, cardHeight(1) + 16)
                g.font = font(family, Font.PLAIN, 20); g.color = MUTED
                g.drawString("最近没有可显示的公共聊天。", M + 22, y + 40)
            }
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
            entries.forEachIndexed { index, entry ->
                val lines = lineCounts[index]
                val height = cardHeight(lines)
                card(g, M, y, WIDTH - M * 2, height)
                drawRich(g, entry.display.ifBlank { entry.name }, M + 20, y + 26, 520, 17, family, 1, boldFirst = true)
                g.font = font(family, Font.PLAIN, 13); g.color = MUTED
                val time = stamp.format(Date(entry.at))
                g.drawString(time, WIDTH - M - 20 - g.fontMetrics.stringWidth(time), y + 24)
                val text = if (entry.message.length > maxLength) entry.message.take(maxLength) + "…" else entry.message
                // 长消息在卡片内换行显示，避免一条占满或过早省略。
                drawRich(g, text, M + 20, y + 50, perMessageWidth, 18, family, lines)
                y += height + GAP
            }

            g.font = font(family, Font.PLAIN, 14); g.color = MUTED
            val footerText = "POWERED BY HU HOBOT · 仅显示当前内存中的最近公共聊天"
            g.drawString(footerText, (WIDTH - g.fontMetrics.stringWidth(footerText)) / 2, height - 28)
        } finally { g.dispose() }
        return ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }
    }

    private fun drawRich(g: Graphics2D, raw: String, x: Int, base: Int, w: Int, size: Int, family: String, maxLines: Int, boldFirst: Boolean = false) {
        val segments = McTextParser.parse(raw.take(1024), INK)
        var px = x
        var line = 0
        var clipped = false
        outer@ for (segment in segments) {
            var index = 0
            while (index < segment.text.length) {
                if (line >= maxLines) { clipped = true; break@outer }
                g.font = font(family, if (segment.bold || boldFirst) Font.BOLD else Font.PLAIN, size)
                val metrics = g.fontMetrics
                var end = index
                var width = 0
                while (end < segment.text.length) {
                    val advance = metrics.charWidth(segment.text[end])
                    if (px + width + advance > x + w) break
                    width += advance; end++
                }
                if (end == index) {
                    if (line + 1 >= maxLines) { clipped = true; break@outer }
                    line++; px = x; continue
                }
                g.color = segment.color
                g.drawString(segment.text.substring(index, end), px, base + line * (size + 6))
                px += width
                index = end
                if (index < segment.text.length) { line++; px = x }
            }
        }
        if (clipped) {
            g.color = MUTED
            g.font = font(family, Font.PLAIN, size)
            g.drawString("…", minOf(px, x + w - 10), base + line * (size + 6))
        }
    }

    /** Conservative estimate: CJK glyphs are about one font-size wide. */
    private fun messageLines(text: String, width: Int): Int {
        if (text.isEmpty()) return 1
        val perLine = (width / 18).coerceAtLeast(12)
        return ((text.length + perLine - 1) / perLine).coerceIn(1, 4)
    }

    private fun cardHeight(lines: Int): Int = 38 + lines * 22

    private fun card(g: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        val shape = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), 16f, 16f)
        g.paint = GradientPaint(x.toFloat(), y.toFloat(), Color(255, 255, 255, 190), x.toFloat(), (y + h).toFloat(), Color(228, 241, 252, 200))
        g.fill(shape)
        g.color = Color(255, 255, 255, 110); g.stroke = BasicStroke(1f); g.draw(shape)
    }

    private fun font(family: String, style: Int, size: Int): Font =
        FontManager.font(family, style, size)
}
