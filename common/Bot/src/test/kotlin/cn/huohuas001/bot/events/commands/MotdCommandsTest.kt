package cn.huohuas001.bot.events.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/motd` 查询地址的构造规则。
 */
class MotdCommandsTest {

    @Test
    fun `未指定端口时不传 port，交给 stype=auto 解析 SRV`() {
        val (status, image) = MotdCommands.buildApiUrls("mc.hypixel.net")
        assertEquals(
            "https://motd.txssb.cn/api/status?ip=mc.hypixel.net&stype=auto&lang=zh-CN",
            status
        )
        assertEquals(
            "https://motd.txssb.cn/api/app_img?ip=mc.hypixel.net&stype=auto&lang=zh-CN",
            image
        )
    }

    @Test
    fun `指定端口时带上 port 参数`() {
        val (status, image) = MotdCommands.buildApiUrls("mc.hypixel.net:25566")
        assertTrue(status.contains("ip=mc.hypixel.net&port=25566&stype=auto&lang=zh-CN"), status)
        assertTrue(image.contains("ip=mc.hypixel.net&port=25566&stype=auto&lang=zh-CN"), image)
        assertTrue(image.startsWith("https://motd.txssb.cn/api/app_img?"), image)
    }

    @Test
    fun `地址两端的空白会被忽略`() {
        val (status, _) = MotdCommands.buildApiUrls("  play.example.com:25565  ")
        assertEquals(
            "https://motd.txssb.cn/api/status?ip=play.example.com&port=25565&stype=auto&lang=zh-CN",
            status
        )
    }

    @Test
    fun `带方括号的 IPv6 地址可以携带端口`() {
        val (status, _) = MotdCommands.buildApiUrls("[::1]:25566")
        assertTrue(status.contains("port=25566"), status)
        assertFalse(status.contains("ip=[::1]"), status)
    }

    @Test
    fun `不带方括号的 IPv6 不会把最后一段误判成端口`() {
        val (status, _) = MotdCommands.buildApiUrls("::1")
        assertFalse(status.contains("port="), status)
    }
}
