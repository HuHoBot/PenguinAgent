package agentcheck

import agent.spigot.onlinelist.*
import agent.spigot.manager.FontManager
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import kotlin.test.*

class PlayerIdentityRasterTest {
    @Test fun longTitlesDoNotDisplaceOrSplitTheIdentityLine() {
        if(!FontManager.isBundledReady())FontManager.initialize(Files.createTempDirectory("identity-fonts").toFile())
        val cache=AvatarCache(Executor { it.run() },500,500,16,false)
        try {
            val renderer=OnlineListRenderer(cache,columns=3)
            val plain=OnlineListPlayer("LongPlayerName16","fixture",null,false,pingMs=30)
            val decorated=plain.copy(prefix="<gold>[特别特别长的自定义称号，不能挤掉玩家身份]</gold>",suffix=" <aqua>建设者与冒险家</aqua>")
            fun render(p:OnlineListPlayer)=ImageIO.read(renderer.render(ServerSnapshot("Test",100,1,listOf(p),capturedAt=1763790000000L)).inputStream())
            val a=render(plain);val b=render(decorated)
            var differences=0
            for(y in 65 until 87)for(x in 81 until 292)if(a.getRGB(x,y)!=b.getRGB(x,y))differences++
            assertEquals(0,differences,"adding a long title must not alter the identity row")
        } finally { cache.close() }
    }
}
