package agentcheck

import agent.spigot.chatlog.ChatLogRenderer
import agent.spigot.chatlog.ChatLogStore
import agent.spigot.manager.ServerMode
import agent.spigot.manager.ServerModeDetector
import agent.spigot.onlinelist.SkinPolicy
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class ChatLogTest {
    private fun entry(name: String, message: String, at: Long = 1_700_000_000_000) =
        ChatLogStore.Entry(name, "[VIP]$name", message, at)

    @Test fun pagesNeverLoseEntriesEvenWhenLongMessagesCostTwoSlots() {
        ChatLogStore.configure(50)
        ChatLogStore.clear()
        repeat(5) { ChatLogStore.record(entry("Short$it", "hello")) }
        ChatLogStore.record(entry("Long", "x".repeat(200)))
        // Five short entries fill three two-slot pages; the long one needs a fourth.
        for (page in 1..4) {
            val result = ChatLogStore.page(null, page, 2, 80)
            assertTrue(result.entries.size <= 3)
            assertEquals(4, result.totalPages)
        }
        assertEquals(6, ChatLogStore.page(null, 1, 100, 80).totalEntries)
        assertTrue(ChatLogStore.page(null, 1, 2, 80).entries.any { it.name == "Long" })
    }

    @Test fun playerFilterIsCaseInsensitiveAndPagingClampsOutOfRange() {
        ChatLogStore.configure(50)
        ChatLogStore.clear()
        ChatLogStore.record(entry("Astra", "one"))
        ChatLogStore.record(entry("Borealis", "two"))
        ChatLogStore.record(entry("astra", "three"))
        val filtered = ChatLogStore.page("ASTRA", 1, 30, 80)
        assertEquals(2, filtered.totalEntries)
        assertTrue(filtered.entries.all { it.name.equals("Astra", true) })
        // Requesting past the end yields the last real page rather than an empty render.
        val clamped = ChatLogStore.page(null, 99, 1, 80)
        assertEquals(clamped.totalPages, clamped.page)
        assertTrue(clamped.entries.isNotEmpty())
    }

    @Test fun boundedStoreDropsOldestWithoutTouchingOtherMemory() {
        ChatLogStore.configure(30)
        ChatLogStore.clear()
        repeat(36) { ChatLogStore.record(entry("P$it", "m$it")) }
        assertEquals(30, ChatLogStore.size())
        assertEquals(listOf("P35", "P34", "P33"), ChatLogStore.page(null, 1, 30, 80).entries.take(3).map { it.name })
        assertTrue(ChatLogStore.page(null, 1, 30, 80).entries.none { it.name == "P0" })
    }

    @Test fun rendererProducesReadablePngWithHeaderAndEmptyState() {
        val png = ChatLogRenderer.render("Fixtures", listOf(entry("Astra", "hello"), entry("Borealis", "y".repeat(200))), 1, 1, 2, null, 80)
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(ChatLogRenderer.WIDTH, image.width)
        assertTrue(image.height > 200)
        assertTrue(png.size > 8_000)
        val empty = ImageIO.read(ByteArrayInputStream(ChatLogRenderer.render("Fixtures", emptyList(), 1, 1, 0, "Astra", 80)))
        assertEquals(ChatLogRenderer.WIDTH, empty.width)
    }

    @Test fun serverModeDetectionUsesRuntimeClassesAndExplicitHybridOverride() {
        assertEquals(ServerMode.PAPER, ServerModeDetector.detect { false })
        assertEquals(ServerMode.FOLIA, ServerModeDetector.detect { it == ServerModeDetector.REGION_CLASS })
        assertEquals(ServerMode.HYBRID, ServerMode.parse(" hybrid "))
        assertEquals(ServerMode.PAPER, ServerMode.parse("Paper"))
        assertNull(ServerMode.parse("auto"))
        assertNull(ServerMode.parse("nonsense"))
    }

    @Test fun offlineServersSkipRemoteSkinsUnlessTheOwnerOptsOut() {
        assertFalse(SkinPolicy.remoteLookupsEnabled(true, serverOnlineMode = false, offlineDefaultSkins = true))
        assertTrue(SkinPolicy.remoteLookupsEnabled(true, serverOnlineMode = false, offlineDefaultSkins = false))
        assertTrue(SkinPolicy.remoteLookupsEnabled(true, serverOnlineMode = true, offlineDefaultSkins = true))
        assertFalse(SkinPolicy.remoteLookupsEnabled(false, serverOnlineMode = true, offlineDefaultSkins = false))
    }
}

class PlaceholderTextTest {
    @Test fun builtInVariablesExpandAndUnknownTokensRemainVisible() {
        val expanded = agent.spigot.onlinelist.PlaceholderText.expand(
            "[{world}]{name}/{display} {ping}ms {server} {online}/{max}", "Astra", "Astra", "world", 42, "Fixture", 3, 20
        )
        assertEquals("[world]Astra/Astra 42ms Fixture 3/20", expanded)
        assertEquals("—", agent.spigot.onlinelist.PlaceholderText.expand("{ping}", "A", "A", "w", -1, "s", 0, 0))
        assertEquals("%unknown%", agent.spigot.onlinelist.PlaceholderText.expand("%unknown%", "A", "A", "w", 1, "s", 0, 0))
    }
}

class SchedulerModeTest {
    @Test fun plainTestJvmMustNotClaimRegionisedScheduling() {
        // Regression: string-name comparison once made every server report FOLIA and crash 1.18.
        assertEquals(agent.spigot.manager.ServerMode.PAPER, agent.spigot.manager.FoliaScheduler.mode())
        assertFalse(agent.spigot.manager.FoliaScheduler.isFolia())
    }
}
