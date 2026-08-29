package cn.huohuas001.huhobotPenguin.spigot.inventory

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO

/**
 * 基于 Faithful 32x (MIT) 主题的背包渲染器。
 * 使用纯 Java2D，无外部依赖。
 */
object InventoryRenderer {

    private val logger = Logger.getLogger("InventoryRenderer")
    private const val BG_WIDTH = 704
    private const val BG_HEIGHT = 664
    private const val SLOT_SIZE = 72
    private const val ITEM_SIZE = 64

    // 布局坐标（来自 layout.yml）
    private const val STORAGE_X = 28
    private const val STORAGE_Y = 332
    private const val STORAGE_COLS = 9
    private const val STORAGE_ROWS = 3
    private const val STEP_X = 72
    private const val STEP_Y = 72

    private const val HOTBAR_X = 28
    private const val HOTBAR_Y = 564

    private val ARMOR_POS = mapOf(
        "head" to Pair(28, 28),
        "chest" to Pair(28, 100),
        "legs" to Pair(28, 172),
        "feet" to Pair(28, 244)
    )
    private val OFFHAND_POS = Pair(304, 244)
    private val QTY_OFFSET = Pair(68, 68)
    private val DUR_OFFSET = Pair(8, 64)
    private const val DUR_WIDTH = 56
    private const val DUR_HEIGHT = 4

    // 缓存背景图和纹理
    private var backgroundImage: BufferedImage? = null
    private val textureCache = ConcurrentHashMap<String, BufferedImage?>()
    private var fallbackTexture: BufferedImage? = null

