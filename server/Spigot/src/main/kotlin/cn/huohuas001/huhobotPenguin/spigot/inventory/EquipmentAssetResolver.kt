package cn.huohuas001.huhobotPenguin.spigot.inventory

import com.alibaba.fastjson.JSONObject
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

class EquipmentAssetResolver {
    private val root = "inventory/armor-assets/"
    private val leatherItemKeys = setOf("leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots")
    private val images = ConcurrentHashMap<String, BufferedImage>()
    private val layers = ConcurrentHashMap<String, List<BufferedImage>>()
    private val trims = ConcurrentHashMap<String, BufferedImage>()
    private val leatherItems = ConcurrentHashMap<String, BufferedImage>()

    fun resolveLeatherItemTexture(itemKey: String, color: Int): BufferedImage {
        require(itemKey in leatherItemKeys)
        val cacheKey = "$itemKey|${color and 0xffffff}"
        return leatherItems.computeIfAbsent(cacheKey) {
            val tinted = tint(image("textures/item/$itemKey.png"), color)
            val overlay = image("textures/item/${itemKey}_overlay.png")
            val graphics = tinted.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                graphics.drawImage(overlay, 0, 0, tinted.width, tinted.height, null)
            } finally {
                graphics.dispose()
            }
            tinted
        }
    }

    fun resolveArmorLayers(descriptor: ArmorVisualDescriptor, leggings: Boolean): List<BufferedImage> {
        val layerType = if (leggings) "humanoid_leggings" else "humanoid"
        val cacheKey = "$layerType|${descriptor.equipmentModelKey}|${descriptor.leatherColor}"
        return layers.computeIfAbsent(cacheKey) {
            val model = keyPath(descriptor.equipmentModelKey)
            val entries = json("equipment/$model.json").getJSONObject("layers")
                ?.getJSONArray(layerType) ?: return@computeIfAbsent emptyList()
            (0 until entries.size).map { index ->
                val entry = entries.getJSONObject(index)
                val texture = image("textures/entity/equipment/$layerType/${keyPath(entry.getString("texture"))}.png")
                val dye = entry.getJSONObject("dyeable")
                if (dye == null) texture else tint(
                    texture,
                    descriptor.leatherColor ?: (dye.getIntValue("color_when_undyed") and 0xffffff)
                )
            }
        }
    }

    fun resolveArmorTrimTexture(descriptor: ArmorVisualDescriptor, leggings: Boolean): BufferedImage? {
        if (!descriptor.hasTrim()) return null
        val layerType = if (leggings) "humanoid_leggings" else "humanoid"
        val cacheKey = "$layerType|${descriptor.equipmentModelKey}|${descriptor.trimPatternKey}|${descriptor.trimMaterialKey}"
        return runCatching {
            trims.computeIfAbsent(cacheKey) {
                val pattern = keyPath(json("data/trim_pattern/${keyPath(descriptor.trimPatternKey!!)}.json")
                    .getString("asset_id"))
                val material = json("data/trim_material/${keyPath(descriptor.trimMaterialKey!!)}.json")
                val overrides = material.getJSONObject("override_armor_assets")
                val palette = overrides?.getString(descriptor.equipmentModelKey)
                    ?: material.getString("asset_name")
                require(palette.matches(Regex("[a-z0-9._-]+")))
                applyPalette(
                    image("textures/trims/entity/$layerType/$pattern.png"),
                    image("textures/trims/color_palettes/$palette.png")
                )
            }
        }.getOrNull()
    }

    fun resolveArmorGlintTexture(): BufferedImage = image("textures/misc/enchanted_glint_armor.png")

    private fun json(path: String): JSONObject = resource(path).bufferedReader(Charsets.UTF_8).use {
        JSONObject.parseObject(it.readText()) ?: error("Invalid equipment data: $path")
    }

    private fun image(path: String): BufferedImage = images.computeIfAbsent(path) {
        resource(path).use { input -> ImageIO.read(input) ?: error("Invalid equipment image: $path") }
    }

    private fun resource(path: String) = run {
        require(path.matches(Regex("[a-zA-Z0-9._/-]+")) && !path.contains(".."))
        javaClass.classLoader.getResourceAsStream(root + path)
            ?: error("Missing equipment resource: $path")
    }

    private fun keyPath(key: String): String {
        require(key.matches(Regex("[a-z0-9._-]+:[a-z0-9._/-]+")) && !key.contains(".."))
        return key.substringAfter(':')
    }

    private fun tint(source: BufferedImage, color: Int): BufferedImage {
        val output = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val pixel = source.getRGB(x, y)
            val red = ((pixel ushr 16) and 0xff) * ((color ushr 16) and 0xff) / 255
            val green = ((pixel ushr 8) and 0xff) * ((color ushr 8) and 0xff) / 255
            val blue = (pixel and 0xff) * (color and 0xff) / 255
            output.setRGB(x, y, (pixel and -0x1000000) or (red shl 16) or (green shl 8) or blue)
        }
        return output
    }

    private fun applyPalette(source: BufferedImage, targetPalette: BufferedImage): BufferedImage {
        val sourcePalette = image("textures/trims/color_palettes/trim_palette.png")
        val colors = HashMap<Int, Int>()
        val count = minOf(sourcePalette.width * sourcePalette.height, targetPalette.width * targetPalette.height)
        for (index in 0 until count) {
            val x = index % sourcePalette.width
            val y = index / sourcePalette.width
            val sourceColor = if (sourcePalette.raster.numBands == 1) {
                val sample = sourcePalette.raster.getSample(x, y, 0)
                (sample shl 16) or (sample shl 8) or sample
            } else sourcePalette.getRGB(x, y) and 0xffffff
            colors[sourceColor] = targetPalette.getRGB(index % targetPalette.width, index / targetPalette.width) and 0xffffff
        }
        val output = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val pixel = source.getRGB(x, y)
            output.setRGB(x, y, (pixel and -0x1000000) or (colors[pixel and 0xffffff] ?: (pixel and 0xffffff)))
        }
        return output
    }
}
