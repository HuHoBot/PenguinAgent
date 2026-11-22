package agentcheck

import agent.spigot.onlinelist.*
import java.awt.Color
import java.net.URL
import java.nio.file.Files
import kotlin.test.*

class OnlineRegressionTest {
    @Test fun adminsAndTitledPlayersLeadAndEachTierUsesAlphabeticInitials() {
        val rows=listOf(
            OnlineListPlayer("Zoe","z",null,false),
            OnlineListPlayer("Milo","m",null,true),
            OnlineListPlayer("Astra","a",null,false,"<gold>[VIP]</gold>&7"),
            OnlineListPlayer("Brio","b",null,true),
            OnlineListPlayer("Cedar","c",null,false)
        )
        val sorted=OnlineListPages.sorted(rows)
        assertEquals(listOf("Brio","Milo","Astra","Cedar","Zoe"),sorted.map { it.name })
        assertTrue(sorted.take(3).all { it.administrator || OnlineListPages.hasTitle(it) })
        assertTrue(sorted.drop(3).none { it.administrator || OnlineListPages.hasTitle(it) })
    }

    @Test fun randomizedNameAndTitleFixturesRemainDeterministicallySorted() {
        val random=java.util.Random(773104L)
        val names=listOf("Aster","Bramble","Cinder","Dove","Ember","Fern","Gale","Hollis",
            "Indigo","Juniper","Kestrel","Lumen","Morrow","Nettle","Orion","Piper",
            "Quill","Riven","Sable","Tarin","Umber","Vale","Wren","Xanthe","Yarrow","Zephyr")
        val titles=listOf("<gold>[VIP]</gold>&7","<#70D6FF>[Guide]</#70D6FF>","&d[Builder]&7", "<gradient:#f80:#e4e>[MVP]</gradient>&7")
        val shuffled=names.let { java.util.ArrayList(it).also { list->java.util.Collections.shuffle(list,random) } }.mapIndexed { i,name ->
            val title=if(i%3==0)titles[random.nextInt(titles.size)] else ""
            OnlineListPlayer(name+random.nextInt(10),"uuid-$i",null,random.nextInt(11)==0,prefix=title,
                suffix=if(i%7==0)"&7"else "",pingMs=random.nextInt(801))
        }
        val result=OnlineListPages.sorted(shuffled)
        val tier=result.map { it.administrator||OnlineListPages.hasTitle(it) }
        assertEquals(tier.sortedDescending(),tier)
        result.zipWithNext().forEach { (a,b) ->
            if(OnlineListPages.priorityTier(a)==OnlineListPages.priorityTier(b))
                assertTrue(a.name.first().lowercaseChar()<=b.name.first().lowercaseChar())
        }
        assertEquals(result.map { it.uuid }.toSet(),shuffled.map { it.uuid }.toSet())
        assertTrue(result.map { it.pingMs }.distinct().size>10)
        assertTrue(result.map { it.prefix }.distinct().size>3)
    }

    @Test fun nestedMiniMessageRestoresParentColour() {
        val parts = McTextParser.parse("<red>A<blue>B</blue>C</red>D", Color.BLACK)
        assertEquals(Color(255,85,85), parts.first { "C" in it.text }.color)
        assertEquals(Color.BLACK, parts.last().color)
    }
    @Test fun shortLivedPeakIsNotOverwritten() {
        OnlineHistoryStore.configure(Files.createTempDirectory("history-test").toFile())
        OnlineHistoryStore.record(0)
        OnlineHistoryStore.record(5)
        OnlineHistoryStore.record(0)
        assertEquals(5, OnlineHistoryStore.recent().maxOf { it.second })
    }
    @Test fun legacyHistoryIsCopiedToVersionedDataWithoutDeletingSource() {
        val dir=Files.createTempDirectory("history-migrate").toFile()
        val old=dir.resolve("online-history.json")
        old.writeText("[[${System.currentTimeMillis()-1000},2]]")
        OnlineHistoryStore.configure(dir.resolve("data"))
        OnlineHistoryStore.flush()
        assertTrue(dir.resolve("data/online-history.json").isFile)
        assertTrue(old.isFile)
        assertEquals(2,OnlineHistoryStore.recent().last().second)
    }
    interface ProfilePlayer { fun getPlayerProfile(): Any }
    class Textures { fun getSkin(): URL = URL("https://textures.minecraft.net/texture/test") }
    class Profile { fun getTextures(): Any = Textures() }
    @Test fun bukkitUrlSkinIsAccepted() {
        val player = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(org.bukkit.entity.Player::class.java, ProfilePlayer::class.java)) { _, m, _ ->
            when(m.name) { "getPlayerProfile" -> Profile(); "getName" -> "Steve"; "getUniqueId" -> java.util.UUID(0,1); else -> null }
        } as org.bukkit.entity.Player
        assertEquals("https://textures.minecraft.net/texture/test", SkinResolver.resolve(player))
    }
}
