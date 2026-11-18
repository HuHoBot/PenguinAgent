package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
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
 * 深色科技风的在线列表渲染器（纯代码绘制背景，不依赖底图切片）。
 *
 * 视觉要素：深空渐变背景 + 细网格 + 光斑，左上服务器图标与名称，
 * 名称下彩色 MOTD（支持 §/& 传统代码、hex、MiniMessage），
 * 在线趋势折线图（最近 48 小时）与 TPS/MSPT/内存性能卡，
 * 玩家卡片（管理员金色高亮），左下角保留 HuHo 娘。
 */
class OnlineListRenderer(
    private val avatarCache: AvatarCache,
    private val columns: Int,
    private val configuredFont: String,
    private val footerText: String,
    private val protocolSummary: String? = null,
    private val maxRenderPlayers: Int = 60,
    /** 自定义信息行（config online-list.render.custom-lines），支持 §/hex/MiniMessage。 */
    private val customLines: List<String> = emptyList(),
    /** 玩家名颜色（config online-list.display.name-color），空 = 默认白。 */
    private val nameColor: String = "",
    /** 主题：dark（默认）/ light。 */
    private val themeName: String = "dark",
    /** 是否显示节日横幅。 */
    private val eventsEnabled: Boolean = true
) {
    companion object {
        const val WIDTH = 1200
        const val HEADER_HEIGHT = 384
        const val BODY_TILE_HEIGHT = 640
        const val FOOTER_HEIGHT = 256
        private const val GRID_TOP = 618
        private const val FOOTER_BOTTOM_HEIGHT = 196
        private const val MASCOT_HEIGHT = 150

        /** 主题色板（深色 / 亮色两套，配置 online-list.render.theme 切换）。 */
        data class Theme(
            val bg1: Color, val bg2: Color, val bg3: Color,
            val grid: Color,
            val textPrimary: Color, val textSecondary: Color,
            val accent: Color, val accent2: Color,
            val cardFill: Color, val cardBorder: Color, val cardShadow: Color,
            val adminText: Color, val adminBorder: Color, val adminFill: Color, val crownGold: Color,
            val good: Color, val warn: Color, val bad: Color
        )

        private val DARK = Theme(
            bg1 = Color(10, 15, 28), bg2 = Color(14, 22, 40), bg3 = Color(9, 13, 24),
            grid = Color(120, 200, 255, 10),
            textPrimary = Color(234, 244, 255), textSecondary = Color(143, 166, 192),
            accent = Color(79, 227, 193), accent2 = Color(139, 124, 246),
            cardFill = Color(255, 255, 255, 12), cardBorder = Color(120, 200, 255, 46),
            cardShadow = Color(0, 0, 0, 90),
            adminText = Color(255, 214, 120), adminBorder = Color(255, 206, 84, 210),
            adminFill = Color(255, 206, 84, 34), crownGold = Color(255, 198, 61),
            good = Color(79, 227, 193), warn = Color(255, 190, 92), bad = Color(255, 107, 107)
        )

        private val LIGHT = Theme(
            bg1 = Color(238, 245, 252), bg2 = Color(226, 236, 248), bg3 = Color(248, 251, 255),
            grid = Color(30, 70, 120, 14),
            textPrimary = Color(18, 35, 59), textSecondary = Color(90, 113, 134),
            accent = Color(14, 159, 134), accent2 = Color(96, 86, 200),
            cardFill = Color(255, 255, 255, 165), cardBorder = Color(120, 160, 220, 110),
            cardShadow = Color(30, 60, 100, 28),
            adminText = Color(104, 60, 0), adminBorder = Color(202, 140, 20, 230),
            adminFill = Color(255, 226, 160, 150), crownGold = Color(232, 168, 32),
            good = Color(14, 140, 100), warn = Color(196, 124, 16), bad = Color(200, 52, 52)
        )

        private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Shanghai"))
        private val HOUR = DateTimeFormatter.ofPattern("HH时").withZone(ZoneId.of("Asia/Shanghai"))
    }

    /** HuHo 娘立绘（repo 提供的 HuHo.png），放在左下角作为装饰。 */
    private val mascot: BufferedImage = readResource("/online/HuHo.png")

    /** 内置默认头像（Steve / Alex）：离线模式或网络不可用时的兜底，永不留空。 */
    private val defaultHeads: List<BufferedImage> = listOfNotNull(
        runCatching { readResource("/online/head-steve.png") }.getOrNull(),
        runCatching { readResource("/online/head-alex.png") }.getOrNull()
    )

    private val safeColumns = columns.coerceIn(1, 3)
    private val compact = safeColumns == 3
    private val sideMargin = if (compact) 88 else 96
    private val cardGapX = if (compact) 18 else 28
    private val cardGapY = if (compact) 16 else 22
    private val cardHeight = if (compact) 96 else 118
    private val avatarSize = if (compact) 60 else 76
    private val fontFamily = chooseFont(configuredFont)
    private val theme: Theme = if (themeName.equals("light", true)) LIGHT else DARK
    /** 玩家区起始 Y（随信息区实际高度动态计算，避免空行）。 */
    private var gridTop = GRID_TOP
    private val safeFooterText = footerText.trim()
    private val safeProtocolSummary = protocolSummary?.trim()

    /** 渲染一页在线列表。 */
    @Throws(IOException::class)
    fun render(snapshot: ServerSnapshot): ByteArray {
        val avatars = avatarCache.loadAll(snapshot.players)
        val image = renderImage(snapshot, avatars)
        val output = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", output)) throw IOException("PNG writer is unavailable")
        return output.toByteArray()
    }

    private fun renderImage(snapshot: ServerSnapshot, avatars: Map<String, BufferedImage>): BufferedImage {
        val displayed = snapshot.players.size.coerceAtMost(maxRenderPlayers)
        val rows = if (displayed == 0) 0 else (displayed + safeColumns - 1) / safeColumns
        val gridHeight = if (rows == 0) 0 else rows * cardHeight + (rows - 1) * cardGapY
        val contentBottom = if (rows == 0) GRID_TOP + 190 else GRID_TOP + gridHeight
        val height = contentBottom + FOOTER_BOTTOM_HEIGHT

        val result = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            configure(graphics)
            drawBackground(graphics, height)
            // 信息区实际高度 → 趋势卡与玩家区紧接其后，不留空行
            val heroBottom = drawHero(graphics, snapshot)
            val trendTop = heroBottom + 26
            val trendHeight = drawTrendAndMetrics(graphics, snapshot, trendTop)
            gridTop = trendTop + trendHeight + 24
            val newContentBottom = if (rows == 0) gridTop + 190 else gridTop + gridHeight
            val newHeight = newContentBottom + FOOTER_BOTTOM_HEIGHT
            // 高度按实际排版重新生成（先画到临时图，再裁剪/重绘）
            if (newHeight != height) {
                val resized = BufferedImage(WIDTH, newHeight, BufferedImage.TYPE_INT_ARGB)
                val resizedGraphics = resized.createGraphics()
                try {
                    configure(resizedGraphics)
                    drawBackground(resizedGraphics, newHeight)
                    drawHero(resizedGraphics, snapshot)
                    drawTrendAndMetrics(resizedGraphics, snapshot, trendTop)
                    if (rows == 0) drawEmptyState(resizedGraphics) else drawPlayers(resizedGraphics, snapshot, avatars)
                    drawBottomBar(resizedGraphics, newHeight, snapshot)
                } finally {
                    resizedGraphics.dispose()
                }
                return resized
            }
            drawTrendAndMetrics(graphics, snapshot, trendTop)
            if (rows == 0) drawEmptyState(graphics) else drawPlayers(graphics, snapshot, avatars)
            drawBottomBar(graphics, height, snapshot)
        } finally {
            graphics.dispose()
        }
        return result
    }

    // ───────────────────────── 背景 ─────────────────────────

    private fun drawBackground(graphics: Graphics2D, height: Int) {
        graphics.paint = LinearGradientPaint(
            0f, 0f, 0f, height.toFloat(),
            floatArrayOf(0f, 0.45f, 1f),
            arrayOf(theme.bg1, theme.bg2, theme.bg3)
        )
        graphics.fillRect(0, 0, WIDTH, height)

        // 顶部光斑
        graphics.paint = RadialGradientPaint(
            (WIDTH * 0.22f), 120f, 520f,
            floatArrayOf(0f, 1f),
            arrayOf(Color(theme.accent.red, theme.accent.green, theme.accent.blue, if (themeName.equals("light", true)) 26 else 46), Color(theme.accent.red, theme.accent.green, theme.accent.blue, 0))
        )
        graphics.fillRect(0, 0, WIDTH, 640)
        graphics.paint = RadialGradientPaint(
            (WIDTH * 0.88f), 60f, 480f,
            floatArrayOf(0f, 1f),
            arrayOf(Color(theme.accent2.red, theme.accent2.green, theme.accent2.blue, if (themeName.equals("light", true)) 24 else 42), Color(theme.accent2.red, theme.accent2.green, theme.accent2.blue, 0))
        )
        graphics.fillRect(0, 0, WIDTH, 640)

        // 细网格
        graphics.color = theme.grid
        graphics.stroke = BasicStroke(1f)
        var y = 40
        while (y < height) {
            graphics.drawLine(0, y, WIDTH, y)
            y += 48
        }
        var x = 0
        while (x < WIDTH) {
            graphics.drawLine(x, 0, x, height)
            x += 48
        }
    }

    // ───────────────────────── 顶部信息 ─────────────────────────

    private fun drawHero(graphics: Graphics2D, snapshot: ServerSnapshot): Int {
        // 顶部标题（用户要求保留"在线列表"字样）
        graphics.font = font(Font.BOLD, 30)
        graphics.color = theme.textSecondary
        val subtitle = "在线列表"
        graphics.drawString(subtitle, (WIDTH - graphics.fontMetrics.stringWidth(subtitle)) / 2, 48)

        // 节日 / 事件横幅（内置事件表；命中的日期自动显示）
        if (eventsEnabled) {
            EventTheme.current()?.let { event ->
                graphics.font = font(Font.BOLD, 19)
                val bannerWidth = graphics.fontMetrics.stringWidth(event.banner) + 34
                val bannerX = (WIDTH - bannerWidth) / 2
                val banner = RoundRectangle2D.Float(bannerX.toFloat(), 62f, bannerWidth.toFloat(), 30f, 15f, 15f)
                graphics.color = if (themeName.equals("light", true)) Color(226, 168, 40, 46) else Color(255, 198, 61, 38)
                graphics.fill(banner)
                graphics.color = theme.adminBorder
                graphics.stroke = BasicStroke(1.2f)
                graphics.draw(banner)
                graphics.color = theme.adminText
                graphics.drawString(event.banner, bannerX + 17, 84)
            }
        }

        val iconSize = 112
        val iconX = sideMargin
        val iconY = 78
        val iconShape = RoundRectangle2D.Float(iconX.toFloat(), iconY.toFloat(), iconSize.toFloat(), iconSize.toFloat(), 24f, 24f)
        graphics.color = theme.cardShadow
        graphics.fill(RoundRectangle2D.Float((iconX + 3).toFloat(), (iconY + 5).toFloat(), iconSize.toFloat(), iconSize.toFloat(), 24f, 24f))
        val clip = graphics.clip
        graphics.clip(iconShape)
        val icon = snapshot.icon
        if (icon != null) {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(icon, iconX, iconY, iconSize, iconSize, null)
        } else {
            graphics.color = if (themeName.equals("light", true)) Color(210, 224, 240) else Color(30, 44, 68)
            graphics.fill(iconShape)
            graphics.font = font(Font.BOLD, 26)
            graphics.color = theme.textSecondary
            drawCenteredIn(graphics, "无图标", iconX, iconSize, iconY + 62)
        }
        graphics.clip = clip
        graphics.color = theme.accent
        graphics.stroke = BasicStroke(2f)
        graphics.draw(iconShape)

        val textLeft = iconX + iconSize + 26
        val right = WIDTH - sideMargin

        // 名称（大号）+ 状态点
        graphics.font = font(Font.BOLD, 48)
        graphics.color = theme.textPrimary
        val name = truncate(graphics, snapshot.serverName, WIDTH - textLeft - 320)
        graphics.drawString(name, textLeft, 134)
        graphics.color = theme.accent
        graphics.fillOval(textLeft + graphics.fontMetrics.stringWidth(name) + 12, 108, 12, 12)

        // 人数（右上，格式 "3 / 20"）
        val onlineText = "${snapshot.totalOnlinePlayers}"
        val maxText = " / ${snapshot.maxPlayers}"
        graphics.font = font(Font.BOLD, 42)
        val onlineWidth = graphics.fontMetrics.stringWidth(onlineText)
        graphics.font = font(Font.PLAIN, 24)
        val maxWidth = graphics.fontMetrics.stringWidth(maxText)
        val startX = right - onlineWidth - maxWidth
        graphics.font = font(Font.BOLD, 42)
        graphics.color = theme.textPrimary
        graphics.drawString(onlineText, startX, 134)
        graphics.font = font(Font.PLAIN, 24)
        graphics.color = theme.textSecondary
        graphics.drawString(maxText, startX + onlineWidth, 134)

        // 彩色 MOTD（两行，支持 §/hex/MiniMessage）
        val motdLines = snapshot.motd.replace("\\n", "\n").split('\n').filter(String::isNotBlank).take(2)
        if (motdLines.isEmpty()) {
            graphics.font = font(Font.PLAIN, 21)
            graphics.color = theme.textSecondary
            graphics.drawString("（未配置 MOTD）", textLeft, 172)
        } else {
            motdLines.forEachIndexed { index, line ->
                drawRichText(graphics, line, textLeft, 174 + index * 30, WIDTH - textLeft - sideMargin, 24)
            }
        }

        // 技术行：只保留必要信息（版本 / 品牌 / Via 支持），不显示构建号
        val coreShort = snapshot.coreVersion.substringBefore("-").substringBefore(" ").trim()
        val infoParts = mutableListOf<String>()
        coreShort.takeIf(String::isNotBlank)?.let { infoParts.add(it) }
        snapshot.brand.takeIf(String::isNotBlank)?.let { infoParts.add(it) }
        snapshot.viaRange.takeIf(String::isNotBlank)?.let { infoParts.add("支持 $it") }
        graphics.font = font(Font.PLAIN, 20)
        graphics.color = theme.textSecondary
        graphics.drawString(
            truncate(graphics, infoParts.joinToString(" · "), WIDTH - sideMargin * 2),
            sideMargin, 280
        )

        // 地址（重要信息加粗；域名默认不带端口，非 25565 才显示端口）
        var cursorY = 308
        if (snapshot.serverIp.isNotBlank()) {
            val address = if (snapshot.serverPort == 25565 || snapshot.serverPort <= 0) {
                snapshot.serverIp
            } else {
                "${snapshot.serverIp}:${snapshot.serverPort}"
            }
            graphics.font = font(Font.BOLD, 22)
            graphics.color = theme.accent
            graphics.drawString(truncate(graphics, address, WIDTH - sideMargin * 2), sideMargin, cursorY)
            cursorY += 30
        }

        // 自定义信息行（可随意增删，无配置时不留空行）
        customLines.take(3).forEach { line ->
            drawRichText(graphics, line, sideMargin, cursorY, WIDTH - sideMargin * 2, 21)
            cursorY += 26
        }
        return cursorY + 6
    }

    // ───────────────────────── 趋势 + 性能 ─────────────────────────

    private fun drawTrendAndMetrics(graphics: Graphics2D, snapshot: ServerSnapshot, top: Int): Int {
        val height = 236
        val trendWidth = 760
        val metricsX = sideMargin + trendWidth + 24
        val metricsWidth = WIDTH - sideMargin - metricsX

        // 趋势卡
        val trendCard = RoundRectangle2D.Float(sideMargin.toFloat(), top.toFloat(), trendWidth.toFloat(), height.toFloat(), 26f, 26f)
        graphics.color = theme.cardFill
        graphics.fill(trendCard)
        graphics.color = theme.cardBorder
        graphics.stroke = BasicStroke(1.6f)
        graphics.draw(trendCard)

        graphics.font = font(Font.BOLD, 22)
        graphics.color = theme.textPrimary
        graphics.drawString("在线趋势 · 最近 48 小时", sideMargin + 26, top + 38)

        val points = OnlineHistoryStore.recent(48)
        if (points.size < 2) {
            graphics.font = font(Font.PLAIN, 19)
            graphics.color = theme.textSecondary
            graphics.drawString("数据采集中（每 5 分钟记录一次，稍后再来看趋势曲线）", sideMargin + 26, top + 110)
        } else {
            drawSparkline(graphics, points, sideMargin + 26, top + 60, trendWidth - 52, height - 90)
        }

        // 性能卡
        val metricsCard = RoundRectangle2D.Float(metricsX.toFloat(), top.toFloat(), metricsWidth.toFloat(), height.toFloat(), 26f, 26f)
        graphics.color = theme.cardFill
        graphics.fill(metricsCard)
        graphics.color = theme.cardBorder
        graphics.stroke = BasicStroke(1.6f)
        graphics.draw(metricsCard)

        data class Metric(val label: String, val value: String, val ratio: Double, val accent: Color)
        val memRatio = if (snapshot.memoryMaxMb > 0) snapshot.memoryUsedMb.toDouble() / snapshot.memoryMaxMb else 0.0
        val metrics = listOf(
            Metric("TPS", snapshot.tps?.let { String.format("%.1f", it) } ?: "—",
                snapshot.tps?.let { (it / 20.0).coerceIn(0.0, 1.0) } ?: 0.0,
                snapshot.tps?.let { tps -> when { tps >= 18 -> theme.good; tps >= 14 -> theme.warn; else -> theme.bad } } ?: theme.textSecondary),
            Metric("MSPT", snapshot.mspt?.let { String.format("%.1f ms", it) } ?: "—",
                snapshot.mspt?.let { (it / 50.0).coerceIn(0.0, 1.0) } ?: 0.0,
                snapshot.mspt?.let { mspt -> when { mspt < 20 -> theme.good; mspt < 40 -> theme.warn; else -> theme.bad } } ?: theme.textSecondary),
            Metric("内存", if (snapshot.memoryMaxMb > 0) "${snapshot.memoryUsedMb} / ${snapshot.memoryMaxMb} MB" else "—",
                memRatio.coerceIn(0.0, 1.0),
                if (snapshot.memoryMaxMb > 0) when { memRatio < 0.7 -> theme.good; memRatio < 0.85 -> theme.warn; else -> theme.bad } else theme.textSecondary)
        )
        metrics.forEachIndexed { index, metric ->
            val y = top + 44 + index * 54
            graphics.font = font(Font.PLAIN, 16)
            graphics.color = theme.textSecondary
            graphics.drawString(metric.label, metricsX + 24, y)
            // 数值另起一行左对齐：长数字（如 12345 / 32768 MB）不会压到标签
            graphics.font = font(Font.BOLD, 19)
            graphics.color = metric.accent
            graphics.drawString(truncate(graphics, metric.value, metricsWidth - 48), metricsX + 24, y + 20)
            val barY = y + 28
            val barWidth = metricsWidth - 48
            graphics.color = if (themeName.equals("light", true)) Color(120, 150, 190, 60) else Color(255, 255, 255, 26)
            graphics.fill(RoundRectangle2D.Float((metricsX + 24).toFloat(), barY.toFloat(), barWidth.toFloat(), 7f, 6f, 6f))
            graphics.color = metric.accent
            graphics.fill(
                RoundRectangle2D.Float(
                    (metricsX + 24).toFloat(), barY.toFloat(),
                    (barWidth * metric.ratio).toFloat().coerceAtLeast(if (metric.ratio > 0) 6f else 0f), 7f, 6f, 6f
                )
            )
        }

        // 问题提示（性能卡内，不覆盖 MOTD）：只列出确实存在的异常
        val issues = mutableListOf<String>()
        snapshot.tps?.let { if (it < 15.0) issues.add("TPS 偏低 ${String.format("%.1f", it)}") }
        snapshot.mspt?.let { if (it > 45.0) issues.add("单 tick 耗时偏高 ${String.format("%.0f", it)}ms") }
        if (snapshot.memoryMaxMb > 0 && memRatio > 0.88) {
            issues.add("内存占用 ${(memRatio * 100).toInt()}%，接近上限")
        }
        val issueY = top + 204
        if (issues.isNotEmpty()) {
            graphics.font = font(Font.BOLD, 16)
            graphics.color = theme.bad
            graphics.drawString("检测到 ${issues.size} 项异常", metricsX + 24, issueY)
            graphics.font = font(Font.PLAIN, 15)
            graphics.color = Color(255, 178, 178, 220)
            issues.take(1).forEach { issue ->
                graphics.drawString(truncate(graphics, "· $issue", metricsWidth - 48), metricsX + 24, issueY + 20)
            }
        } else {
            // 无异常也给出状态行，避免性能卡底部留空
            graphics.font = font(Font.BOLD, 15)
            graphics.color = theme.good
            graphics.drawString("运行正常", metricsX + 24, issueY)
        }
        return height
    }

    /** 折线图：渐变填充 + 曲线 + 峰值 + 网格刻度。 */
    private fun drawSparkline(graphics: Graphics2D, raw: List<Pair<Long, Int>>, x: Int, y: Int, width: Int, height: Int) {
        // 降采样到最多 120 点
        val step = maxOf(1, raw.size / 120)
        val points = if (step > 1) raw.filterIndexed { index, _ -> index % step == 0 } else raw
        val maxValue = maxOf(1, points.maxOfOrNull { it.second } ?: 1)
        val peak = points.maxByOrNull { it.second }
        val chartX = x
        val chartY = y
        val chartW = width
        val chartH = height

        // 网格 + Y 轴刻度
        graphics.font = font(Font.PLAIN, 15)
        for (i in 0..3) {
            val gy = chartY + chartH * i / 3
            graphics.color = Color(255, 255, 255, 18)
            graphics.stroke = BasicStroke(1f)
            graphics.drawLine(chartX, gy, chartX + chartW, gy)
            graphics.color = theme.textSecondary
            val value = (maxValue * (3 - i) / 3.0).toInt()
            graphics.drawString("$value", chartX - 6 - graphics.fontMetrics.stringWidth("$value"), gy + 5)
        }
        // X 轴时间标签
        val first = points.first().first
        val last = points.last().first
        val span = (last - first).coerceAtLeast(1)
        for (i in 0..4) {
            val gx = chartX + chartW * i / 4
            val time = first + span * i / 4
            graphics.color = theme.textSecondary
            val label = HOUR.format(Instant.ofEpochMilli(time))
            val lw = graphics.fontMetrics.stringWidth(label)
            val lx = (gx - lw / 2).coerceIn(chartX, chartX + chartW - lw)
            graphics.drawString(label, lx, chartY + chartH + 20)
        }

        // 曲线路径（横向按时间比例，纵向按值比例）
        val curve = Path2D.Float()
        val fill = Path2D.Float()
        points.forEachIndexed { index, point ->
            val px = chartX + (chartW * (point.first - first).toDouble() / span).toFloat()
            val py = chartY + chartH - (chartH * point.second.toDouble() / maxValue).toFloat()
            if (index == 0) { curve.moveTo(px, py); fill.moveTo(px, (chartY + chartH).toFloat()); fill.lineTo(px, py) }
            else { curve.lineTo(px, py); fill.lineTo(px, py) }
        }
        fill.lineTo((chartX + chartW).toFloat(), (chartY + chartH).toFloat())
        fill.closePath()

        // 渐变填充
        graphics.paint = LinearGradientPaint(
            chartX.toFloat(), chartY.toFloat(), chartX.toFloat(), (chartY + chartH).toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(Color(79, 227, 193, 70), Color(79, 227, 193, 0))
        )
        graphics.fill(fill)
        graphics.paint = null

        // 曲线
        graphics.color = theme.accent
        graphics.stroke = BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(curve)

        // 峰值标注：垂直虚线 + 光晕双圈 + 胶囊标签
        if (peak != null) {
            val px = chartX + (chartW * (peak.first - first).toDouble() / span).toFloat()
            val py = chartY + chartH - (chartH * peak.second.toDouble() / maxValue).toFloat()

            // 垂直虚线（峰值时间对齐到 X 轴）
            graphics.color = Color(139, 124, 246, 90)
            graphics.stroke = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 5f), 0f)
            graphics.drawLine(px.toInt(), py.toInt() + 8, px.toInt(), chartY + chartH)
            graphics.stroke = BasicStroke(1.2f)

            // 外层光晕 + 内层亮点
            graphics.color = Color(139, 124, 246, 60)
            graphics.fillOval((px - 11).toInt(), (py - 11).toInt(), 22, 22)
            graphics.color = Color(139, 124, 246, 130)
            graphics.fillOval((px - 7).toInt(), (py - 7).toInt(), 14, 14)
            graphics.color = Color(235, 230, 255)
            graphics.fillOval((px - 4).toInt(), (py - 4).toInt(), 8, 8)

            // 胶囊标签
            graphics.font = font(Font.BOLD, 15)
            val label = "峰值 ${peak.second}"
            val labelWidth = graphics.fontMetrics.stringWidth(label)
            val boxWidth = labelWidth + 20
            val boxHeight = 24
            val boxX = (px + 12).coerceAtMost((chartX + chartW - boxWidth).toFloat()).toInt()
            val boxY = (py - boxHeight - 10).toInt().coerceAtLeast(chartY + 2)
            val box = RoundRectangle2D.Float(boxX.toFloat(), boxY.toFloat(), boxWidth.toFloat(), boxHeight.toFloat(), 12f, 12f)
            graphics.color = Color(48, 40, 88, 220)
            graphics.fill(box)
            graphics.color = Color(139, 124, 246, 200)
            graphics.stroke = BasicStroke(1.2f)
            graphics.draw(box)
            graphics.color = Color(226, 220, 255)
            graphics.drawString(label, boxX + 10, boxY + 17)
        }
    }

    // ───────────────────────── 玩家 ─────────────────────────

    private fun drawPlayers(graphics: Graphics2D, snapshot: ServerSnapshot, avatars: Map<String, BufferedImage>) {
        val usableWidth = WIDTH - sideMargin * 2
        val cardWidth = (usableWidth - (safeColumns - 1) * cardGapX) / safeColumns
        for ((index, player) in snapshot.players.withIndex()) {
            if (index >= maxRenderPlayers) break
            val column = index % safeColumns
            val row = index / safeColumns
            val x = sideMargin + column * (cardWidth + cardGapX)
            val y = gridTop + row * (cardHeight + cardGapY)
            drawPlayerCard(graphics, x, y, cardWidth, player, avatars[player.uuid])
        }
    }

    private fun drawPlayerCard(graphics: Graphics2D, x: Int, y: Int, width: Int, player: OnlineListPlayer, avatar: BufferedImage?) {
        val cardArc = if (compact) 20 else 26
        val card = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), cardHeight.toFloat(), cardArc.toFloat(), cardArc.toFloat())
        graphics.color = theme.cardShadow
        val shadowOffset = if (compact) 4 else 6
        graphics.fill(RoundRectangle2D.Float((x + 2).toFloat(), (y + shadowOffset).toFloat(), width.toFloat(), cardHeight.toFloat(), cardArc.toFloat(), cardArc.toFloat()))
        if (player.administrator) {
            graphics.color = Color(255, 198, 61, 40)
            graphics.fill(RoundRectangle2D.Float((x - 3).toFloat(), (y - 3).toFloat(), (width + 6).toFloat(), (cardHeight + 6).toFloat(), (cardArc + 3).toFloat(), (cardArc + 3).toFloat()))
        }
        graphics.color = if (player.administrator) theme.adminFill else theme.cardFill
        graphics.fill(card)
        graphics.stroke = BasicStroke(if (player.administrator) 2.2f else 1.4f)
        graphics.color = if (player.administrator) theme.adminBorder else theme.cardBorder
        graphics.draw(card)
        if (player.administrator) drawCrown(graphics, x + width - 34, y + 12, 22, 15)

        val avatarX = x + (if (compact) 16 else 21)
        val avatarY = y + (cardHeight - avatarSize) / 2
        val avatarArc = if (compact) 14 else 18
        val avatarShape = RoundRectangle2D.Float(avatarX.toFloat(), avatarY.toFloat(), avatarSize.toFloat(), avatarSize.toFloat(), avatarArc.toFloat(), avatarArc.toFloat())
        val previousClip = graphics.clip
        graphics.clip(avatarShape)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val shownAvatar = avatar ?: defaultHeadFor(player)
        if (shownAvatar != null) {
            graphics.drawImage(shownAvatar, avatarX, avatarY, avatarSize, avatarSize, null)
        } else {
            graphics.color = if (themeName.equals("light", true)) Color(222, 232, 244) else Color(46, 64, 92)
            graphics.fill(avatarShape)
            graphics.font = font(Font.BOLD, if (compact) 24 else 30)
            graphics.color = theme.textSecondary
            drawCenteredIn(graphics, player.name.take(1).uppercase(), avatarX, avatarSize, avatarY + avatarSize / 2 + (if (compact) 8 else 10))
        }
        graphics.clip = previousClip
        graphics.color = Color(120, 200, 255, 90)
        graphics.stroke = BasicStroke(1.6f)
        graphics.draw(avatarShape)

        // 在线状态点
        graphics.color = theme.accent
        val dot = if (compact) 10 else 13
        graphics.fillOval(avatarX + avatarSize - dot, avatarY + avatarSize - dot, dot, dot)

        val textX = avatarX + avatarSize + (if (compact) 13 else 19)
        val nameSize = if (compact) 22 else 28
        // 称号（LuckPerms 前缀/后缀）与名字一起解析颜色；名字颜色可由配置覆盖
        val richName = buildString {
            if (player.prefix.isNotBlank()) append(player.prefix)
            if (nameColor.isNotBlank()) append("<color:").append(nameColor).append(">")
            append(player.name)
            if (nameColor.isNotBlank()) append("</color>")
            if (player.suffix.isNotBlank()) append(player.suffix)
        }
        drawRichText(
            graphics, richName, textX, y + (if (compact) 50 else 69),
            x + width - textX - 18, nameSize,
            if (player.administrator) theme.adminText else theme.textPrimary
        )

        graphics.font = font(Font.PLAIN, if (compact) 14 else 17)
        graphics.color = if (player.administrator) Color(255, 206, 84, 200) else theme.textSecondary
        graphics.drawString(if (player.administrator) "ADMIN" else "ONLINE", textX, y + (if (compact) 72 else 93))
    }

    /** 按 uuid 哈希选择内置默认头像（Steve/Alex），保证每个玩家都有头像。 */
    private fun defaultHeadFor(player: OnlineListPlayer): BufferedImage? {
        if (defaultHeads.isEmpty()) return null
        val index = (player.uuid.hashCode().toLong() and 0x7fffffff).mod(defaultHeads.size.toLong()).toInt()
        return defaultHeads[index]
    }

    private fun drawCrown(graphics: Graphics2D, x: Int, y: Int, width: Int, height: Int) {
        val path = Path2D.Float().apply {
            moveTo(x.toFloat(), (y + height).toFloat())
            lineTo(x.toFloat(), (y + height * 0.25f).toFloat())
            lineTo((x + width * 0.25f), (y + height * 0.6f).toFloat())
            lineTo((x + width * 0.5f), y.toFloat())
            lineTo((x + width * 0.75f), (y + height * 0.6f).toFloat())
            lineTo((x + width).toFloat(), (y + height * 0.25f).toFloat())
            lineTo((x + width).toFloat(), (y + height).toFloat())
            closePath()
        }
        graphics.color = theme.crownGold
        graphics.fill(path)
    }

    // ───────────────────────── 空态 / 底部 ─────────────────────────

    private fun drawEmptyState(graphics: Graphics2D) {
        val x = sideMargin
        val y = gridTop
        val width = WIDTH - sideMargin * 2
        val height = 160
        val card = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), 26f, 26f)
        graphics.color = theme.cardFill
        graphics.fill(card)
        graphics.color = theme.cardBorder
        graphics.stroke = BasicStroke(1.6f)
        graphics.draw(card)

        graphics.font = font(Font.BOLD, 28)
        drawCentered(graphics, "服务器里静悄悄的", y + 70, theme.textPrimary)
        graphics.font = font(Font.PLAIN, 21)
        drawCentered(graphics, "当前没有玩家在线", y + 112, theme.textSecondary)
    }

    private fun drawBottomBar(graphics: Graphics2D, imageHeight: Int, snapshot: ServerSnapshot) {
        val top = imageHeight - FOOTER_BOTTOM_HEIGHT
        graphics.color = if (themeName.equals("light", true)) Color(120, 150, 190, 60) else Color(120, 200, 255, 26)
        graphics.stroke = BasicStroke(1f)
        graphics.drawLine(sideMargin, top, WIDTH - sideMargin, top)

        // HuHo 娘：左下角半身出血（下半身沉出画面，只露脸与上身），单独预留不遮挡文字
        val mascotHeight = FOOTER_BOTTOM_HEIGHT + 74
        val mascotWidth = (mascot.width.toDouble() / mascot.height * mascotHeight).toInt().coerceAtMost(280)
        val mascotX = -14
        val mascotY = imageHeight - mascotHeight + 74
        graphics.paint = RadialGradientPaint(
            (mascotX + mascotWidth / 2f), (mascotY + mascotHeight / 3f), (mascotWidth * 0.95f),
            floatArrayOf(0f, 1f),
            arrayOf(
                Color(theme.accent.red, theme.accent.green, theme.accent.blue, if (themeName.equals("light", true)) 30 else 58),
                Color(theme.accent.red, theme.accent.green, theme.accent.blue, 0)
            )
        )
        graphics.fillOval(
            mascotX - mascotWidth / 3, mascotY - mascotHeight / 4,
            mascotWidth + mascotWidth * 2 / 3, mascotHeight + mascotHeight / 2
        )
        graphics.paint = null
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(mascot, mascotX, mascotY, mascotWidth, mascotHeight, null)

        // 文字：在整张图内居中（不被 HuHo 娘挤偏）
        var baseline = top + 40
        if (safeFooterText.isNotEmpty()) {
            graphics.font = font(Font.PLAIN, 18)
            drawCentered(graphics, safeFooterText, baseline, theme.textSecondary)
            baseline += 28
        }
        safeProtocolSummary?.takeIf(String::isNotBlank)?.let { summary ->
            graphics.font = font(Font.PLAIN, 16)
            drawCentered(graphics, stripMinecraftCodes(summary), baseline, Color(theme.textSecondary.red, theme.textSecondary.green, theme.textSecondary.blue, 190))
            baseline += 26
        }
        if (snapshot.players.size > maxRenderPlayers) {
            graphics.font = font(Font.BOLD, 17)
            drawCentered(
                graphics,
                "共 ${snapshot.players.size} 位玩家，图中显示前 $maxRenderPlayers 位（完整名单见下方文字）",
                baseline,
                theme.warn
            )
            baseline += 26
        }
        graphics.font = font(Font.BOLD, 20)
        drawCentered(graphics, "第 ${snapshot.currentPage} / ${snapshot.totalPages} 页", imageHeight - 32, theme.textPrimary)
        // 右下角：更新时间 + Powered by（避开左下 HuHo 娘）
        graphics.font = font(Font.PLAIN, 15)
        graphics.color = theme.textSecondary
        val powered = "Powered by HuHoBot"
        val poweredWidth = graphics.fontMetrics.stringWidth(powered)
        graphics.drawString(powered, WIDTH - sideMargin - poweredWidth, imageHeight - 34)
        val updated = "更新 ${TIME.format(Instant.ofEpochMilli(snapshot.capturedAt))}"
        graphics.drawString(updated, WIDTH - sideMargin - poweredWidth - 20 - graphics.fontMetrics.stringWidth(updated), imageHeight - 34)
    }

    // ───────────────────────── 富文本 ─────────────────────────

    /** 用 McTextParser 绘制富文本（§/hex/MiniMessage 全部支持）。 */
    private fun drawRichText(
        graphics: Graphics2D,
        text: String,
        x: Int,
        baseline: Int,
        maxWidth: Int,
        size: Int,
        defaultColor: Color = Color(234, 244, 255)
    ) {
        var cursor = x
        var stopped = false
        for (segment in McTextParser.parse(text, defaultColor)) {
            if (stopped || segment.text.isEmpty()) continue
            graphics.font = font(if (segment.bold) Font.BOLD else Font.PLAIN, size)
            graphics.color = if (themeName.equals("light", true)) readableOnLight(segment.color) else segment.color
            val width = graphics.fontMetrics.stringWidth(segment.text)
            if (cursor + width > x + maxWidth) {
                var end = segment.text.length
                while (end > 0 && graphics.fontMetrics.stringWidth(segment.text.substring(0, end) + "…") + (cursor - x) > maxWidth) end--
                if (end > 0) graphics.drawString(segment.text.substring(0, end) + "…", cursor, baseline)
                stopped = true
                continue
            }
            graphics.drawString(segment.text, cursor, baseline)
            cursor += width
        }
    }

    private fun stripMinecraftCodes(text: String): String = McTextParser.plainText(text)

    /** 亮色主题下把过亮的文字色压暗，保证白底可读（青色/黄色等 LP 前缀）。 */
    private fun readableOnLight(color: Color): Color {
        val luminance = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
        if (luminance <= 150) return color
        val factor = 0.52
        return Color(
            (color.red * factor).toInt().coerceAtLeast(0),
            (color.green * factor).toInt().coerceAtLeast(0),
            (color.blue * factor).toInt().coerceAtLeast(0)
        )
    }

    // ───────────────────────── 工具 ─────────────────────────

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

    private fun drawCenteredIn(graphics: Graphics2D, text: String, x: Int, width: Int, baseline: Int) {
        graphics.drawString(text, x + (width - graphics.fontMetrics.stringWidth(text)) / 2, baseline)
    }

    private fun drawCenteredIn(graphics: Graphics2D, text: String, x: Int, width: Int, baseline: Int, color: Color) {
        graphics.color = color
        drawCenteredIn(graphics, text, x, width, baseline)
    }

    private fun configure(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    private fun chooseFont(configured: String): String =
        cn.huohuas001.huhobotPenguin.spigot.manager.FontManager.chooseFont(configured)

    private fun readResource(path: String): BufferedImage {
        val input: InputStream? = OnlineListRenderer::class.java.getResourceAsStream(path)
            ?: throw IOException("Missing image resource: $path")
        return input.use { ImageIO.read(it) } ?: throw IOException("Unreadable image resource: $path")
    }


}
