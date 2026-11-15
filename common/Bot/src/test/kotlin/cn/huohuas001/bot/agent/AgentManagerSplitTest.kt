package cn.huohuas001.bot.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** splitMessages 超长拆分测试（对应 issue：单行超过 3000 字符时不切分导致发送失败）。 */
class AgentManagerSplitTest {

    private val max = 3000

    private fun split(content: String): List<String> = AgentManager.splitMessages(content)

    @Test
    fun 短内容原样返回() {
        val content = "你好，服务器在线 3 人。"
        assertEquals(listOf(content), split(content))
    }

    @Test
    fun 恰好等于上限不拆() {
        val content = "a".repeat(max)
        assertEquals(listOf(content), split(content))
    }

    @Test
    fun 恰好等于上限加一被拆() {
        val content = "a".repeat(max + 1)
        val chunks = split(content)
        assertTrue(chunks.size >= 2, "超过上限 1 字符也必须拆")
        chunks.forEach { assertTrue(it.length <= max, "每条都不超过上限，实际 ${it.length}") }
    }

    @Test
    fun 多行内容正确切块且不超限() {
        // 总长必须明显超过 3000，确保不走快速路径
        val lines = List(100) { "第${it}行内容。${"x".repeat(60)}" } // 每行约 65 字符
        val content = lines.joinToString("\n")
        val chunks = split(content)
        assertTrue(chunks.size > 1, "多行内容应拆成多条，实际 ${chunks.size}")
        chunks.forEach { assertTrue(it.length <= max, "每条都不超过上限，实际 ${it.length}") }
        chunks.forEach { assertTrue(it.isNotBlank(), "不应产生空块") }
        // 行可能恰好在块边界被硬切（允许），但首尾行必须完整、不丢内容
        assertTrue(chunks.first().startsWith(lines[0]), "首行应完整")
        assertTrue(chunks.last().endsWith(lines[99]), "末行应完整")
        // 无字符丢失：拼接后长度不少于原文（行间补的换行只会多不会少）
        assertTrue(chunks.sumOf { it.length } >= content.length, "内容总长不应减少")
        assertTrue(chunks.none { it.startsWith("\n") }, "块首不应残留空行")
    }

    @Test
    fun 单行超长被硬切且不丢字符() {
        val content = "y".repeat(max * 2 + 5)
        val chunks = split(content)
        assertTrue(chunks.size >= 2, "单行超过上限必须被切开")
        chunks.forEach { assertTrue(it.length <= max, "每条都不超过上限，实际 ${it.length}") }
        assertEquals(max * 2 + 5, chunks.sumOf { it.length }, "字符总数守恒")
        assertTrue(chunks.none { it.startsWith("\n") }, "块首不应残留空行")
    }

    @Test
    fun 单行超长夹在普通行之间() {
        val head = "第一行普通内容"
        val long = "z".repeat(max * 2)
        val tail = "最后一行"
        val chunks = split("$head\n$long\n$tail")
        assertTrue(chunks.size >= 3, "应有多个分块，实际 ${chunks.size}")
        chunks.forEach { assertTrue(it.length <= max, "每条都不超过上限，实际 ${it.length}") }
        assertTrue(chunks.first().startsWith(head), "首块以第一行开头")
        assertTrue(chunks.last().endsWith(tail), "末块以最后一行结尾")
        chunks.forEach { assertTrue(it.isNotBlank(), "不应产生空块") }
        assertTrue(chunks.none { it.startsWith("\n") }, "块首不应残留空行")
    }

    @Test
    fun 空内容不产生块() {
        assertTrue(split("").isEmpty(), "空内容不应产生块")
    }

    @Test
    fun 单个空行也被发送() {
        val chunks = split("开始\n\n结束")
        assertEquals(1, chunks.size)
    }
}
