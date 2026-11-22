package agentcheck

import agent.spigot.onlinelist.*
import agent.update.FetchRoutes
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import kotlin.test.*

class ExpandedChecksTest {
    private fun png(color:Color):ByteArray {
        val image=BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().also { it.color=color;it.fillRect(8,8,8,8);it.dispose() }
        return java.io.ByteArrayOutputStream().also { ImageIO.write(image,"png",it) }.toByteArray()
    }
    @Test fun skinFamiliesParseOnlySkinAndFallbackAfterMalformedResponses() {
        val skin=png(Color.MAGENTA)
        for(type in listOf("ashcon","ely","csl","mojang","avatar","crafatar")){
            val row=mapOf("type" to type,"url" to "https://fixture.example/$type/profile","root" to "https://fixture.example")
            val seen=mutableListOf<String>()
            val result=SkinSources(listOf(row)).find(OnlineListPlayer("Wren","01234567-89ab-cdef-0123-456789abcdef",null,false)){url ->
                seen+=url
                when {
                    url.endsWith("skin.png")||url.contains("/textures/")->skin
                    url.contains("sessionserver.mojang.com")-> {
                        val value=java.util.Base64.getEncoder().encodeToString("""{"textures":{"CAPE":{"url":"https://fixture.example/cape"},"SKIN":{"url":"https://fixture.example/skin.png"}}}""".toByteArray())
                        """{"properties":[{"name":"textures","value":"$value"}]}""".toByteArray()
                    }
                    type=="ashcon"->"""{"textures":{"skin":{"url":"https://fixture.example/skin.png"}}}""".toByteArray()
                    type=="ely"->"""{"SKIN":{"url":"https://fixture.example/skin.png"}}""".toByteArray()
                    type=="csl"->"""{"skins":{"slim":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}""".toByteArray()
                    type=="mojang"->"""{"id":"0123456789abcdef0123456789abcdef"}""".toByteArray()
                    else->skin
                }
            }
            assertNotNull(result,type)
            assertEquals(type in listOf("avatar","crafatar"),result.second,type)
            assertFalse(seen.any { it.endsWith("cape") })
        }
        val chain=SkinSources(listOf(mapOf("type" to "csl","url" to "https://fixture.example/bad"),mapOf("type" to "avatar","url" to "https://fixture.example/good")))
        assertNotNull(chain.find(OnlineListPlayer("Juno","j",null,false)){url->if(url.endsWith("bad"))"<html>failed</html>".toByteArray()else skin})
        assertNull(SkinSources.image("not a PNG".toByteArray()))
    }
    @Test fun exactSkinWinsAndCacheAvoidsRepeatedDownloads() {
        var requests=0
        val cache=AvatarCache(Executor{it.run()},100,100,16,true,sources=emptyList(),fetcher={url->requests++;assertEquals("https://fixture.example/skin.png",url);png(Color.CYAN)})
        val player=OnlineListPlayer("Aster","not-a-real-uuid","https://fixture.example/skin.png",false)
        repeat(3){val map=cache.loadAll(listOf(player));assertEquals(Color.CYAN.rgb,map[player.uuid]!!.getRGB(32,32))}
        assertEquals(1,requests)
        cache.close()
    }
    @Test fun classicAndHdHeadsIncludeHatLayerAtCorrectCoordinates() {
        val cache=AvatarCache(Executor{it.run()},100,100,16,false)
        for((width,height)in listOf(64 to 32,64 to 64,128 to 128,256 to 256)){
            val scale=width/64
            val skin=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB)
            skin.createGraphics().also { g->g.color=Color.RED;g.fillRect(8*scale,8*scale,8*scale,8*scale);g.color=Color.BLUE;g.fillRect(40*scale,8*scale,4*scale,8*scale);g.dispose() }
            val head=cache.cropHead(skin)
            assertEquals(Color.BLUE.rgb,head.getRGB(16,64),"hat overlay at $width x $height")
            assertEquals(Color.RED.rgb,head.getRGB(112,64),"base face at $width x $height")
        }
    }
    @Test fun performanceColoursChangeAcrossHealthyWarningCriticalAndUnknown() {
        val renderer=OnlineListRenderer(AvatarCache(Executor{it.run()},100,100,8,false))
        val healthy=ServerSnapshot("Test",30,0,emptyList(),tps=19.9,mspt=12.0,memoryUsedMb=256,memoryMaxMb=1024)
        val warning=healthy.copy(tps=16.0,mspt=42.0,memoryUsedMb=820)
        val critical=healthy.copy(tps=8.0,mspt=98.0,memoryUsedMb=1000)
        val unknown=healthy.copy(tps=null,mspt=null,memoryMaxMb=0)
        for(i in 0..2){
            assertEquals(4,listOf(healthy,warning,critical,unknown).map { renderer.metricColour(i,it) }.distinct().size)
            val bad=renderer.metricColour(i,critical);assertTrue(bad.red>bad.green)
        }
        assertTrue(renderer.performanceFractions(healthy)[1]<renderer.performanceFractions(critical)[1])
    }
    @Test fun githubIsAlwaysFirstAndOnlyKnownHostsReceiveFallbackRoutes() {
        val release="https://github.com/HuHoBot/PenguinAgent/releases/download/v1.23.2/HuHoBot-Penguin-1.23.2.jar"
        val raw="https://raw.githubusercontent.com/HuHoBot/PenguinAgent/master/MEU.json"
        for(url in listOf(release,raw,"https://api.github.com/repos/HuHoBot/PenguinAgent/releases")){
            val routes=FetchRoutes.candidates(url);assertEquals(url,routes.first());assertTrue(routes.size>1)
        }
        assertTrue(FetchRoutes.candidates(raw).any { it.contains("cdn.jsdelivr.net/gh/HuHoBot/PenguinAgent@master/MEU.json") })
        assertEquals(listOf("https://example.com/file"),FetchRoutes.candidates("https://example.com/file"))
        assertTrue(FetchRoutes.releaseAsset(release))
        assertFalse(FetchRoutes.releaseAsset(release.replace("HuHoBot/PenguinAgent","attacker/fork")))
        assertFalse(FetchRoutes.releaseAsset("https://github.com@evil.example/HuHoBot/PenguinAgent/releases/download/bad"))
    }
    @Test fun rendererReloadConstructionDoesNotReprocessArtwork(){
        val before=OnlineListRenderer.assetPreparationCount()
        repeat(5){OnlineListRenderer(AvatarCache(Executor{it.run()},100,100,8,false))}
        assertEquals(before,OnlineListRenderer.assetPreparationCount(),"reload must not prepare the full PNG on the server thread")
    }
    @Test fun releaseTargetsInstalledDescriptorNotRemappedCacheAndRejectsDuplicates(){
        val dir=java.nio.file.Files.createTempDirectory("release-jars").toFile()
        fun jar(name:String,plugin:String,version:String){
            java.util.jar.JarOutputStream(dir.resolve(name).outputStream()).use { out ->
                out.putNextEntry(java.util.jar.JarEntry("plugin.yml"))
                out.write("name: $plugin\nversion: '$version'\n".toByteArray());out.closeEntry()
            }
        }
        jar("custom-install-name.jar","HuHoBotPenguin","1.23.2")
        jar("other.jar","AnotherPlugin","1.23.2")
        val found=agent.spigot.monitor.ReleaseFiles.installedJar(dir,"HuHoBotPenguin","1.23.2")
        assertEquals("custom-install-name.jar",found?.name)
        dir.resolve(".paper-remapped").mkdir()
        java.nio.file.Files.copy(found!!.toPath(),dir.resolve(".paper-remapped/cache.jar").toPath())
        assertEquals(found,agent.spigot.monitor.ReleaseFiles.installedJar(dir,"HuHoBotPenguin","1.23.2"))
        jar("duplicate.jar","HuHoBotPenguin","1.23.2")
        assertNull(agent.spigot.monitor.ReleaseFiles.installedJar(dir,"HuHoBotPenguin","1.23.2"))
    }
    @Test fun bundledSymbolSubsetRendersTitleStarsWithoutSystemFonts(){
        val font=javaClass.getResourceAsStream("/fonts/HuHoSymbols.ttf")!!.use { java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,it) }
        for(cp in listOf(0x2726,0x2605,0x2713))assertTrue(font.canDisplay(cp),"missing U+${cp.toString(16)}")
    }
    @Test fun timelinePositionsUseElapsedTimeNotSampleIndex() {
        val renderer=OnlineListRenderer(AvatarCache(Executor{it.run()},100,100,8,false))
        val base=java.time.Instant.parse("2026-11-20T01:00:00Z").toEpochMilli()
        val points=listOf(base to 0,(base+1000)to 2,(base+86400000)to 1)
        val ticks=renderer.trendTimeLabels(points,5)
        assertEquals(5,ticks.size);assertEquals("11-20 15:00",ticks[1].second)
        val fast=renderer.trendTimeLabels(listOf(base to 0,(base+60000)to 1),5)
        assertEquals("09:00:15",fast[1].second)
    }
}