    fun init() {
        // 服务器通常无图形环境，需要启用 headless 模式
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true")
        }
        backgroundImage = loadResource("inventory/faithful32x/background.png")
        fallbackTexture = loadResource("inventory/faithful32x/fallback/unknown.png")
    }

    /**
     * 渲染玩家背包为 PNG 字节数组。
     */
    fun render(player: Player): ByteArray? {
        val bg = backgroundImage ?: return null
        val canvas = BufferedImage(BG_WIDTH, BG_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)

        // 绘制背景
        g.drawImage(bg, 0, 0, null)

        // 绘制玩家预览
        drawPlayerPreview(g, player)

        val inv = player.inventory
        val armor = mapOf(
            "head" to inv.helmet,
            "chest" to inv.chestplate,
            "legs" to inv.leggings,
            "feet" to inv.boots
        )
        val offhand = inv.itemInOffHand
        val storage = inv.storageContents // 36 slots: 0-8=hotbar, 9-35=storage

        // 绘制护甲槽
        for ((slot, pos) in ARMOR_POS) {
            val item = armor[slot]
            drawSlot(g, item, pos.first, pos.second)
        }

        // 绘制副手
        drawSlot(g, offhand, OFFHAND_POS.first, OFFHAND_POS.second)

        // 绘制物品栏 (3行9列, storageContents[9..35])
        for (row in 0 until STORAGE_ROWS) {
            for (col in 0 until STORAGE_COLS) {
                val idx = 9 + row * STORAGE_COLS + col
                val item = storage.getOrNull(idx)
                val x = STORAGE_X + col * STEP_X
                val y = STORAGE_Y + row * STEP_Y
                drawSlot(g, item, x, y)
            }
        }

        // 绘制快捷栏 (storageContents[0..8])
        for (col in 0 until 9) {
            val item = storage.getOrNull(col)
            val x = HOTBAR_X + col * STEP_X
            drawSlot(g, item, x, HOTBAR_Y)
        }

        // 绘制 Faithful 32x 水印（License 要求）
        drawWatermark(g)

        g.dispose()

        // 输出 PNG
        return try {
            val baos = ByteArrayOutputStream()
            ImageIO.write(canvas, "PNG", baos)
            baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun drawWatermark(g: Graphics2D) {
        val origComposite = g.composite
        val origColor = g.color
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val fontSmall = Font("SansSerif", Font.PLAIN, 16)
        val fontBold = Font("SansSerif", Font.BOLD, 18)

        val line1 = "Faithful 32x"
        val line2 = "faithfulpack.net"

        val fmSmall = g.getFontMetrics(fontSmall)
        val fmBold = g.getFontMetrics(fontBold)

        val padding = 12
        val lineSpacing = 3
        val totalHeight = fmBold.height + lineSpacing + fmSmall.height
        val maxWidth = maxOf(fmBold.stringWidth(line1), fmSmall.stringWidth(line2))

        val boxW = maxWidth + padding * 2
        val boxH = totalHeight + padding
        val boxX = BG_WIDTH - boxW - padding
        val boxY = padding

        // 背景半透明黑色矩形
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f)
        g.color = Color.BLACK
        g.fillRoundRect(boxX, boxY, boxW, boxH, 8, 8)

        // 文字
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
        g.color = Color.WHITE
        g.font = fontBold
        g.drawString(line1, boxX + padding, boxY + padding + fmBold.ascent)
        g.font = fontSmall
        g.drawString(line2, boxX + padding, boxY + padding + fmBold.height + lineSpacing + fmSmall.ascent)

        g.composite = origComposite
        g.color = origColor
    }

    private fun drawSlot(g: Graphics2D, item: ItemStack?, x: Int, y: Int) {
        if (item == null || item.type.isAir) return
        val texture = getTexture(item) ?: return

        // 绘制物品纹理 (居中在格子内)
        val offsetX = x + (SLOT_SIZE - ITEM_SIZE) / 2
        val offsetY = y + (SLOT_SIZE - ITEM_SIZE) / 2
        g.drawImage(texture, offsetX, offsetY, ITEM_SIZE, ITEM_SIZE, null)

        // 绘制数量
        if (item.amount > 1) {
            drawQuantity(g, item.amount, x + QTY_OFFSET.first, y + QTY_OFFSET.second)
        }

        // 绘制耐久条
        if (item.type.maxDurability > 0) {
            val dmg = (item.itemMeta as? Damageable)?.damage ?: 0
            if (dmg > 0) {
                drawDurability(g, item.type.maxDurability.toInt(), dmg, x + DUR_OFFSET.first, y + DUR_OFFSET.second)
            }
        }
    }

    // 玩家预览区域 (来自 layout.yml)
    private const val PREVIEW_X = 102
    private const val PREVIEW_Y = 30
    private const val PREVIEW_W = 198
    private const val PREVIEW_H = 283

    private fun drawPlayerPreview(g: Graphics2D, player: Player) {
        val skin = SkinFetcher.fetchSkin(player.name) ?: DefaultPlayerSkinProvider.defaultSkin()
        val preview = try {
            PlayerSkinRenderer.render(skin)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "皮肤渲染失败: ${player.name}", e)
            return
        }
        // 缩放适配预览区域
        val scale = minOf(
            PREVIEW_W.toDouble() / preview.width,
            PREVIEW_H.toDouble() / preview.height
        )
        val drawW = (preview.width * scale).toInt()
        val drawH = (preview.height * scale).toInt()
        val drawX = PREVIEW_X + (PREVIEW_W - drawW) / 2
        val drawY = PREVIEW_Y + (PREVIEW_H - drawH) / 2
        g.drawImage(preview, drawX, drawY, drawW, drawH, null)
    }

    private fun drawQuantity(g: Graphics2D, amount: Int, x: Int, y: Int) {
        g.font = Font("SansSerif", Font.BOLD, 14)
        val fm = g.fontMetrics
        val text = if (amount > 999) "999+" else amount.toString()
        val textW = fm.stringWidth(text)
        val textH = fm.ascent

        // 阴影
        g.color = Color(0, 0, 0, 180)
        g.drawString(text, x - textW + 1, y + textH + 1)
        // 白色文字
        g.color = Color.WHITE
        g.drawString(text, x - textW, y + textH)
    }

    private fun drawDurability(g: Graphics2D, maxDur: Int, damage: Int, x: Int, y: Int) {
        val ratio = 1.0 - damage.toDouble() / maxDur
        val fillWidth = (DUR_WIDTH * ratio).toInt().coerceIn(0, DUR_WIDTH)

        // 背景条
        g.color = Color(0, 0, 0, 120)
        g.fillRect(x, y, DUR_WIDTH, DUR_HEIGHT)

        // 颜色：绿→黄→红
        g.color = when {
            ratio > 0.5 -> Color(0x55FF55)
            ratio > 0.2 -> Color(0xFFFF55)
            else -> Color(0xFF5555)
        }
        g.fillRect(x, y, fillWidth, DUR_HEIGHT)
    }

    private fun getTexture(item: ItemStack): BufferedImage? {
        val key = item.type.key.toString() // e.g. "minecraft:diamond_sword"
        return textureCache.getOrPut(key) { loadTexture(item.type, item) }
    }

    private val missingTextureBlocks = setOf(
        "anvil", "chipped_anvil", "damaged_anvil",
        "chest", "trapped_chest", "ender_chest", "barrel",
        "hopper", "dispenser", "dropper", "brewing_stand",
        "enchanting_table", "stonecutter", "loom",
        "bell", "campfire", "soul_campfire", "torch", "soul_torch",
        "wall_torch", "soul_wall_torch", "redstone_torch", "redstone_wall_torch",
        "candle", "cake", "cake_with_candle",
        "flower_pot", "potted_oak_sapling", "potted_spruce_sapling",
        "potted_birch_sapling", "potted_jungle_sapling", "potted_acacia_sapling",
        "potted_dark_oak_sapling", "potted_fern", "potted_allium",
        "potted_azalea_bush", "potted_rose_bush", "potted_dead_bush",
        "potted_cactus", "potted_bamboo", "potted_crimson_fungus",
        "potted_warped_fungus", "potted_crimson_roots", "potted_warped_roots",
        "potted_brown_mushroom", "potted_red_mushroom", "potted_wither_rose",
        "potted_blue_orchid", "potted_orange_tulip", "potted_pink_tulip",
        "potted_peony", "potted_lily_of_the_valley",
        "head", "skeleton_skull", "wither_skeleton_skull",
        "zombie_head", "creeper_head", "dragon_head",
        "player_head", "player_wall_head",
        "skeleton_wall_skull", "wither_skeleton_wall_skull",
        "zombie_wall_head", "creeper_wall_head", "dragon_wall_head",
        "armor_stand", "item_frame", "painting",
        "sign", "oak_sign", "spruce_sign", "birch_sign", "jungle_sign",
        "acacia_sign", "dark_oak_sign", "crimson_sign", "warped_sign",
        "oak_wall_sign", "spruce_wall_sign", "birch_wall_sign",
        "jungle_wall_sign", "acacia_wall_sign", "dark_oak_wall_sign",
        "crimson_wall_sign", "warped_wall_sign",
        "bed", "white_bed", "orange_bed", "magenta_bed", "light_blue_bed",
        "yellow_bed", "lime_bed", "pink_bed", "gray_bed", "light_gray_bed",
        "cyan_bed", "purple_bed", "blue_bed", "brown_bed", "green_bed",
        "red_bed", "black_bed",
        "white_wall_bed", "orange_wall_bed", "magenta_wall_bed",
        "light_blue_wall_bed", "yellow_wall_bed", "lime_wall_bed",
        "pink_wall_bed", "gray_wall_bed", "light_gray_wall_bed",
        "cyan_wall_bed", "purple_wall_bed", "blue_wall_bed",
        "brown_wall_bed", "green_wall_bed", "red_wall_bed", "black_wall_bed",
        "banner", "white_banner", "orange_banner", "magenta_banner",
        "light_blue_banner", "yellow_banner", "lime_banner", "pink_banner",
        "gray_banner", "light_gray_banner", "cyan_banner", "purple_banner",
        "blue_banner", "brown_banner", "green_banner", "red_banner", "black_banner",
        "white_wall_banner", "orange_wall_banner", "magenta_wall_banner",
        "light_blue_wall_banner", "yellow_wall_banner", "lime_wall_banner",
        "pink_wall_banner", "gray_wall_banner", "light_gray_wall_banner",
        "cyan_wall_banner", "purple_wall_banner", "blue_wall_banner",
        "brown_wall_banner", "green_wall_banner", "red_wall_banner", "black_wall_banner",
        "shulker_box", "white_shulker_box", "orange_shulker_box",
        "magenta_shulker_box", "light_blue_shulker_box", "yellow_shulker_box",
        "lime_shulker_box", "pink_shulker_box", "gray_shulker_box",
        "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box",
        "blue_shulker_box", "brown_shulker_box", "green_shulker_box",
        "red_shulker_box", "black_shulker_box",
        "glow_item_frame", "painting"
    )

    private fun loadTexture(material: Material, item: ItemStack): BufferedImage? {
        val key = material.key.toString()
        val name = key.replace("minecraft:", "")
        val basePath = "inventory/faithful32x/assets/minecraft"

        // 0. 硬编码缺失贴图 → 直接回退
        if (name in missingTextureBlocks) return fallbackTexture

        // 1. 尝试 overrides（优先级最高）
        var img = loadResource("inventory/faithful32x/overrides/items/$key.png")
        if (img != null) return img

        // 2. 尝试 item 贴图
        img = loadResource("$basePath/$name.png")
        if (img != null) return img

        // 3. 方块物品：尝试合成等距3D预览
        if (material.isBlock) {
            val hasTop = loadResource("$basePath/${name}_top.png") != null
            val hasFront = loadResource("$basePath/${name}_front.png") != null
                    || loadResource("$basePath/${name}_side.png") != null

            if (hasTop && hasFront) {
                val top = loadResource("$basePath/${name}_top.png")!!
                val front = loadResource("$basePath/${name}_front.png")
                    ?: loadResource("$basePath/${name}_side.png")!!
                img = renderIsometricBlock(top, front)
            } else {
                img = loadResource("$basePath/${name}.png")
            }
        }

        // 4. 回退纹理
        return img ?: fallbackTexture
    }

    /**
     * 用顶面+正面贴图合成等距3D方块预览。
     */
    private fun renderIsometricBlock(topTex: BufferedImage, sideTex: BufferedImage): BufferedImage {
        val size = 64
        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // 等距参数
            val tw = 22  // 顶面半宽
            val th = 11  // 顶面半高
            val sh = 26  // 侧面高度

            // 居中偏移
            val ox = size / 2
            val oy = 10

            // 顶面 (菱形)
            val topPoly = intArrayOf(
                ox, oy,              // 上
                ox + tw, oy + th,    // 右
                ox, oy + th * 2,     // 下
                ox - tw, oy + th     // 左
            )
            drawFaceTransformed(g, topTex, topPoly)

            // 正面 (左下平行四边形)
            val frontPoly = intArrayOf(
                ox - tw, oy + th,        // 左上
                ox, oy + th * 2,         // 右上
                ox, oy + th * 2 + sh,    // 右下
                ox - tw, oy + th + sh    // 左下
            )
            drawFaceTransformed(g, sideTex, frontPoly)

            // 右侧面 (右下平行四边形)
            val rightPoly = intArrayOf(
                ox, oy + th * 2,          // 左上
                ox + tw, oy + th,         // 右上
                ox + tw, oy + th + sh,    // 右下
                ox, oy + th * 2 + sh      // 左下
            )
            drawFaceTransformed(g, sideTex, rightPoly)

        } finally {
        g.dispose()
        }
        return result
    }

    /**
     * 将纹理通过仿射变换绘制到目标四边形。
     */
    private fun drawFaceTransformed(g: Graphics2D, texture: BufferedImage, dst: IntArray) {
        val w = texture.width.toDouble()
        val h = texture.height.toDouble()

        // 4点映射: src(0,0)(w,0)(w,h)(0,h) -> dst 四边形
        // 使用 PerspectiveTransform 通过 Graphics2D.transform
        val dx1 = dst[0].toDouble(); val dy1 = dst[1].toDouble()
        val dx2 = dst[2].toDouble(); val dy2 = dst[3].toDouble()
        val dx3 = dst[4].toDouble(); val dy3 = dst[5].toDouble()
        val dx4 = dst[6].toDouble(); val dy4 = dst[7].toDouble()

        // 用平移+剪切+缩放映射: 先算 3 点确定仿射
        val m00 = (dx2 - dx1) / w
        val m10 = (dy2 - dy1) / w
        val m01 = (dx4 - dx1) / h
        val m11 = (dy4 - dy1) / h
        val m02 = dx1
        val m12 = dy1

        val tx = java.awt.geom.AffineTransform(m00, m10, m01, m11, m02, m12)
        g.drawImage(texture, tx, null)
    }

    private fun loadResource(path: String): BufferedImage? {
        return try {
            val stream: InputStream? = InventoryRenderer::class.java.classLoader.getResourceAsStream(path)
            stream?.use {
                val img = ImageIO.read(it) ?: return null
                // 动画贴图：高度>宽度时只取第一帧
                if (img.height > img.width && img.height > 16) {
                    img.getSubimage(0, 0, img.width, img.width)
                } else img
            }
        } catch (_: Exception) {
            null
        }
    }
}
