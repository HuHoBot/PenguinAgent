package cn.huohuas001.huhobotPenguin.spigot.inventory

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.SkullMeta
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

/**
 * Agent 内置背包渲染管线。
 *
 * Faithful 只负责物品纹理；背景、圆角遮罩、格子卡片和人物预览均由程序独立合成。
 * 用户壁纸始终位于最底层，不会影响物品格子和人物模型的可读性。
 */
object InventoryRenderer {
    private val logger = Logger.getLogger("InventoryRenderer")

    private const val RESOURCE_ROOT = "inventory/faithful32x"
    private const val MAX_BACKGROUND_BYTES = 16L * 1024L * 1024L
    private const val MAX_BACKGROUND_PIXELS = 32L * 1024L * 1024L

    private const val INVENTORY_WIDTH = 1359
    private const val INVENTORY_HEIGHT = 1017
    private const val SLOT_SIZE = 104
    private const val ITEM_SIZE = 96
    private const val STORAGE_X = 151
    private const val STORAGE_Y = 485
    private const val STORAGE_STEP_X = 119
    private const val STORAGE_STEP_Y = 114
    private const val HOTBAR_Y = 861
    private const val PREVIEW_X = 537
    private const val PREVIEW_Y = 80
    private const val PREVIEW_WIDTH = 272
    private const val PREVIEW_HEIGHT = 373

    private const val ENDER_WIDTH = 1620
    private const val ENDER_HEIGHT = 694
    private const val ENDER_SLOT_SIZE = 128
    private const val ENDER_ITEM_SIZE = 112
    private const val ENDER_X = 175
    private const val ENDER_Y = 138
    private const val ENDER_STEP_X = 143
    private const val ENDER_STEP_Y = 149

    private val armorPositions = mapOf(
        "head" to Rectangle(399, 143, SLOT_SIZE, SLOT_SIZE),
        "chest" to Rectangle(399, 286, SLOT_SIZE, SLOT_SIZE),
        "legs" to Rectangle(844, 143, SLOT_SIZE, SLOT_SIZE),
        "feet" to Rectangle(844, 286, SLOT_SIZE, SLOT_SIZE)
    )
    private val offhandPosition = Rectangle(210, 221, SLOT_SIZE, SLOT_SIZE)

    private val cardFill = Color(244, 247, 249, 42)
    private val playerFill = Color(244, 247, 249, 48)
    private val cardEdge = Color(255, 255, 255, 78)
    private val cardInnerEdge = Color(35, 51, 64, 26)

    @Volatile private var inventoryBackground: BufferedImage? = null
    @Volatile private var enderChestBackground: BufferedImage? = null
    private val textureCache = ConcurrentHashMap<String, BufferedImage>()
    private val playerHeadCache = ConcurrentHashMap<String, BufferedImage>()
    private var fallbackTexture: BufferedImage? = null
    private val playerModelRenderer by lazy { PlayerModelRenderer(PREVIEW_WIDTH, PREVIEW_HEIGHT) }
    private val equipmentAssets by lazy { EquipmentAssetResolver() }

    /** 初始化默认壁纸或用户壁纸，并预先合成所有强制遮罩。 */
    fun init(
        dataFolder: File,
        customEnabled: Boolean = false,
        inventoryFile: String = "inventory.png",
        enderChestFile: String = "",
        fit: String = "cover"
    ) {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true")
        }
        textureCache.clear()
        playerHeadCache.clear()
        fallbackTexture = loadResource("$RESOURCE_ROOT/fallback/unknown.png")

        val normalizedFit = fit.trim().lowercase().takeIf { it == "cover" || it == "stretch" }
            ?: run {
                logger.warning("inventory.render.custom-background.fit 只能是 cover 或 stretch，已使用 cover")
                "cover"
            }
        val defaultInventory = loadResource("$RESOURCE_ROOT/default-wallpaper.png")
            ?: solidWallpaper()

