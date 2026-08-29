package cn.huohuas001.huhobotPenguin.spigot.inventory

import java.awt.image.BufferedImage
import java.util.Locale
import java.util.Objects

/** 验证后的本地皮肤像素 + 不可变的内容派生缓存键。 */
data class PlayerSkin(
    val image: BufferedImage,
    val cacheKey: String,
    val source: String,
    val slim: Boolean
) {
    init {
        require(image.width == 64 && (image.height == 64 || image.height == 32)) {
            "Minecraft 皮肤必须是 64x64 或 64x32"
        }
    }

    companion object {
        fun safeKey(value: String): String {
            val normalized = value.trim().lowercase(Locale.ROOT)
            require(normalized.matches(Regex("[a-z0-9._\\-]{1,128}"))) { "不安全的缓存键" }
            return normalized
        }
    }
}
