package agent.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupBannerTest {
    @Test fun bannerWordmarkFitsTheSameFrameOnStartAndReload() {
        val start = StartupBanner.lines("1.23", "Leaf", false)
        val reload = StartupBanner.lines("1.23", "Velocity", true)
        assertEquals(10, start.size)
        assertEquals(start.dropLast(1), reload.dropLast(1))
        assertTrue(start.dropLast(1).all { it.length == start.first().length })
        assertTrue(start.drop(1).take(7).all { '█' in it })
        assertEquals("HuHoBot Penguin v1.23 · Leaf · 启动", start.last())
        assertEquals("HuHoBot Penguin v1.23 · Velocity · 重载", reload.last())
    }
}
