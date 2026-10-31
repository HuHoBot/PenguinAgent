package cn.huohuas001.bot.provider

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 群→游戏消息的 MiniMessage / 旧式颜色码转换。
 */
class ConfigProviderMiniMessageTest {

    private fun provider(template: String) = object : ConfigProvider {
        override fun getChatFormat() = ChatFormat("g {message}", template, true, "")
        override fun getMotd() = Motd("", 0, "", "", false, false)
        override fun getBotName() = "bot"
        override fun getPlatform() = "test"
        override fun getPluginVersion() = "test"
    }

    private fun format(template: String, name: String = "Steve", message: String = "hello") =
        provider(template).formatGroupMessage(name, message)

    @Test
    fun `MiniMessage 命名颜色`() {
        assertEquals("§6[QQ]§r Steve: hello", format("<gold>[QQ]</gold> {name}: {message}"))
    }

    @Test
    fun `MiniMessage 十六进制颜色`() {
        assertEquals("§x§1§2§3§4§5§6Steve§r: hi", format("<#123456>{name}</#123456>: {message}", message = "hi"))
    }

    @Test
    fun `MiniMessage 装饰格式`() {
        assertEquals("§lSteve§r: hi", format("<bold>{name}</bold>: {message}", message = "hi"))
    }

    @Test
    fun `兼容旧式 amp 颜色码`() {
        assertEquals("§6Steve§r: hi", format("&6{name}&r: {message}", message = "hi"))
    }

    @Test
    fun `兼容旧式 section 颜色码`() {
        assertEquals("§6Steve§r: hi", format("§6{name}§r: {message}", message = "hi"))
    }

    @Test
    fun `用户内容里的标签不会被解析`() {
        assertEquals("<red>Evil: hi", format("{name}: {message}", name = "<red>Evil", message = "hi"))
        assertEquals("Steve: <red>red text", format("{name}: {message}", message = "<red>red text"))
    }

    @Test
    fun `普通文本原样保留`() {
        assertEquals("Steve: a < b", format("{name}: {message}", message = "a < b"))
        assertEquals("Steve: <notatag>yo", format("{name}: {message}", message = "<notatag>yo"))
    }

    @Test
    fun `@高亮颜色码保留`() {
        assertEquals("[QQ] Steve: §9@Alex§r hello", format("[QQ] {name}: {message}", message = "§9@Alex§r hello"))
    }
}
