package agentcheck

import agent.spigot.onlinelist.*
import agent.spigot.scripting.ModuleDownloads
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*

class SourceAndModuleTest {
    @Test fun vanillaTeamProvidesTitleSuffixAndNameColourWithoutLuckPerms(){
        val team=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(Team::class.java)){_,m,_->when(m.name){"getPrefix"->"[Team]";"getSuffix"->"§d★";"getColor"->ChatColor.BLUE;else->null}}as Team
        val board=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(Scoreboard::class.java)){_,m,_->if(m.name=="getEntryTeam")team else null}as Scoreboard
        val player=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(Player::class.java)){_,m,_->when(m.name){"getName"->"Astra";"getUniqueId"->UUID(0,1);"getScoreboard"->board;"getPlayerListName","getDisplayName"->"Astra";else->null}}as Player
        val meta=NameMeta.read(player)
        assertTrue(meta.prefix.contains("[Team]"));assertTrue(meta.prefix.endsWith("§9"));assertEquals("§d★",meta.suffix)
        val configured=NameMeta.read(player,"<aqua>[Manual]</aqua>&7","")
        assertTrue(configured.explicit);assertTrue(configured.prefix.contains("[Manual]"))
        val unresolved=NameMeta.read(player,"%missing_expansion_prefix%","")
        assertFalse(unresolved.explicit);assertEquals(meta.prefix,unresolved.prefix)
    }
    @Test fun realYggdrasilHttpReadsTexturesOnlyAndPrivateAccessRequiresExplicitTrust(){
        val server=com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val url="http://127.0.0.1:${server.address.port}"
        val image=BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);image.createGraphics().also{it.color=Color.CYAN;it.fillRect(8,8,8,8);it.dispose()}
        val png=ByteArrayOutputStream().also{ImageIO.write(image,"png",it)}.toByteArray()
        val texture=Base64.getEncoder().encodeToString("""{"textures":{"SKIN":{"url":"$url/skin.png"},"CAPE":{"url":"$url/not-skin"}}}""".toByteArray())
        server.createContext("/profile"){e->val json="""{"properties":[{"name":"textures","value":"$texture"}]}""".toByteArray();e.sendResponseHeaders(200,json.size.toLong());e.responseBody.use{it.write(json)}}
        server.createContext("/skin.png"){e->e.sendResponseHeaders(200,png.size.toLong());e.responseBody.use{it.write(png)}}
        server.start()
        try{
            val row=mapOf("type" to "yggdrasil","url" to "$url/profile/{uuid}","allow-private" to "true")
            val allowed=SkinSources(listOf(row),1000,1000)
            val player=OnlineListPlayer("Astra","id",null,false)
            assertNotNull(allowed.find(player));allowed.close()
            val rejected=SkinSources(listOf(row-"allow-private"),1000,1000)
            assertNull(rejected.find(player));rejected.close()
        }finally{server.stop(0)}
    }
    @Test fun fontModulePendingFileIsVerifiedBeforeItIsApplied(){
        val data=Files.createTempDirectory("font-module").toFile();val pending=data.resolve("modules/pending").apply{mkdirs()}
        val bytes=javaClass.getResourceAsStream("/fonts/HuHoSymbols.ttf")!!.use{it.readBytes()}
        val file=pending.resolve("font-test.bin");file.writeBytes(bytes)
        val p=Properties().apply{setProperty("id","font-test");setProperty("version","1.23.3");setProperty("sha256",agent.spigot.scripting.AddonSources.digest(bytes))}
        p.store(pending.resolve("font-test.properties").outputStream(),null)
        val warnings=mutableListOf<String>();ModuleDownloads.applyPending(data){warnings+=it}
        assertTrue(warnings.isEmpty(),warnings.joinToString());assertContentEquals(bytes,data.resolve("fonts/font-test.ttf").readBytes())
        assertFalse(file.exists())
    }
    @Test fun modifiedPendingModuleNeverReplacesExistingFont(){
        val data=Files.createTempDirectory("bad-font-module").toFile();val pending=data.resolve("modules/pending").apply{mkdirs()}
        data.resolve("fonts").mkdir();data.resolve("fonts/font-test.ttf").writeText("keep")
        pending.resolve("font-test.bin").writeText("changed")
        Properties().apply{setProperty("id","font-test");setProperty("version","1.23.3");setProperty("sha256","0".repeat(64))}
            .store(pending.resolve("font-test.properties").outputStream(),null)
        val warnings=mutableListOf<String>();ModuleDownloads.applyPending(data){warnings+=it}
        assertTrue(warnings.isNotEmpty());assertEquals("keep",data.resolve("fonts/font-test.ttf").readText())
    }
}
