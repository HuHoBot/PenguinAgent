package cn.huohuas001.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 指令面板复用逻辑的回归测试。
 *
 * 1.21.0 把配置哈希写进面板备注，命令或群配置一变就创建新面板并删除旧面板，
 * 导致 QQ 客户端缓存的 panel_id 失效、群面板不再弹出。这里锁住「复用优先」的行为。
 */
class MenuManagerTest {

    @Test
    fun `槽位备注稳定且不包含内容哈希`() {
        assertEquals("HuHoBot Penguin 指令面板", MenuManager.remarkForSlot(0))
        assertEquals("HuHoBot Penguin 指令面板 #2", MenuManager.remarkForSlot(1))
        assertEquals("HuHoBot Penguin 指令面板 #3", MenuManager.remarkForSlot(2))
    }

    @Test
    fun `识别新旧受管面板备注`() {
        assertTrue(MenuManager.isManagedRemark("HuHoBot Penguin 指令面板"))
        assertTrue(MenuManager.isManagedRemark("HuHoBot 指令面板"))
        assertTrue(MenuManager.isManagedRemark("HuHoBot Penguin 指令面板 #2"))
        // 1.21.0 写出的内容哈希备注
        assertTrue(MenuManager.isManagedRemark("HuHoBot Penguin 指令面板 #92fd92c8"))

        assertFalse(MenuManager.isManagedRemark(""))
        assertFalse(MenuManager.isManagedRemark("HuHoBot test 面板"))
        assertFalse(MenuManager.isManagedRemark("其他机器人的面板"))
    }

    @Test
    fun `备注不一致时复用已有面板而不是新建`() {
        val legacy = MenuManager.PanelRecord(
            id = "panel-legacy",
            remark = "HuHoBot Penguin 指令面板",
            groupOpenIds = listOf("group-a"),
            version = 3
        )
        val hashed = MenuManager.PanelRecord(
            id = "panel-hashed",
            remark = "HuHoBot Penguin 指令面板 #92fd92c8",
            groupOpenIds = listOf("group-b"),
            version = 7
        )

        // 槽位备注与历史面板都不同（1.21.0 升上来的场景），仍应复用面板而不是新建
        val reused = MenuManager.choosePanelForReuse(
            listOf(legacy, hashed),
            "HuHoBot Penguin 指令面板 #3",
            listOf("group-b")
        )
        assertSame(hashed, reused, "应优先复用已经关联目标群的面板")
    }

    @Test
    fun `备注不一致但只有一个空闲面板时直接复用`() {
        val legacy = MenuManager.PanelRecord(
            id = "panel-legacy",
            remark = "HuHoBot Penguin 指令面板",
            groupOpenIds = listOf("group-a"),
            version = 3
        )

        val reused = MenuManager.choosePanelForReuse(
            listOf(legacy),
            "HuHoBot Penguin 指令面板 #2",
            listOf("group-a")
        )
        assertSame(legacy, reused)
    }

    @Test
    fun `同名备注优先于其他空闲面板`() {
        val preferred = MenuManager.PanelRecord("p2", "HuHoBot Penguin 指令面板 #2", emptyList())
        val other = MenuManager.PanelRecord("p1", "HuHoBot Penguin 指令面板", listOf("group-a"))

        val reused = MenuManager.choosePanelForReuse(
            listOf(other, preferred),
            "HuHoBot Penguin 指令面板 #2",
            listOf("group-a")
        )
        assertSame(preferred, reused)
    }

    @Test
    fun `没有受管面板时才需要新建`() {
        assertNull(MenuManager.choosePanelForReuse(emptyList(), "HuHoBot Penguin 指令面板", listOf("group-a")))
    }
}
