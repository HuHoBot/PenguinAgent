package agentcheck

import agent.spigot.inventory.SkinFetcher
import agent.spigot.onlinelist.SkinSources
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

class InventorySkinHttpTest {
    @Test fun inventoryFetcherUsesConfiguredCslEndpointAndCachesItsFullSlimSkin() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val base = "http://127.0.0.1:${server.address.port}"
        val count = AtomicInteger()
        val image = BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().also { it.color=Color.CYAN;it.fillRect(0,0,128,128);it.dispose() }
        val png = ByteArrayOutputStream().also { ImageIO.write(image,"png",it) }.toByteArray()
        server.createContext("/csl/") { e ->
            count.incrementAndGet()
            val data = "{\"skins\":{\"slim\":\"${"a".repeat(32)}\"}}".toByteArray()
            e.sendResponseHeaders(200,data.size.toLong());e.responseBody.use { it.write(data) };e.close()
        }
        server.createContext("/textures/") { e ->
            count.incrementAndGet();e.sendResponseHeaders(200,png.size.toLong());e.responseBody.use { it.write(png) };e.close()
        }
        server.start()
        try {
            SkinFetcher.configure(listOf(mapOf("type" to "csl","url" to "$base/csl/{name}.json","root" to base,"allow-private" to "true")),1000,true,8)
            val uuid = UUID(0,123)
            val skin = SkinFetcher.fetchSkin("FixturePlayer",uuid)
            assertNotNull(skin)
            assertEquals("SHARED_SKIN_SOURCES",skin.source)
            assertTrue(skin.slim)
            assertEquals(64,skin.image.width)
            assertEquals(Color.CYAN.rgb,skin.image.getRGB(20,20))
            assertEquals(skin,SkinFetcher.fetchSkin("FixturePlayer",uuid))
            assertEquals(2,count.get(),"second query must not repeat CSL/texture downloads")
        } finally {
            SkinFetcher.close()
            SkinFetcher.configure(SkinSources.defaults(),1000,false,8)
            server.stop(0)
        }
    }
}
