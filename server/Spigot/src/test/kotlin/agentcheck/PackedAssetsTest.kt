package agentcheck

import agent.spigot.manager.BundledFonts
import com.alibaba.fastjson.JSON
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

class PackedAssetsTest {
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun fontsAreUnpackedWithoutChangingAnyOfTheirBytes() {
        val metadata = javaClass.getResourceAsStream("/fonts/FONT-SOURCES.json")!!.bufferedReader().use { it.readText() }
        val records = JSON.parseArray(metadata)
        assertEquals(4, records.size)
        for (index in 0 until records.size) {
            val font = records.getJSONObject(index)
            val name = font.getString("file")
            val original = assertNotNull(BundledFonts.open(name)).use { it.readBytes() }
            assertEquals(font.getIntValue("bytes"), original.size, name)
            assertEquals(font.getString("sha256"), digest(original), name)
            assertNull(javaClass.getResource("/fonts/$name"), "do not ship a duplicate uncompressed font")
        }
    }

    @Test fun losslessArtworkOptimizationPreservesDecodedPixels(){
        val checks=listOf(
            Triple("/online/HuHo.png",1212 to 1298,"0cd873411ee388e3184d1e3dfca9e25e140ccacb23fc78742786205aba66a730"),
            Triple("/inventory/faithful32x/default-wallpaper.png",1359 to 1017,"a731908ec27f7ddd1b295e808a39b20673473658b94843856b6422ead17fd221")
        )
        for((resource,size,hash) in checks){
            val image=assertNotNull(javaClass.getResourceAsStream(resource)).use{ImageIO.read(it)}
            assertEquals(size.first,image.width);assertEquals(size.second,image.height)
            assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(image.convertRgba()).joinToString(""){"%02x".format(it.toInt()and 255)})
        }
    }
    private fun java.awt.image.BufferedImage.convertRgba():ByteArray=ByteArray(width*height*4).also{data->
        var i=0;for(y in 0 until height)for(x in 0 until width){val c=getRGB(x,y);data[i++]=(c ushr 16).toByte();data[i++]=(c ushr 8).toByte();data[i++]=c.toByte();data[i++]=(c ushr 24).toByte()}
    }
    @Test fun mascotStillDecodesWithOriginalDimensionsAndTransparency() {
        val image = assertNotNull(javaClass.getResourceAsStream("/online/HuHo.png")).use { ImageIO.read(it) }
        assertEquals(1212, image.width)
        assertEquals(1298, image.height)
        assertTrue(image.colorModel.hasAlpha())
    }
}
