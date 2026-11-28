package agent.events

import agent.events.commands.SensitiveFilter
import com.alibaba.fastjson.JSON
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.*

class SensitiveFilterTest {
    @Test fun emptyCustomListStillUsesBuiltInRulesAndDoesNotBlockCleanText() {
        val blocked = SensitiveFilter.inspect("这句话包含傻 逼", words = emptyList(), aiReview = false)
        assertTrue(blocked.blocked)
        assertTrue(blocked.matchedWords.contains("傻逼"))
        assertNotEquals(blocked.original, blocked.sanitized)
        val clean = SensitiveFilter.inspect("正常的服务器聊天", words = emptyList(), aiReview = false)
        assertFalse(clean.blocked)
        assertEquals(clean.original, clean.sanitized)
    }

    @Test fun customWordsAreNormalisedAndAllowNoneIsExplicit() {
        assertTrue(SensitiveFilter.inspect("请不要发 S E C R E T", listOf("secret"), aiReview = false).blocked)
        assertFalse(SensitiveFilter.inspect("傻 逼 secret", listOf("secret"), allowNone = true, aiReview = false).matchedWords.contains("傻逼"))
        assertTrue(SensitiveFilter.inspect("傻 逼 secret", listOf("secret"), allowNone = true, aiReview = false).blocked)
        assertFalse(SensitiveFilter.inspect("傻 逼", emptyList(), allowNone = true, aiReview = false).blocked)
    }

    @Test fun aiReviewCanOnlyAddAConfirmedViolationAndMalformedResponsesFailOpenToLocalRules() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            val response = JSON.toJSONString(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to "{\"violation\":true,\"reason\":\"fixture\"}")))))
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val result = SensitiveFilter.inspect(
                "没有本地词但需要AI拦截", words = emptyList(),
                baseUrl = "http://127.0.0.1:${server.address.port}/v1", apiKey = "fixture",
                model = "fixture", aiReview = true
            )
            assertTrue(result.blocked)
            assertTrue(result.aiBlocked)
            assertTrue(result.sanitized.contains("拦截"))
        } finally { server.stop(0) }
    }
}
