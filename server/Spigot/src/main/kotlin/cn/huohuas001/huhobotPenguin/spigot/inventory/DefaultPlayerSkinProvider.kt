package cn.huohuas001.huhobotPenguin.spigot.inventory

import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage

/** 程序化生成的回退皮肤；不捆绑 Mojang 或第三方图片。 */
object DefaultPlayerSkinProvider {

    private val fallback by lazy {
        PlayerSkin(createSkin(), "huhobot-default-v1", "LOCAL_DEFAULT", false)
    }

    fun defaultSkin(): PlayerSkin = fallback

    private fun createSkin(): BufferedImage {
        val skin = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = skin.createGraphics()
        try {
            val face = Color(207, 151, 112)
            val hair = Color(58, 36, 31)
            val shirt = Color(43, 145, 151)
            val trousers = Color(48, 55, 92)

            // 头
            fillCuboid(g, 0, 0, 8, 8, 8, face)
            // 身体
            fillCuboid(g, 16, 16, 8, 12, 4, shirt)
            // 右臂
            fillCuboid(g, 40, 16, 4, 12, 4, face)
            // 右臂外层
            fillCuboid(g, 32, 48, 4, 12, 4, face)
            // 左腿
            fillCuboid(g, 0, 16, 4, 12, 4, trousers)
            // 右腿
            fillCuboid(g, 16, 48, 4, 12, 4, trousers)

            // 头发
            g.color = hair
            g.fillRect(8, 8, 8, 3)

            // 眼睛
            g.color = Color(45, 30, 27)
            g.fillRect(9, 11, 2, 1)
            g.fillRect(13, 11, 2, 1)

            // 身体外层（半透明）
            fillCuboid(g, 16, 32, 8, 12, 4, Color(78, 198, 197, 130))
            fillCuboid(g, 40, 32, 4, 12, 4, Color(78, 198, 197, 130))
            fillCuboid(g, 48, 48, 4, 12, 4, Color(78, 198, 197, 130))
        } finally {
            g.dispose()
        }
        return skin
    }

    private fun fillCuboid(g: Graphics2D, u: Int, v: Int, width: Int, height: Int, depth: Int, color: Color) {
        g.color = color
        g.fillRect(u, v, depth * 2 + width * 2, depth + height)
    }
}
