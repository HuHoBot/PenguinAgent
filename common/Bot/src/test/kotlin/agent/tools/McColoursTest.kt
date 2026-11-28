package agent.tools

import kotlin.test.*

class McColoursTest {
    @Test fun removesLegacyHexAndAmpersandCodesButKeepsPlainAmpersands() {
        assertEquals("Hello Astra!", McColours.strip("§6Hello §lAstra§r!"))
        assertEquals("Red", McColours.strip("§x§f§f§0§0§0§0Red"))
        assertEquals("Fish & Chips", McColours.strip("Fish & Chips"))
        assertEquals("A", McColours.strip("&aA"))
    }
}
