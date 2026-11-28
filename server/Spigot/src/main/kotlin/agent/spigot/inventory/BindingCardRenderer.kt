package agent.spigot.inventory

import org.bukkit.Bukkit
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 「我的绑定」个人卡片：头像 + 绑定角色列表 + 在线状态。
 * 亮色风格与查在线一致；所有绘制均防御式，任何一项失败不影响整卡输出。
 */
object BindingCardRenderer {
    private const val W = 760

    fun render(plugin: agent.spigot.HuHoBotSpigot, playerNames: List<String>, title: String): ByteArray? {
        if (playerNames.isEmpty()) return null
        val height = 150 + playerNames.size.coerceAtMost(10) * 74 + 40
        val image = BufferedImage(W, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, Color(214,238,248), W.toFloat(), height.toFloat(), Color(233,228,246))
            g.fillRect(0, 0, W, height)
            g.color = Color(35,47,65)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
            g.drawString(title, 36, 58)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
            g.color = Color(88,105,126)
            g.drawString("共 ${playerNames.size} 个绑定角色", 38, 86)
            var y = 120
            val ink = Color(35,47,65)
            for (name in playerNames.take(10)) {
                val card = RoundRectangle2D.Float(28f, y.toFloat(), (W-56).toFloat(), 62f, 16f, 16f)
                g.paint = GradientPaint(28f, y.toFloat(), Color(255,255,255,190), 28f, (y+62).toFloat(), Color(226,240,252,170))
                g.fill(card)
                g.color = Color(179,199,225)
                g.stroke = BasicStroke(1f)
                g.draw(card)
                // 头像：真实皮肤优先，失败用纯色圆底
                val head = runCatching { roundHead(SkinFetcher.fetchSkin(playerName = name)) }.getOrNull()
                if (head != null) g.drawImage(head, 44, y + 11, null)
                else {
                    g.color = Color(125,95,180)
                    g.fill(Ellipse2D.Float(44f, (y+11).toFloat(), 40f, 40f))
                }
                g.color = ink
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
                g.drawString(name, 100, y + 30)
                val online = Bukkit.getPlayerExact(name)
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
                if (online != null) {
                    g.color = Color(13,123,77)
                    g.drawString("在线 · ${online.ping}ms · ${online.world?.name.orEmpty()}", 100, y + 52)
                } else {
                    g.color = Color(88,105,126)
                    g.drawString("离线", 100, y + 52)
                }
                y += 74
            }
            g.color = Color(88,105,126)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            g.drawString("Powered by HuHoBot", 36, height - 16)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }

    private fun roundHead(skin: PlayerSkin?): java.awt.image.BufferedImage? {
        val src = skin?.image ?: return null
        // 皮肤 64x64：头在 (8,8,8,8)，放大到 40px 圆形
        val face = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val g2 = face.createGraphics()
        g2.drawImage(src, 0, 0, 8, 8, 8, 8, 16, 16, null)
        g2.dispose()
        val out = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.composite = AlphaComposite.SrcIn
            g.color = Color.WHITE
            g.fill(Ellipse2D.Float(0f, 0f, 40f, 40f))
            g.composite = AlphaComposite.SrcOver
            g.drawImage(face, 0, 0, 40, 40, null)
        } finally { g.dispose() }
        return out
    }
}
