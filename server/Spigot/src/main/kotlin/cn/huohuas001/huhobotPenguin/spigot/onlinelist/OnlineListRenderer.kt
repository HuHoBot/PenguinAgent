package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * 使用 1200px 雾蓝玻璃三段底图渲染可向下无限扩展的在线列表。
 */
class OnlineListRenderer(
    private val avatarCache: AvatarCache,
    private val columns: Int,
    private val configuredFont: String,
    private val footerText: String
) {
    companion object {
        const val WIDTH = 1200
        const val HEADER_HEIGHT = 384
        const val BODY_TILE_HEIGHT = 640
        const val FOOTER_HEIGHT = 256
        private const val GRID_TOP = 520

        /**
         * footer 顶部参与接缝颜色校正的行数。
         *
         * 底图切片可能来自不同来源，顶部几十行内常有整体亮度/色温偏差；
         * 校正量随行号递减到零，不影响 footer 自身的设计渐变。
         */
        private const val SEAM_BLEND_HEIGHT = 64

        /** 计算接缝色差时的水平平滑半径（像素），只保留低频差异。 */
        private const val SEAM_BLUR_RADIUS = 48

        private val TEXT_PRIMARY = Color(25, 69, 96)
        private val TEXT_SECONDARY = Color(33, 88, 116, 190)
        private val CARD_FILL = Color(232, 248, 255, 128)
        private val CARD_BORDER = Color(220, 252, 255, 188)
        private val CARD_SHADOW = Color(18, 65, 98, 35)
        private val CYAN = Color(57, 218, 236)
        private val ADMIN_TEXT = Color(15, 139, 166, 220)

        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"))
    }

    private val header: BufferedImage = readResource("/online/huhobot-glass-header-1200x384.png")
    private val bodyTile: BufferedImage = readResource("/online/huhobot-glass-body-tile-1200x640.png")
    private val footer: BufferedImage = readResource("/online/huhobot-glass-footer-1200x256.png")

    /**
     * 缝合后的 footer：顶部若干行做过低频颜色校正，用来消除与 body 的接缝。
     *
     * 不同来源的底图切片 lighting 常有细微偏差（例如用户自行替换 footer 后，
     * footer 顶边整体比 body 底部亮十几级），直接拼接会出现一条横贯画面的硬边。
     * 校正只针对低频色差，不会把角色等细节涂抹到背景上。
     */
    private val seamCorrectedFooter: BufferedImage by lazy { buildSeamCorrectedFooter() }

    private val safeColumns = columns.coerceIn(1, 3)
    private val compact = safeColumns == 3
    private val sideMargin = if (compact) 92 else 104
    private val cardGapX = if (compact) 18 else 28
    private val cardGapY = if (compact) 16 else 22
    private val cardHeight = if (compact) 96 else 118
    private val avatarSize = if (compact) 60 else 76
    private val contentBottomSafe = if (compact) 60 else 86
    private val fontFamily = chooseFont(configuredFont)
    private val safeFooterText = footerText.trim()

    init {
        validateSlice(header, WIDTH, HEADER_HEIGHT, "header")
        validateSlice(bodyTile, WIDTH, BODY_TILE_HEIGHT, "body tile")
        validateSlice(footer, WIDTH, FOOTER_HEIGHT, "footer")
    }

    /** 渲染一页在线列表，返回 PNG 字节数组。 */
    @Throws(IOException::class)
    fun render(snapshot: ServerSnapshot): ByteArray {
        val avatars = avatarCache.loadAll(snapshot.players)
        val image = renderImage(snapshot, avatars)
        val output = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", output)) throw IOException("PNG writer is unavailable")
        return output.toByteArray()
    }

    private fun renderImage(
        snapshot: ServerSnapshot,
        avatars: Map<String, BufferedImage>
    ): BufferedImage {
        val displayedPlayers = snapshot.players.size
        val rows = if (displayedPlayers == 0) 0 else (displayedPlayers + safeColumns - 1) / safeColumns
        val gridHeight = if (rows == 0) 0 else rows * cardHeight + (rows - 1) * cardGapY
        val contentBottom = if (rows == 0) GRID_TOP + 210 else GRID_TOP + gridHeight
        var bodyTiles = Math.max(1, divideRoundUp(contentBottom + contentBottomSafe - HEADER_HEIGHT, BODY_TILE_HEIGHT))
        if (snapshot.totalPages > 1) bodyTiles = Math.max(2, bodyTiles)
        val footerTop = HEADER_HEIGHT + bodyTiles * BODY_TILE_HEIGHT
        val height = footerTop + FOOTER_HEIGHT

        val result = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            configure(graphics)
            drawBackground(graphics, bodyTiles, footerTop)
            drawHeader(graphics, snapshot)
            if (rows == 0) {
                drawEmptyState(graphics)
            } else {
                drawPlayers(graphics, snapshot, avatars)
            }
            drawFooter(graphics, footerTop, snapshot)
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun drawBackground(graphics: Graphics2D, bodyTiles: Int, footerTop: Int) {
        graphics.drawImage(header, 0, 0, null)
        for (i in 0 until bodyTiles) {
            graphics.drawImage(bodyTile, 0, HEADER_HEIGHT + i * BODY_TILE_HEIGHT, null)
        }
        // 使用做过接缝校正的 footer，避免与 body 之间出现硬边
        graphics.drawImage(seamCorrectedFooter, 0, footerTop, null)
    }

    private fun buildSeamCorrectedFooter(): BufferedImage {
        val result = BufferedImage(WIDTH, FOOTER_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        graphics.drawImage(footer, 0, 0, null)
        graphics.dispose()

        val band = SEAM_BLEND_HEIGHT.coerceIn(0, FOOTER_HEIGHT)
        if (band == 0) return result

        val bodyRow = IntArray(WIDTH)
        val footerRow = IntArray(WIDTH)
        val dr = IntArray(WIDTH)
        val dg = IntArray(WIDTH)
        val db = IntArray(WIDTH)
        val sr = IntArray(WIDTH)
        val sg = IntArray(WIDTH)
        val sb = IntArray(WIDTH)

        for (y in 0 until band) {
            // body 平铺到接缝下方时，接缝处第 y 行会重复 body 图块的第 y 行
            bodyTile.getRGB(0, y, WIDTH, 1, bodyRow, 0, WIDTH)
            footer.getRGB(0, y, WIDTH, 1, footerRow, 0, WIDTH)
            for (x in 0 until WIDTH) {
                dr[x] = redOf(bodyRow[x]) - redOf(footerRow[x])
                dg[x] = greenOf(bodyRow[x]) - greenOf(footerRow[x])
                db[x] = blueOf(bodyRow[x]) - blueOf(footerRow[x])
            }
            boxMean(dr, sr, SEAM_BLUR_RADIUS)
            boxMean(dg, sg, SEAM_BLUR_RADIUS)
            boxMean(db, sb, SEAM_BLUR_RADIUS)

            // 接缝处（y=0）校正量最大，向下迅速归零
            val remaining = (band - y).toFloat() / band
            val weight = remaining * remaining
            for (x in 0 until WIDTH) {
                val color = footerRow[x]
                result.setRGB(
                    x,
                    y,
                    ((color ushr 24) shl 24) or
                        (clampChannel(redOf(color) + sr[x] * weight) shl 16) or
                        (clampChannel(greenOf(color) + sg[x] * weight) shl 8) or
                        clampChannel(blueOf(color) + sb[x] * weight)
                )
            }
        }
        return result
    }

    /** 半径 [radius] 的滑窗均值，边缘以最近像素填充。 */
    private fun boxMean(source: IntArray, output: IntArray, radius: Int) {
        val size = source.size
        val window = radius * 2 + 1
        var sum = 0L
        for (x in -radius..radius) sum += source[x.coerceIn(0, size - 1)]
        for (x in 0 until size) {
            output[x] = (sum / window).toInt()
            sum += source[(x + radius + 1).coerceAtMost(size - 1)]
            sum -= source[(x - radius).coerceAtLeast(0)]
        }
    }

    private fun redOf(color: Int): Int = (color shr 16) and 0xFF

    private fun greenOf(color: Int): Int = (color shr 8) and 0xFF

    private fun blueOf(color: Int): Int = color and 0xFF

    private fun clampChannel(value: Float): Int = value.toInt().coerceIn(0, 255)

    private fun drawHeader(graphics: Graphics2D, snapshot: ServerSnapshot) {
        graphics.font = font(Font.BOLD, 58)
        graphics.color = TEXT_PRIMARY
        val title = "在线列表"
        graphics.drawString(title, (WIDTH - graphics.fontMetrics.stringWidth(title)) / 2, 280)

        graphics.font = font(Font.BOLD, 32)
        graphics.color = TEXT_PRIMARY
        graphics.drawString(snapshot.serverName, sideMargin, 446)

        val count = "${snapshot.totalOnlinePlayers} / ${snapshot.maxPlayers}"
        graphics.font = font(Font.BOLD, 30)
        drawRight(graphics, count, WIDTH - sideMargin, 446, TEXT_PRIMARY)

        graphics.font = font(Font.PLAIN, 22)
        graphics.color = TEXT_SECONDARY
        graphics.drawString("当前在线玩家", sideMargin, 486)
        val timeStr = TIME.format(Instant.ofEpochMilli(snapshot.capturedAt))
        drawRight(graphics, "更新 $timeStr", WIDTH - sideMargin, 486, TEXT_SECONDARY)
    }

    private fun drawPlayers(
        graphics: Graphics2D,
        snapshot: ServerSnapshot,
        avatars: Map<String, BufferedImage>
    ) {
        val usableWidth = WIDTH - sideMargin * 2
        val cardWidth = (usableWidth - (safeColumns - 1) * cardGapX) / safeColumns
        for ((index, player) in snapshot.players.withIndex()) {
            val column = index % safeColumns
            val row = index / safeColumns
            val x = sideMargin + column * (cardWidth + cardGapX)
            val y = GRID_TOP + row * (cardHeight + cardGapY)
            drawPlayerCard(graphics, x, y, cardWidth, player, avatars[player.uuid])
        }
    }

    private fun drawPlayerCard(
        graphics: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        player: OnlineListPlayer,
        avatar: BufferedImage?
    ) {
        val cardArc = if (safeColumns == 3) 24 else 32
        val card = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), cardHeight.toFloat(), cardArc.toFloat(), cardArc.toFloat())
        graphics.color = CARD_SHADOW
        val shadowOffset = if (safeColumns == 3) 4 else 6
        graphics.fill(RoundRectangle2D.Float((x + 2).toFloat(), (y + shadowOffset).toFloat(), width.toFloat(), cardHeight.toFloat(), cardArc.toFloat(), cardArc.toFloat()))
        graphics.color = CARD_FILL
        graphics.fill(card)
        graphics.stroke = BasicStroke(1.7f)
        graphics.color = CARD_BORDER
        graphics.draw(card)

        val avatarX = x + (if (safeColumns == 3) 16 else 21)
        val avatarY = y + (cardHeight - avatarSize) / 2
        val avatarArc = if (safeColumns == 3) 16 else 20
        val avatarShape = RoundRectangle2D.Float(avatarX.toFloat(), avatarY.toFloat(), avatarSize.toFloat(), avatarSize.toFloat(), avatarArc.toFloat(), avatarArc.toFloat())
        val previousClip = graphics.clip
        graphics.clip(avatarShape)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        if (avatar == null) {
            drawFallbackAvatar(graphics, avatarX, avatarY, player)
        } else {
            graphics.drawImage(avatar, avatarX, avatarY, avatarSize, avatarSize, null)
        }
        graphics.clip = previousClip
        graphics.color = Color(219, 250, 255, 210)
        graphics.stroke = BasicStroke(2f)
        graphics.draw(avatarShape)

        graphics.color = CYAN
        val dotSize = if (safeColumns == 3) 10 else 13
        graphics.fillOval(avatarX + avatarSize - dotSize, avatarY + avatarSize - dotSize, dotSize, dotSize)

        val textX = avatarX + avatarSize + (if (safeColumns == 3) 13 else 19)
        graphics.font = font(Font.BOLD, if (safeColumns == 3) 22 else 28)
        graphics.color = TEXT_PRIMARY
        val name = truncate(graphics, player.name, x + width - textX - 18)
        graphics.drawString(name, textX, y + (if (safeColumns == 3) 50 else 69))

        graphics.font = font(Font.PLAIN, if (safeColumns == 3) 15 else 18)
        graphics.color = if (player.administrator) ADMIN_TEXT else TEXT_SECONDARY
        val label = if (player.administrator) "ADMIN" else "ONLINE"
        graphics.drawString(label, textX, y + (if (safeColumns == 3) 73 else 94))
    }

    private fun drawFallbackAvatar(graphics: Graphics2D, x: Int, y: Int, player: OnlineListPlayer) {
        val hue = (player.uuid.hashCode().and(0x7fffffff)) % 360
        val base = Color.getHSBColor(hue / 360f, 0.22f, 0.86f)
        graphics.color = base
        graphics.fillRect(x, y, avatarSize, avatarSize)
        graphics.color = Color(255, 255, 255, 210)
        graphics.font = font(Font.BOLD, if (safeColumns == 3) 27 else 34)
        val initial = if (player.name.isEmpty()) "?" else player.name.substring(0, 1).uppercase()
        val metrics = graphics.fontMetrics
        graphics.drawString(initial, x + (avatarSize - metrics.stringWidth(initial)) / 2, y + (if (safeColumns == 3) 40 else 50))
    }

    private fun drawEmptyState(graphics: Graphics2D) {
        val x = sideMargin
        val y = GRID_TOP + 25
        val width = WIDTH - sideMargin * 2
        val height = 160
        val card = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), 36f, 36f)
        graphics.color = CARD_FILL
        graphics.fill(card)
        graphics.color = CARD_BORDER
        graphics.stroke = BasicStroke(1.7f)
        graphics.draw(card)

        graphics.font = font(Font.BOLD, 30)
        val title = "服务器里静悄悄的"
        drawCentered(graphics, title, y + 70, TEXT_PRIMARY)
        graphics.font = font(Font.PLAIN, 22)
        drawCentered(graphics, "当前没有玩家在线", y + 112, TEXT_SECONDARY)
    }

    private fun drawFooter(graphics: Graphics2D, footerTop: Int, snapshot: ServerSnapshot) {
        if (safeFooterText.isNotEmpty()) {
            graphics.font = font(Font.PLAIN, 18)
            drawCentered(graphics, safeFooterText, footerTop + 73, Color(226, 249, 255, 180))
        }

        // 用同一玻璃胶囊内的无装饰纹理覆盖原有四个方块，再写入动态页码。
        graphics.drawImage(footer, 525, footerTop + 151, 675, footerTop + 202, 500, 151, 501, 202, null)
        graphics.font = font(Font.BOLD, 23)
        drawCentered(
            graphics,
            "第 ${snapshot.currentPage} / ${snapshot.totalPages} 页",
            footerTop + 187,
            TEXT_PRIMARY
        )
    }

    private fun font(style: Int, size: Int): Font = Font(fontFamily, style, size)

    private fun truncate(graphics: Graphics2D, text: String, maxWidth: Int): String {
        val metrics = graphics.fontMetrics
        if (metrics.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun drawRight(graphics: Graphics2D, text: String, right: Int, baseline: Int, color: Color) {
        graphics.color = color
        graphics.drawString(text, right - graphics.fontMetrics.stringWidth(text), baseline)
    }

    private fun drawCentered(graphics: Graphics2D, text: String, baseline: Int, color: Color) {
        graphics.color = color
        graphics.drawString(text, (WIDTH - graphics.fontMetrics.stringWidth(text)) / 2, baseline)
    }

    private fun configure(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    private fun chooseFont(configured: String): String {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        if (configured.isNotBlank() && available.contains(configured.trim())) return configured.trim()
        val candidates = arrayOf("Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Micro Hei", "SansSerif")
        for (candidate in candidates) if (available.contains(candidate)) return candidate
        return Font.SANS_SERIF
    }

    private fun readResource(path: String): BufferedImage {
        val input: InputStream? = OnlineListRenderer::class.java.getResourceAsStream(path)
            ?: throw IOException("Missing image resource: $path")
        val image = input.use { ImageIO.read(it) }
            ?: throw IOException("Unreadable image resource: $path")
        return image
    }

    private fun validateSlice(image: BufferedImage, width: Int, height: Int, name: String) {
        if (image.width != width || image.height != height) {
            throw IllegalArgumentException(
                "$name must be ${width}x$height, got ${image.width}x${image.height}"
            )
        }
    }

    private fun divideRoundUp(value: Int, divisor: Int): Int = maxOf(0, (value + divisor - 1) / divisor)
}
