package agentcheck

import agent.spigot.onlinelist.*
import agent.spigot.manager.ConfigUpgrade
import agent.update.ReleasePolicy
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import kotlin.test.*
import org.bukkit.configuration.file.YamlConfiguration

class ConfigAndRenderTest {
    @BeforeTest fun fonts(){if(!agent.spigot.manager.FontManager.isBundledReady())agent.spigot.manager.FontManager.initialize(Files.createTempDirectory("render-fonts").toFile())}
    private val defaults=javaClass.getResourceAsStream("/config.yml")!!.bufferedReader().use { it.readText() }
    @Test fun misplacedCustomTextMigratesWithoutLoss() {
        val old="""config-version: 14
online-list:
  display:
    custom-lines:
      - '<green>MLBS'
  render:
    custom-lines: []
    theme: dark
  theme: light
  skin:
    connect-timeout-ms: 2000
    read-timeout-ms: 7000
other-plugin:
  keep: true
"""
        val transformed=ConfigUpgrade.transform(old,defaults)
        val c=YamlConfiguration().apply { loadFromString(transformed) }
        assertEquals(listOf("<green>MLBS"),c.getStringList("online-list.custom-lines"))
        assertEquals(listOf("<green>MLBS"),c.getStringList("online-list.display.custom-lines"))
        assertTrue(c.getBoolean("other-plugin.keep"))
        assertTrue(c.contains("online-list.skin.connect-timeout-ms"))
        assertTrue(c.contains("online-list.skin.read-timeout-ms"))
        assertTrue(c.getBoolean("online-list.enabled"))
        assertEquals("dark",c.getString("online-list.render.theme"))
        assertEquals("light",c.getString("online-list.theme"))
        assertEquals(7,c.getMapList("online-list.skin.sources").size)
        assertEquals(19,c.getInt("config-version"))
        assertTrue(transformed.contains("title: '<gradient:#62d6df:#ad9cff>在线列表</gradient>'"))
        assertEquals(transformed,ConfigUpgrade.transform(transformed,defaults))
    }
    @Test fun futureConfigIsNotDowngraded() {
        val raw="config-version: 99\nfuture: true\n"
        assertEquals(raw,ConfigUpgrade.transform(raw,defaults))
    }
    @Test fun defaultYamlHasOnlyOneCustomLinesAndNoOtaOrColourControls() {
        val c=YamlConfiguration().apply { loadFromString(this@ConfigAndRenderTest.defaults) }
        assertEquals(19,c.getInt("config-version"));assertFalse(c.contains("update"))
        assertFalse(c.contains("online-list.display.name-color"));assertFalse(c.contains("online-list.theme"))
        assertTrue(c.isList("online-list.custom-lines"))
    }
    @Test fun futureSchemaBypassesTextLevelDuplicateCleanupPath() {
        val raw="config-version: 88\nonline-list:\n  theme: future\n"
        assertEquals(raw,ConfigUpgrade.transform(raw,defaults))
    }
    @Test fun semanticPolicyDoesNotLoopOrDowngrade() {
        assertEquals(0,ReleasePolicy.compare("1.23","1.23.0"))
        assertEquals(1,ReleasePolicy.compare("1.24.0","1.23.9"))
        assertEquals(-1,ReleasePolicy.compare("1.24.0-rc.1","1.24.0"))
        assertFalse(ReleasePolicy.needs("1.24.0","1.23.0","1.23.0"))
        assertFalse(ReleasePolicy.needs("1.23.1","1.23.1","1.23.1"))
        assertTrue(ReleasePolicy.needs("1.23.0","1.23.1","1.23.1"))
        assertNull(ReleasePolicy.compare("<script>","1.23"))
    }
    @Test fun prefixColourFlowsIntoNameAndGradientSurvives() {
        val parsed=McTextParser.parse("&6[VIP]&7Alex",Color.BLACK)
        assertEquals(Color(170,170,170),parsed.last().color)
        val gradient=McTextParser.parse("<gradient:#ff0000:#0000ff>ABCDE</gradient>")
        assertEquals("ABCDE",gradient.joinToString(""){it.text})
        assertTrue(gradient.map { it.color }.distinct().size>2)
    }
    @Test fun adventureDisplayNameKeepsRichStyleAcrossThePluginClassloader() {
        val source=Component.text("Alex").color(NamedTextColor.GRAY).decorate(TextDecoration.BOLD)
        val formatted=NameFormat.serverComponent(source)
        assertNotNull(formatted)
        val segment=McTextParser.parse(formatted).single()
        assertEquals("Alex",segment.text)
        assertEquals(Color(170,170,170),segment.color)
        assertTrue(segment.bold)
    }
    @Test fun defaultServerTabNameDoesNotSuppressLuckPermsPrefix() {
        assertFalse(NameFormat.meaningfulDisplayOverride("Alex","Alex"))
        assertTrue(NameFormat.meaningfulDisplayOverride("&6[VIP]&7Alex","Alex"))
        assertTrue(NameFormat.meaningfulDisplayOverride("<red>Alex</red>","Alex"))
    }
    @Test fun highDefinitionSkinUsesScaledHeadCoordinates() {
        val skin=BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB)
        val g=skin.createGraphics();g.color=Color.MAGENTA;g.fillRect(16,16,16,16);g.dispose()
        val cache=AvatarCache(Executor { it.run() },100,100,8,false)
        assertEquals(Color.MAGENTA.rgb,cache.cropHead(skin).getRGB(64,64))
    }
    @Test fun componentBase64SelectsSkinNotCape() {
        val raw="""{"textures":{"CAPE":{"url":"https://example.com/cape"},"SKIN":{"url":"https://example.com/skin"}}}"""
        val b64=java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
        assertEquals("https://example.com/skin",SkinResolver.textureUrl(b64))
    }
    @Test fun cslSourceUsesActualTextureAndDoesNotNeedLiveNetwork() {
        val skin=BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB)
        val out=java.io.ByteArrayOutputStream();ImageIO.write(skin,"png",out)
        val chain=SkinSources(listOf(mapOf("type" to "csl","url" to "https://example.com/csl/{name}.json","root" to "https://example.com")))
        val called=mutableListOf<String>()
        val result=chain.find(OnlineListPlayer("Alex","u",null,false)) { url ->
            called+=url
            if(url.endsWith(".json")) """{"skins":{"slim":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}""".toByteArray() else out.toByteArray()
        }
        assertNotNull(result);assertFalse(result.second)
        assertEquals("https://example.com/textures/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",called.last())
    }
    @Test fun paginationPreservesAllInformation() {
        val snapshot=ServerSnapshot("Test",20,2,listOf(OnlineListPlayer("a","1",null,false),OnlineListPlayer("b","2",null,false)),
            motd="<red>A\nB",serverIp="mc.example.com",tps=19.8,protocolVersion="774")
        val page=OnlineListPages.page(snapshot,2,1)
        assertEquals(snapshot.motd,page.motd);assertEquals(19.8,page.tps);assertEquals("774",page.protocolVersion)
        assertEquals(2,page.currentPage);assertEquals(2,page.totalPages)
    }
    @Test fun timelineRendersSevenPreciseTicksAndKeepsChronology() {
        val base=System.currentTimeMillis()-48*3600000L
        val points=(0..96).map { i -> (base+i*1800000L) to (i%19) }
        val renderer=OnlineListRenderer(AvatarCache(Executor{it.run()},100,100,8,false))
        val ticks=renderer.trendTimeLabels(points,7)
        assertEquals(7,ticks.size)
        assertEquals(0.0,ticks.first().first)
        assertEquals(1.0,ticks.last().first)
        assertTrue(ticks.all { it.second.matches(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}")) })
        assertEquals(ticks.map { it.first }.sorted(),ticks.map { it.first })
    }
    @Test fun adminsAndTitledCardsHaveDistinctStatusAndLatencySizes() {
        val renderer=OnlineListRenderer(AvatarCache(Executor{it.run()},100,100,8,false))
        assertEquals("ADMIN",renderer.playerStatus(OnlineListPlayer("Root","r",null,true)))
        assertEquals("ONLINE",renderer.playerStatus(OnlineListPlayer("Guest","g",null,false)))
        assertTrue(renderer.latencySize()>renderer.statusSize())
        assertTrue(renderer.latencySize()<20)
    }
    private fun variedPlayers(count:Int,seed:Long):List<OnlineListPlayer> {
        val random=java.util.Random(seed)
        val base=listOf("Astra","Bramble","Cinder","Dahlia","Ember","Fable","Gale","Hollis","Indigo","Juno",
            "Kestrel","Lumen","Morrow","Nettle","Orion","Piper","Quill","Riven","Sable","Tarin","Umber",
            "Vale","Wren","Xanthe","Yarrow","Zephyr","Alto","Beryl","Cobalt","Dune","Echo","Flint")
        val titles=listOf("<gold>[VIP]</gold>&7","<#57c7d4>[Guide]</#57c7d4>&7","&d[Builder]&7",
            "<gradient:#d59c32:#e66f9b>[MVP]</gradient>&7","&b[MOD]&7","<#9c76ed>[Artist]</#9c76ed>&7")
        val suffixes=listOf("","&7"," <gray>✦</gray>","<aqua>+</aqua>"," &8[AFK]")
        return (0 until count).map { i ->
            val raw=base[i%base.size]+if(i>=base.size)"${i/base.size}"else ""
            val chosen=if(random.nextInt(100)<46)titles[random.nextInt(titles.size)]else ""
            val admin=random.nextInt(100)<13
            val style=if(random.nextBoolean())"" else listOf("<#ff805d>","<gradient:#68d0cb:#8772d3>","&f")[random.nextInt(3)]
            val close=when(style){"<#ff805d>"->"</#ff805d>";"<gradient:#68d0cb:#8772d3>"->"</gradient>";else->""}
            val shownName=if(random.nextInt(100)<28)chosen+style+raw+close+suffixes[random.nextInt(suffixes.size)]else ""
            val ping=when(i%5){0->-1;1->random.nextInt(80);2->80+random.nextInt(120);else->200+random.nextInt(601)}
            OnlineListPlayer(raw,"fixture-${seed}-$i",null,admin,chosen,(if(random.nextInt(100)<28)suffixes[random.nextInt(suffixes.size)]else ""),"",ping,shownName)
        }
    }
    @Test fun randomizedFixturesAcrossSeedsPreservePriorityAndAlphabeticSort() {
        for(seed in listOf(1L,42L,773104L,20261120L)) {
            val players=variedPlayers(64,seed)
            val sorted=OnlineListPages.sorted(players)
            val tiers=sorted.map(OnlineListPages::priorityTier)
            assertEquals(tiers.sorted(),tiers)
            sorted.zipWithNext().forEach { (a,b) ->
                if(OnlineListPages.priorityTier(a)==OnlineListPages.priorityTier(b))
                    assertTrue(OnlineListPages.initialKey(a.name)<=OnlineListPages.initialKey(b.name))
            }
            assertEquals(64,sorted.map { it.uuid }.distinct().size)
            assertTrue(sorted.map { it.prefix }.distinct().size>=4)
            assertTrue(sorted.map { it.pingMs }.distinct().size>20)
        }
    }
    @Test fun renderingMatrixUsesRealRendererAndNeverOverflowsGeometry() {
        val dir=java.io.File(System.getProperty("user.dir"),"build/visual-checks").apply { mkdirs() }
        agent.spigot.manager.BundledFonts.open("HuHoCJK.otf")?.use {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,it))
        }
        OnlineHistoryStore.configure(Files.createTempDirectory("render-history").toFile())
        val now=System.currentTimeMillis()
        for(i in 0..96) OnlineHistoryStore.record((10+9*kotlin.math.sin(i*0.2)).toInt().coerceAtLeast(0),now-(96-i)*1800000L)
        for(n in listOf(0,1,3,12,27,60)) {
          for(seed in listOf(7L,773104L,20261120L)) {
            val players=variedPlayers(n,seed).mapIndexed { index,p -> p.copy(skinUrl="https://fixture.example/skin/$index.png") }
            val fixtureSkin=javaClass.getResourceAsStream("/online/head-${listOf("alex","ari","efe","kai","makena","noor","steve","sunny","zuri")[Math.floorMod(seed.toInt(),9)]}.png")!!.use { ImageIO.read(it) }
            val skins=(0..8).associate { index ->
                val face=javaClass.getResourceAsStream("/online/head-${listOf("alex","ari","efe","kai","makena","noor","steve","sunny","zuri")[index]}.png")!!.use { ImageIO.read(it) }
                val skin=BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB)
                skin.createGraphics().also { it.drawImage(face,8,8,8,8,null);it.dispose() }
                index to java.io.ByteArrayOutputStream().also { ImageIO.write(skin,"png",it) }.toByteArray()
            }
            val icon=BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB).apply {
                createGraphics().also { g ->
                    g.paint=java.awt.GradientPaint(0f,0f,Color(27,135,156),64f,64f,Color(117,89,184))
                    g.fillOval(2,2,60,60)
                    g.color=Color.WHITE;g.font=java.awt.Font("SansSerif",java.awt.Font.BOLD,36)
                    g.drawString("M",14,46);g.dispose()
                }
            }
            val s=ServerSnapshot("MLBS 测试服务器",2026,n,OnlineListPages.sorted(players),motd="<red>第一行 <gradient:#247894:#854480>渐变</gradient>\n<blue>第二行 MOTD",icon=icon,serverIp="mc.example.com",brand="Leaf",coreVersion="1.21.11-R0.1-SNAPSHOT-TEST",protocolVersion="774",viaRange="1.8 ~ 1.21.11",tps=19.8,mspt=12.4,memoryUsedMb=123456,memoryMaxMb=1048576)
            val r=OnlineListRenderer(AvatarCache(Executor{it.run()},100,100,128,true,sources=emptyList(),fetcher={url->skins[(url.substringAfterLast('/').substringBefore('.').toIntOrNull()?:0)%9]}),3,"","POWERED BY MLBS",customLines=listOf("<green>服务器: MLBS | 腐竹: MOCG3D"))
            val l=r.layout(s)
            assertEquals(24,l.players.y);assertEquals(24,l.sidebar.y)
            for(b in listOf(l.players,l.sidebar,l.trend,l.metrics,l.footer)) {
                assertTrue(b.x>=0&&b.y>=0);assertTrue(b.x+b.w<=1200);assertTrue(b.y+b.h<=l.height)
            }
            val tiles=r.playerBoxes(s)
            for(tile in tiles){
                val overlaps=tile.x<l.trend.x+l.trend.w&&tile.x+tile.w>l.trend.x&&tile.y<l.trend.y+l.trend.h&&tile.y+tile.h>l.trend.y
                assertFalse(overlaps)
            }
            assertTrue(r.latencyColour(30).green>r.latencyColour(30).red)
            assertTrue(r.latencyColour(400).red>r.latencyColour(400).green)
            val bars=r.performanceFractions(s)
            assertEquals(3,bars.size);assertTrue(bars[0]>.9);assertTrue(bars[1]>.24 && bars[1]<.25);assertTrue(bars[2] in 0.0..1.0)
            val bytes=r.render(s);val image=ImageIO.read(java.io.ByteArrayInputStream(bytes))
            assertEquals(l.height,image.height)
            val iconCentre=Color(image.getRGB(l.sidebar.x+20+8,l.sidebar.y+16+10),true)
            assertTrue(iconCentre.alpha>0 && iconCentre.blue>iconCentre.red,"server icon should be visible in the sidebar")
            val barPixel=Color(image.getRGB(l.metrics.x+18+100,l.metrics.y+64))
            assertTrue(barPixel.green>barPixel.red,"TPS bar should be visibly filled")
            val bgA=Color(image.getRGB(0,0));val bgB=Color(image.getRGB(image.width-1,image.height-1))
            assertTrue(kotlin.math.abs(bgA.blue-bgB.blue)>15 || kotlin.math.abs(bgA.red-bgB.red)>15,"canvas should use a visible gradient")
            java.io.File(dir,"players-$n-$seed.png").writeBytes(bytes)
          }
        }
    }
}