        var inventoryWallpaper = defaultInventory
        var enderWallpaper = defaultInventory
        if (customEnabled) {
            val directory = File(dataFolder, "inventory/backgrounds")
            if (!directory.exists() && !directory.mkdirs()) {
                logger.warning("无法创建自定义背包底图目录: ${directory.absolutePath}")
            }
            inventoryWallpaper = loadCustomWallpaper(directory, inventoryFile) ?: defaultInventory
            enderWallpaper = if (enderChestFile.isBlank()) {
                inventoryWallpaper
            } else {
                loadCustomWallpaper(directory, enderChestFile) ?: defaultInventory
            }
        }

        inventoryBackground = composeInventoryBackground(inventoryWallpaper, normalizedFit)
        enderChestBackground = composeEnderChestBackground(enderWallpaper, normalizedFit)
    }

    fun render(snapshot: InventorySnapshot): ByteArray? {
        val background = inventoryBackground ?: return null
        val canvas = copyImage(background)
        val graphics = canvas.createGraphics()
        try {
            configureItemGraphics(graphics)
            drawPlayerPreview(graphics, snapshot)

            drawSlot(graphics, snapshot.armor.getOrNull(0), armorPositions.getValue("head"), ITEM_SIZE)
            drawSlot(graphics, snapshot.armor.getOrNull(1), armorPositions.getValue("chest"), ITEM_SIZE)
            drawSlot(graphics, snapshot.armor.getOrNull(2), armorPositions.getValue("legs"), ITEM_SIZE)
            drawSlot(graphics, snapshot.armor.getOrNull(3), armorPositions.getValue("feet"), ITEM_SIZE)
            drawSlot(graphics, snapshot.offhand, offhandPosition, ITEM_SIZE)

            val storage = snapshot.storage
            for (index in 9 until 36) {
                val normalized = index - 9
                val bounds = Rectangle(
                    STORAGE_X + normalized % 9 * STORAGE_STEP_X,
                    STORAGE_Y + normalized / 9 * STORAGE_STEP_Y,
                    SLOT_SIZE,
                    SLOT_SIZE
                )
                drawSlot(graphics, storage.getOrNull(index), bounds, ITEM_SIZE)
            }
            for (index in 0 until 9) {
                val bounds = Rectangle(
                    STORAGE_X + index * STORAGE_STEP_X,
                    HOTBAR_Y,
                    SLOT_SIZE,
                    SLOT_SIZE
                )
                drawSlot(graphics, storage.getOrNull(index), bounds, ITEM_SIZE)
            }
        } finally {
            graphics.dispose()
        }
        return encode(canvas)
    }

    fun renderEnderChest(snapshot: InventorySnapshot): ByteArray? {
        val background = enderChestBackground ?: return null
        val canvas = copyImage(background)
        val graphics = canvas.createGraphics()
        try {
            configureItemGraphics(graphics)
            val contents = snapshot.enderChest
            for (index in 0 until 27) {
                val bounds = Rectangle(
                    ENDER_X + index % 9 * ENDER_STEP_X,
                    ENDER_Y + index / 9 * ENDER_STEP_Y,
                    ENDER_SLOT_SIZE,
                    ENDER_SLOT_SIZE
                )
                drawSlot(graphics, contents.getOrNull(index), bounds, ENDER_ITEM_SIZE)
            }
        } finally {
            graphics.dispose()
        }
        return encode(canvas)
    }

    private fun drawPlayerPreview(graphics: Graphics2D, snapshot: InventorySnapshot) {
        val skin = SkinFetcher.fetchSkin(snapshot.playerName, snapshot.playerUuid, snapshot.skinProfile)
            ?: DefaultPlayerSkinProvider.defaultSkin(snapshot.playerUuid, Bukkit.getBukkitVersion())
        try {
            graphics.drawImage(
                playerModelRenderer.render(skin, snapshot.armorVisuals, equipmentAssets), PREVIEW_X, PREVIEW_Y, null
            )
        } catch (error: Exception) {
            logger.log(Level.WARNING, "盔甲模型渲染失败，改用普通模型: ${snapshot.playerName}", error)
            graphics.drawImage(playerModelRenderer.render(skin), PREVIEW_X, PREVIEW_Y, null)
        }
    }

    private fun drawSlot(graphics: Graphics2D, item: ItemStack?, bounds: Rectangle, itemSize: Int) {
        if (item == null || item.type.isAir || item.amount <= 0) return
        val texture = getTexture(item) ?: fallbackTexture ?: return
        val itemX = bounds.x + (bounds.width - itemSize) / 2
        val itemY = bounds.y + (bounds.height - itemSize) / 2
        graphics.composite = AlphaComposite.SrcOver
        graphics.drawImage(texture, itemX, itemY, itemSize, itemSize, null)

        if (item.enchantments.isNotEmpty()) {
            graphics.color = Color(130, 95, 255, 150)
            graphics.stroke = BasicStroke(3f)
            graphics.drawRoundRect(itemX + 1, itemY + 1, itemSize - 2, itemSize - 2, 8, 8)
        }
        if (item.amount > 1) drawAmount(graphics, bounds, item.amount)
        val maxDamage = item.type.maxDurability.toInt()
        val damage = (item.itemMeta as? Damageable)?.damage ?: 0
        if (maxDamage > 0 && damage > 0) drawDurability(graphics, bounds, maxDamage, damage)
    }

    private fun drawAmount(graphics: Graphics2D, bounds: Rectangle, amount: Int) {
        val text = amount.toString()
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
        val metrics = graphics.fontMetrics
        val x = bounds.x + bounds.width - 4 - metrics.stringWidth(text)
        val y = bounds.y + bounds.height - 4
        graphics.color = Color(0, 0, 0, 210)
        graphics.drawString(text, x + 2, y + 2)
        graphics.color = Color.WHITE
        graphics.drawString(text, x, y)
    }

    private fun drawDurability(
        graphics: Graphics2D,
        bounds: Rectangle,
        maxDamage: Int,
        damage: Int
    ) {
        val remaining = (1.0 - damage.toDouble() / maxDamage.toDouble()).coerceIn(0.0, 1.0)
        val x = bounds.x + 12
        val y = bounds.y + 96
        val width = 80
        graphics.color = Color(18, 18, 18, 230)
        graphics.fillRect(x, y, width, 6)
        graphics.color = if (remaining > 0.5) Color(73, 214, 112) else Color(238, 177, 47)
        graphics.fillRect(x, y, (width * remaining).toInt(), 6)
    }

    private fun getTexture(item: ItemStack): BufferedImage? {
        dynamicPlayerHead(item)?.let { return it }
        if (item.type == Material.LEATHER_HELMET || item.type == Material.LEATHER_CHESTPLATE ||
            item.type == Material.LEATHER_LEGGINGS || item.type == Material.LEATHER_BOOTS
        ) {
            val color = (item.itemMeta as? LeatherArmorMeta)?.color?.asRGB()
                ?: Bukkit.getItemFactory().defaultLeatherColor.asRGB()
            runCatching { equipmentAssets.resolveLeatherItemTexture(item.type.key.key, color) }
                .onFailure { logger.log(Level.WARNING, "皮革物品贴图渲染失败: ${item.type}", it) }
                .getOrNull()?.let { return it }
        }
        val key = item.type.key.toString()
        textureCache[key]?.let { return it }
        val loaded = loadTexture(item.type) ?: fallbackTexture ?: return null
        return textureCache.putIfAbsent(key, loaded) ?: loaded
    }

    private fun dynamicPlayerHead(item: ItemStack): BufferedImage? {
        if (item.type != Material.PLAYER_HEAD) return null
        val meta = item.itemMeta as? SkullMeta ?: return null
        val owner = meta.owningPlayer
        val ownerName = owner?.name ?: meta.owner ?: return null
        val profile = sequenceOf("getOwnerProfile", "getPlayerProfile")
            .mapNotNull { method -> runCatching { meta.javaClass.getMethod(method).invoke(meta) }.getOrNull() }
            .firstOrNull()
        val skin = SkinFetcher.fetchSkin(
            ownerName, owner?.uniqueId, ServerSkinProfile.fromProfile(profile)
        ) ?: return null
        return playerHeadCache.computeIfAbsent(skin.cacheKey) { renderPlayerHead(skin.image) }
    }

    private fun loadTexture(material: Material): BufferedImage? {
        val name = material.key.key
        loadResource("$RESOURCE_ROOT/special-variants/minecraft/$name.png")?.let { return it }
        loadResource("$RESOURCE_ROOT/overrides/items/minecraft/$name.png")?.let { return it }

        val direct = loadResource("$RESOURCE_ROOT/assets/minecraft/$name.png")
        if (material.isBlock && material.isSolid) {
            val top = loadResource("$RESOURCE_ROOT/assets/minecraft/${name}_top.png") ?: direct
            val side = loadResource("$RESOURCE_ROOT/assets/minecraft/${name}_side.png")
                ?: loadResource("$RESOURCE_ROOT/assets/minecraft/${name}_front.png")
                ?: direct
            if (top != null && side != null) return renderIsometricBlock(top, side)
        }
        return direct ?: fallbackTexture
    }

    private fun renderPlayerHead(skin: BufferedImage): BufferedImage {
        val normalized = if (skin.height == 64) skin else BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).also {
            it.createGraphics().run {
                drawImage(skin, 0, 0, null)
                dispose()
            }
        }
        val top = headFace(normalized, 8, 0, 40, 0)
        val front = headFace(normalized, 8, 8, 40, 8)
        val side = headFace(normalized, 0, 8, 32, 8)
        return renderIsometricBlock(top, front, side)
    }

    private fun headFace(
        skin: BufferedImage,
        baseX: Int,
        baseY: Int,
        overlayX: Int,
        overlayY: Int
    ): BufferedImage {
        val face = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        face.createGraphics().run {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            drawImage(skin, 0, 0, 8, 8, baseX, baseY, baseX + 8, baseY + 8, null)
            if (skin.height >= overlayY + 8) {
                drawImage(skin, 0, 0, 8, 8, overlayX, overlayY, overlayX + 8, overlayY + 8, null)
            }
            dispose()
        }
        return face
    }

    private fun renderIsometricBlock(top: BufferedImage, side: BufferedImage): BufferedImage =
        renderIsometricBlock(top, side, side)

    private fun renderIsometricBlock(
        top: BufferedImage,
        front: BufferedImage,
        right: BufferedImage
    ): BufferedImage {
        val result = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val centerX = 32
            val topY = 6
            val halfWidth = 25
            val halfHeight = 13
            val sideHeight = 31
            drawFace(
                graphics,
                top,
                intArrayOf(
                    centerX, topY,
                    centerX + halfWidth, topY + halfHeight,
                    centerX, topY + halfHeight * 2,
                    centerX - halfWidth, topY + halfHeight
                )
            )
            drawFace(
                graphics,
                shade(front, 0.80),
                intArrayOf(
                    centerX - halfWidth, topY + halfHeight,
                    centerX, topY + halfHeight * 2,
                    centerX, topY + halfHeight * 2 + sideHeight,
                    centerX - halfWidth, topY + halfHeight + sideHeight
                )
            )
            drawFace(
                graphics,
                shade(right, 0.68),
                intArrayOf(
                    centerX, topY + halfHeight * 2,
                    centerX + halfWidth, topY + halfHeight,
                    centerX + halfWidth, topY + halfHeight + sideHeight,
                    centerX, topY + halfHeight * 2 + sideHeight
                )
            )
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun drawFace(graphics: Graphics2D, texture: BufferedImage, target: IntArray) {
        val width = texture.width.toDouble()
        val height = texture.height.toDouble()
        val transform = AffineTransform(
            (target[2] - target[0]) / width,
            (target[3] - target[1]) / width,
            (target[6] - target[0]) / height,
            (target[7] - target[1]) / height,
            target[0].toDouble(),
            target[1].toDouble()
        )
        val oldClip = graphics.clip
        graphics.clip = java.awt.Polygon(
            intArrayOf(target[0], target[2], target[4], target[6]),
            intArrayOf(target[1], target[3], target[5], target[7]),
            4
        )
        graphics.drawImage(texture, transform, null)
        graphics.clip = oldClip
    }

    private fun shade(source: BufferedImage, factor: Double): BufferedImage {
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val argb = source.getRGB(x, y)
            val alpha = argb ushr 24
            val red = (((argb ushr 16) and 0xff) * factor).toInt().coerceAtMost(255)
            val green = (((argb ushr 8) and 0xff) * factor).toInt().coerceAtMost(255)
            val blue = ((argb and 0xff) * factor).toInt().coerceAtMost(255)
            result.setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
        }
        return result
    }

    private fun composeInventoryBackground(wallpaper: BufferedImage, fit: String): BufferedImage {
        val result = wallpaper(wallpaper, INVENTORY_WIDTH, INVENTORY_HEIGHT, fit)
        val graphics = result.createGraphics()
        try {
            configureBackgroundGraphics(graphics)
            for (index in 0 until 27) {
                drawSelectionCard(
                    graphics,
                    Rectangle(
                        STORAGE_X + index % 9 * STORAGE_STEP_X + 2,
                        STORAGE_Y + index / 9 * STORAGE_STEP_Y + 2,
                        SLOT_SIZE - 4,
                        SLOT_SIZE - 4
                    ),
                    cardFill
                )
            }
            for (index in 0 until 9) {
                drawSelectionCard(
                    graphics,
                    Rectangle(STORAGE_X + index * STORAGE_STEP_X + 2, HOTBAR_Y + 2, SLOT_SIZE - 4, SLOT_SIZE - 4),
                    cardFill
                )
            }
            armorPositions.values.forEach { drawSelectionCard(graphics, inset(it, 2), cardFill) }
            drawSelectionCard(graphics, inset(offhandPosition, 2), cardFill)
            drawSelectionCard(
                graphics,
                Rectangle(PREVIEW_X, PREVIEW_Y, PREVIEW_WIDTH, PREVIEW_HEIGHT),
                playerFill,
                14
            )
            drawOuterFrame(graphics, INVENTORY_WIDTH, INVENTORY_HEIGHT)
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun composeEnderChestBackground(wallpaper: BufferedImage, fit: String): BufferedImage {
        val result = wallpaper(wallpaper, ENDER_WIDTH, ENDER_HEIGHT, fit)
        val graphics = result.createGraphics()
        try {
            configureBackgroundGraphics(graphics)
            for (index in 0 until 27) {
                drawSelectionCard(
                    graphics,
                    Rectangle(
                        ENDER_X + index % 9 * ENDER_STEP_X + 2,
                        ENDER_Y + index / 9 * ENDER_STEP_Y + 2,
                        ENDER_SLOT_SIZE - 4,
                        ENDER_SLOT_SIZE - 4
                    ),
                    cardFill
                )
            }
            drawOuterFrame(graphics, ENDER_WIDTH, ENDER_HEIGHT)
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun wallpaper(source: BufferedImage, width: Int, height: Int, fit: String): BufferedImage {
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            configureBackgroundGraphics(graphics)
            val clip: Shape = RoundRectangle2D.Float(2f, 2f, width - 4f, height - 4f, 60f, 60f)
            graphics.clip(clip)
            if (fit == "stretch") {
                graphics.drawImage(source, 0, 0, width, height, null)
            } else {
                val scale = max(width.toDouble() / source.width, height.toDouble() / source.height)
                val scaledWidth = max(1, ceil(source.width * scale).toInt())
                val scaledHeight = max(1, ceil(source.height * scale).toInt())
                graphics.drawImage(
                    source,
                    (width - scaledWidth) / 2,
                    (height - scaledHeight) / 2,
                    scaledWidth,
                    scaledHeight,
                    null
                )
            }
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun drawSelectionCard(
        graphics: Graphics2D,
        bounds: Rectangle,
        fill: Color,
        arc: Int = 10
    ) {
        val originalPaint = graphics.paint
        graphics.paint = GradientPaint(
            0f, bounds.y.toFloat(), Color(244, 247, 249, (fill.alpha * 1.15).toInt()),
            0f, (bounds.y + bounds.height).toFloat(), Color(214, 221, 226, (fill.alpha * 0.65).toInt())
        )
        graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc)
        graphics.paint = originalPaint
        graphics.stroke = BasicStroke(1.2f)
        graphics.color = cardEdge
        graphics.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, arc, arc)
        graphics.stroke = BasicStroke(1f)
        graphics.color = cardInnerEdge
        graphics.drawRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 5, bounds.height - 5, arc, arc)
    }

    private fun drawOuterFrame(graphics: Graphics2D, width: Int, height: Int) {
        graphics.stroke = BasicStroke(4f)
        graphics.color = Color(245, 252, 253, 210)
        graphics.draw(RoundRectangle2D.Float(2f, 2f, width - 5f, height - 5f, 60f, 60f))
        graphics.stroke = BasicStroke(1f)
        graphics.color = Color(20, 35, 47, 210)
        graphics.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 2f, height - 2f, 62f, 62f))
    }

    private fun loadCustomWallpaper(directory: File, fileName: String): BufferedImage? {
        val safeName = fileName.trim()
        if (!safeName.matches(Regex("[A-Za-z0-9._-]+\\.png"))) {
            logger.warning("忽略不安全的自定义底图文件名: $fileName")
            return null
        }
        val root = directory.canonicalFile
        val file = File(root, safeName).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
            logger.warning("找不到自定义背包底图: ${file.absolutePath}")
            return null
        }
        return try {
            if (file.length() > MAX_BACKGROUND_BYTES) {
                logger.warning("自定义背包底图超过 16 MiB: ${file.absolutePath}")
                null
            } else {
                val image = ImageIO.read(file)
                val pixels = if (image == null) Long.MAX_VALUE else image.width.toLong() * image.height.toLong()
                if (image == null || image.width < 1 || image.height < 1 || pixels > MAX_BACKGROUND_PIXELS) {
                    logger.warning("自定义背包底图无法读取或尺寸不安全: ${file.absolutePath}")
                    null
                } else image
            }
        } catch (error: Exception) {
            logger.log(Level.WARNING, "读取自定义背包底图失败: ${file.absolutePath}", error)
            null
        }
    }

    private fun loadResource(path: String): BufferedImage? = try {
        val stream: InputStream? = InventoryRenderer::class.java.classLoader.getResourceAsStream(path)
        stream?.use {
            val image = ImageIO.read(it) ?: return null
            if (image.height > image.width && image.height > 16) {
                image.getSubimage(0, 0, image.width, image.width)
            } else image
        }
    } catch (_: Exception) {
        null
    }

    private fun solidWallpaper(): BufferedImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).also {
        it.setRGB(0, 0, Color(45, 58, 72).rgb)
    }

    private fun copyImage(source: BufferedImage): BufferedImage =
        BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB).also { target ->
            target.createGraphics().run {
                drawImage(source, 0, 0, null)
                dispose()
            }
        }

    private fun encode(image: BufferedImage): ByteArray? = try {
        drawWatermark(image)
        ByteArrayOutputStream(256 * 1024).use { output ->
            if (!ImageIO.write(image, "PNG", output)) null else output.toByteArray()
        }
    } catch (error: Exception) {
        logger.log(Level.WARNING, "背包图片编码失败", error)
        null
    }

    private fun drawWatermark(image: BufferedImage) {
        val text = "Textures: faithfulpack.net"
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
            val metrics = graphics.fontMetrics
            val x = image.width - metrics.stringWidth(text) - 16
            val y = image.height - 16
            graphics.composite = AlphaComposite.SrcOver.derive(0.45f)
            graphics.color = Color(255, 255, 255)
            graphics.drawString(text, x, y)
            graphics.composite = AlphaComposite.SrcOver
        } finally {
            graphics.dispose()
        }
    }

    private fun configureItemGraphics(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    private fun configureBackgroundGraphics(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }

    private fun inset(bounds: Rectangle, amount: Int): Rectangle = Rectangle(
        bounds.x + amount,
        bounds.y + amount,
        bounds.width - amount * 2,
        bounds.height - amount * 2
    )
}
