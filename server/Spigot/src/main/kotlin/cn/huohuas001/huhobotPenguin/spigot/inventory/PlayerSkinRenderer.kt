package cn.huohuas001.huhobotPenguin.spigot.inventory

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * 2D 正面视角玩家皮肤渲染器。
 * 5x 缩放，部件紧密拼接，无间隙。
 */
object PlayerSkinRenderer {
    const val WIDTH = 128
    const val HEIGHT = 256
    private const val S = 7 // 每个 MC 像素 = 7 屏幕像素

    data class PartDef(
        val baseX: Int, val baseY: Int,
        val overlayX: Int, val overlayY: Int,
        val srcW: Int, val srcH: Int,
        val dstX: Int, val dstY: Int
    )

    fun render(skin: PlayerSkin): BufferedImage {
        val texture = normalizeLegacy(skin.image)
        val result = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.composite = AlphaComposite.SrcOver

            val armW = if (skin.slim) 3 else 4
            val leftArmBaseX = if (skin.slim) 40 else 36
            val leftArmOvX = if (skin.slim) 48 else 52

            // 布局: 居中于 128x256 画布
            // 头 8x8 → 64x64,  身 8x12 → 64x96,  手臂 armWx12 → armW*8x96,  腿 4x12 → 32x96
            val bodyW = 8 * S           // 64
            val bodyH = 12 * S          // 96
            val headW = 8 * S           // 64
            val headH = 8 * S           // 64
            val legW = 4 * S            // 32
            val legH = 12 * S           // 96
            val armDw = armW * S        // 24 or 32

            val totalH = headH + bodyH + legH  // 256

            val baseX = (WIDTH - bodyW) / 2        // 32
            val baseY = (HEIGHT - totalH) / 2       // 0

            // 各部件 Y 坐标 (紧密排列)
            val headY = baseY
            val bodyY = headY + headH        // headY + 40
            val legY = bodyY + bodyH         // bodyY + 60
            val armY = bodyY                 // 手臂与身体同高

            // 头部居中于身体
            val headX = baseX + (bodyW - headW) / 2  // = baseX

            // 手臂在身体两侧
            val leftArmX = baseX - armDw
            val rightArmX = baseX + bodyW

            // 腿在身体下方, 居中于身体
            val leftLegX = baseX
            val rightLegX = baseX + bodyW - legW

            val parts = listOf(
                // 左腿 (远)
                PartDef(20, 52, 4, 52, 4, 12, leftLegX, legY),
                // 右腿 (远)
                PartDef(4, 20, 4, 36, 4, 12, rightLegX, legY),
                // 左臂 (远)
                PartDef(leftArmBaseX, 52, leftArmOvX, 52, armW, 12, leftArmX, armY),
                // 右臂 (近)
                PartDef(44, 20, 44, 36, armW, 12, rightArmX, armY),
                // 身体
                PartDef(20, 20, 20, 36, 8, 12, baseX, bodyY),
                // 头
                PartDef(8, 8, 40, 8, 8, 8, headX, headY)
            )

            for (p in parts) {
                drawPart(g, texture, p)
            }
        } finally {
            g.dispose()
        }
        return result
    }

    private fun drawPart(g: Graphics2D, texture: BufferedImage, p: PartDef) {
        val dw = p.srcW * S
        val dh = p.srcH * S
        // 基础层
        g.drawImage(
            texture,
            p.dstX, p.dstY, p.dstX + dw, p.dstY + dh,
            p.baseX, p.baseY, p.baseX + p.srcW, p.baseY + p.srcH,
            null
        )
        // 外层
        if (p.overlayY + p.srcH <= texture.height && p.overlayX + p.srcW <= texture.width) {
            val overlay = texture.getSubimage(p.overlayX, p.overlayY, p.srcW, p.srcH)
            if (hasVisiblePixel(overlay)) {
                g.drawImage(
                    texture,
                    p.dstX, p.dstY, p.dstX + dw, p.dstY + dh,
                    p.overlayX, p.overlayY, p.overlayX + p.srcW, p.overlayY + p.srcH,
                    null
                )
            }
        }
    }

    private fun hasVisiblePixel(img: BufferedImage): Boolean {
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if ((img.getRGB(x, y) ushr 24) != 0) return true
            }
        }
        return false
    }

    private fun normalizeLegacy(input: BufferedImage): BufferedImage {
        if (input.height == 64) return input
        val expanded = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = expanded.createGraphics()
        try {
            g.drawImage(input, 0, 0, null)
            g.drawImage(input, 20, 52, 24, 64, 8, 20, 4, 32, null)
            g.drawImage(input, 36, 52, 40, 64, 48, 20, 44, 32, null)
        } finally {
            g.dispose()
        }
        return expanded
    }
}
