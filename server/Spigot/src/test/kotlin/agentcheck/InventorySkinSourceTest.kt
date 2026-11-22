package agentcheck

import agent.spigot.onlinelist.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class InventorySkinSourceTest {
    @Test fun inventorySkipsAvatarOnlySourcesAndUsesTheSameFullSkinChain() {
        val seen = mutableListOf<String>()
        val image = BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().also { it.color=Color.GREEN;it.fillRect(0,0,128,128);it.dispose() }
        val png = ByteArrayOutputStream().also { ImageIO.write(image,"png",it) }.toByteArray()
        val sources = SkinSources(listOf(
            mapOf("type" to "avatar", "url" to "https://fixture.example/avatar"),
            mapOf("type" to "csl", "url" to "https://fixture.example/csl/{name}.json", "root" to "https://fixture.example")
        ))
        try {
            val skin = sources.findFullSkin(OnlineListPlayer("Astra","u",null,false)) { url ->
                seen += url
                if (url.contains("/csl/")) "{\"skins\":{\"slim\":\"${"a".repeat(32)}\"}}".toByteArray() else png
            }
            assertNotNull(skin)
            assertTrue(skin.slim)
            assertEquals(64,skin.image.width)
            assertEquals(64,skin.image.height)
            assertEquals(Color.GREEN.rgb,skin.image.getRGB(30,30))
            assertFalse(seen.any { it.endsWith("/avatar") },"An avatar is not a body skin")
        } finally { sources.close() }
    }

    @Test fun normalisationAcceptsLegacyAndHdSkinsButRejectsHeadsAndWrongRatios() {
        for ((w,h) in listOf(64 to 32,64 to 64,128 to 128,256 to 128)) {
            val image = SkinSources.normaliseFullSkin(BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB))
            assertNotNull(image)
            assertEquals(64,image.width)
            assertEquals(if (h == w) 64 else 32,image.height)
        }
        for ((w,h) in listOf(8 to 8,32 to 32,128 to 64+1,64 to 128))
            assertNull(SkinSources.normaliseFullSkin(BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB)))
    }
}
