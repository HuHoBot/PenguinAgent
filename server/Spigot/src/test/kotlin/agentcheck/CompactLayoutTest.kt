package agentcheck

import agent.spigot.onlinelist.*
import agent.spigot.scripting.*
import org.bukkit.entity.Player
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class CompactLayoutTest {
    @BeforeTest fun fonts(){if(!agent.spigot.manager.FontManager.isBundledReady())agent.spigot.manager.FontManager.initialize(Files.createTempDirectory("compact-fonts").toFile())}
    fun interface TripleCall { fun call(a:String,b:String,c:String):String }
    private fun renderer(lines:List<String> = emptyList())=OnlineListRenderer(AvatarCache(Executor{it.run()},100,100,8,false),customLines=lines)
    private fun snapshot(n:Int=0)=ServerSnapshot("Test",70,n,(0 until n).map { OnlineListPlayer("A$it","$it",null,false) },brand="<gradient:#36b4c7:#9879d0>自定义核心</gradient>",coreVersion="1.21.11",protocolVersion="774",motd="两行\nMOTD")
    private fun overlaps(a:OnlineListRenderer.Box,b:OnlineListRenderer.Box)=a.x<b.x+b.w&&a.x+a.w>b.x&&a.y<b.y+b.h&&a.y+a.h>b.y
    @Test fun omittedCustomLinesReallyPullCoreAndVersionUp(){
        val s=snapshot(2);val blank=renderer();val spaces=renderer(listOf("","  "));val full=renderer(listOf("<aqua>第一条</aqua>","<gray>第二条</gray>"))
        assertEquals(blank.detailsTop(s),spaces.detailsTop(s))
        assertTrue(full.detailsTop(s)>blank.detailsTop(s)+30)
        assertTrue(full.layout(s).sidebar.h>blank.layout(s).sidebar.h)
    }
    @Test fun fixedTrendAndAlignedPlayerGridStayOutsideTheSidebar(){
        for (cols in 1..3) {
            val r=OnlineListRenderer(AvatarCache(Executor { it.run() },100,100,8,false),columns=cols)
            val empty=r.layout(snapshot())
            val few=r.layout(snapshot(1))
            assertEquals(empty.trend,few.trend,"empty and one-player pages reserve the same space")
            assertTrue(empty.players.h>=240,"minimum player area must be reserved")
            for (n in listOf(0,1,3,12,13,27,60)) {
                val s=snapshot(n);val l=r.layout(s);val boxes=r.playerBoxes(s)
                assertEquals(n,boxes.size)
                assertEquals(empty.trend.h,l.trend.h,"trend must not stretch to fill free space")
                assertEquals(l.trend.y,l.metrics.y,"bottom panels share a baseline")
                assertEquals(l.trend.h,l.metrics.h)
                assertTrue(boxes.all { it.x+it.w<=l.sidebar.x && it.h<85 && it.y+it.h<=l.trend.y })
                assertTrue(boxes.chunked(cols).all { row -> row.map { it.y }.distinct().size==1 })
                assertTrue(boxes.zipWithNext().none { (a,b) -> overlaps(a,b) })
                assertTrue(l.trend.y>l.sidebar.y+l.sidebar.h)
            }
        }
    }
    @Test fun onlyAdminsHaveSpecialHighlightAndNoTitleAloneGetsIt(){
        val r=renderer();assertFalse(r.highlighted(OnlineListPlayer("VIP","x",null,false,"&6[VIP]")))
        assertTrue(r.highlighted(OnlineListPlayer("Admin","a",null,true)))
    }
    @Test fun eightMetadataGroupsHaveIndependentAlphabeticOrdering(){
        val rows=(0..7).flatMap { tier -> listOf("Zeta","Alpha").map { name ->
            val local=tier%4
            OnlineListPlayer(name+tier,"$name-$tier",null,tier<4,if(local<2)"<gold>[VIP]</gold>&7"else "",if(local==0||local==3)"&b+"else "")
        } }.reversed()
        val sorted=OnlineListPages.sorted(rows)
        assertEquals((0..7).flatMap { listOf(it,it) },sorted.map(OnlineListPages::priorityTier))
        for(group in sorted.groupBy(OnlineListPages::priorityTier).values)assertTrue(group.first().name.startsWith("Alpha"))
        assertFalse(OnlineListPages.hasTitle(OnlineListPlayer("A","x",null,false,"&7")))
        assertFalse(OnlineListPages.hasSuffix(OnlineListPlayer("A","x",null,false,suffix="<gray></gray>")))
    }
    @Test fun overloadedMsptKeepsVisibleFullBarAndColourTransitionsAreContinuous(){
        val r=renderer();val base=snapshot();val slow=base.copy(tps=8.0,mspt=86.5,memoryUsedMb=990,memoryMaxMb=1024)
        assertEquals(1.0,r.performanceFractions(slow)[1])
        val low=r.metricColour(1,base.copy(mspt=20.0));val middle=r.metricColour(1,base.copy(mspt=45.0));val high=r.metricColour(1,slow)
        assertTrue(high.red>high.green);assertNotEquals(low,middle)
        val a=r.metricColour(1,base.copy(mspt=44.9));val b=r.metricColour(1,base.copy(mspt=45.1))
        assertTrue(kotlin.math.abs(a.red-b.red)<5&&kotlin.math.abs(a.green-b.green)<5)
    }
    @Test fun bundledJsCanExecuteAndAdaptCallbacksWithoutExternalEngineJar(){
        RhinoBridge().use { js ->
            assertEquals(42.0,(js.eval("6*7") as Number).toDouble())
            val fn=js.eval("(function(x){return 'hello '+x;})")
            @Suppress("UNCHECKED_CAST")val f=RhinoBridge.adapt(fn,java.util.function.Function::class.java)as java.util.function.Function<Any,Any>
            assertEquals("hello Astra",f.apply("Astra").toString())
            assertEquals("hello Milo",java.util.concurrent.CompletableFuture.supplyAsync { f.apply("Milo").toString() }.get())
            val multi=js.eval("(function(a,b,c){return a+b+c;})")
            val three=RhinoBridge.adapt(multi,TripleCall::class.java) as TripleCall
            assertEquals("ABC",three.call("A","B","C"))
        }
    }
    @Test fun startupBannerShowsLargeConsistentWordmark(){
        val start=agent.provider.StartupBanner.lines("1.24","Paper",false)
        val reload=agent.provider.StartupBanner.lines("1.24","Paper",true)
        assertEquals(10,start.size);assertEquals(start.dropLast(1),reload.dropLast(1))
        assertTrue(start.dropLast(1).all { it.length==start.first().length })
        assertEquals("HuHoBot Penguin v1.24 · Paper · 启动",start.last())
    }
    @Test fun fontCascadeCoversCjkSupplementaryHanScriptsAndEmoji(){
        val font=agent.spigot.manager.FontManager
        for(character in listOf("中","繁","龘","𠮷","한","あ","Ω","Ж","é","∑","😀","🦋","🚀")){
            val face=font.fontFor(character,"",java.awt.Font.PLAIN,18)
            assertTrue(character.codePoints().allMatch { face.canDisplay(it) },"missing glyph $character in ${face.family}")
        }
    }
    @Test fun javaAddonCompilesOffThreadBeforeMainActivation(){
        val root=Files.createTempDirectory("native-java-addon").toFile();val folder=root.resolve("fixture").apply{mkdir()}
        folder.resolve("Main.java").writeText("public class Main implements agent.spigot.scripting.AddonProgram { public void start(agent.spigot.scripting.BirdScriptApi api) { System.setProperty(\"agent.java.started\",\"yes\"); } }")
        val pkg=ScriptPackage.open(folder,root)!!
        JavaSession.prepare(pkg,"1.24").use { session ->
            assertNull(System.getProperty("agent.java.started"))
            session.start(null)
            assertEquals("yes",System.getProperty("agent.java.started"));session.stopProgram()
        }
        System.clearProperty("agent.java.started")
    }


    private fun zip(name:String,body:String):ByteArray=ByteArrayOutputStream().also { bytes->ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry(name));it.write(body.toByteArray());it.closeEntry() } }.toByteArray()
    @Test fun networkArchiveInstallDoesNotAutoExecuteAndRejectsZipSlipOrOverwrite(){
        val dir=Files.createTempDirectory("addon-zip").toFile()
        val good=AddonSources.unpack(dir,"fixture",zip("main.lua","error('must not run during install')"))
        assertTrue(good.resolve("main.lua").isFile)
        assertFails{AddonSources.unpack(dir,"fixture",zip("main.lua",""))}
        assertFails{AddonSources.unpack(dir,"evil",zip("../escape.lua",""))}
        assertFalse(dir.parentFile.resolve("escape.lua").exists())
        assertFails{AddonSources.unpack(dir,"data",zip("main.lua",""))}
    }
    @Test fun metadataEntryCannotEscapeItsOwnDirectory(){
        val root=Files.createTempDirectory("addon-path").toFile();val dir=root.resolve("test").apply { mkdir() }
        root.resolve("outside.js").writeText("1+1")
        dir.resolve("metadata.yaml").writeText("name: sample\nentry: ../outside.js\n")
        assertNull(ScriptPackage.open(dir,root))
    }
}
