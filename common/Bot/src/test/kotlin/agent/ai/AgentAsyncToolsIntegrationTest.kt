package agent.ai

import agent.HuHoBot
import agent.tools.Cancelable
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.sun.net.httpserver.HttpServer
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage
import io.github.kloping.qqbot.entities.qqpd.v2.Member
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Executes the real public Agent entry point against a local Chat Completions server. */
class AgentAsyncToolsIntegrationTest {
    @Test fun asyncWaitReleasesWorkerAndPreservesEveryCallInTheSameResponse() {
        runCase(15, listOf(call("wait", "sleep", "{\"duration\":1,\"unit\":\"seconds\"}"),
            call("calc", "calculate", "{\"expression\":\"6*7\"}"),
            call("pick", "random_choice", "{\"options\":[\"only-choice\"]}"))) { fixture ->
            assertTrue(fixture.finished.await(8, TimeUnit.SECONDS), fixture.logs.toString())
            assertEquals(2, fixture.requests.size)
            val messages = fixture.requests[1].getJSONArray("messages")
            val results = (0 until messages.size).map { messages.getJSONObject(it) }.filter { it.getString("role") == "tool" }
            assertEquals(setOf("wait", "calc", "pick"), results.map { it.getString("tool_call_id") }.toSet())
            assertTrue(results.first { it.getString("tool_call_id") == "wait" }.getString("content").contains("1 秒"))
            assertEquals("42", results.first { it.getString("tool_call_id") == "calc" }.getString("content"))
            assertEquals("only-choice", results.first { it.getString("tool_call_id") == "pick" }.getString("content"))
            assertTrue(fixture.workerFreedBeforeWaitEnded, "sleep must not hold the only Agent worker")
        }
    }

    @Test fun configuredStepBudgetSurvivesRepeatedToolRequests() {
        runCase(2, listOf(call("loop", "calculate", "{\"expression\":\"1+1\"}")), repeatTools = true) { f ->
            assertTrue(f.finished.await(8, TimeUnit.SECONDS), f.logs.toString())
            assertEquals(2, f.requests.size)
            assertTrue(f.output.any { it.contains("2 步上限") })
        }
    }

    @Test fun stopCancelsSleepingTaskWithoutAnotherModelRequest() {
        runCase(15, listOf(call("wait", "sleep", "{\"duration\":1,\"unit\":\"hours\"}"))) { f ->
            AgentManager.stopAgent(f.plugin, f.group, f.user)
            val session = AgentManager.findSession(f.group, f.user)!!
            assertNull(session.pendingWait)
            assertFalse(session.waiting)
            assertTrue(session.stopped)
            assertTrue(session.pendingToolCalls.isEmpty())
            assertEquals(1, f.requests.size)
        }
    }

    private fun call(id: String, name: String, arguments: String): JSONObject = JSONObject().apply {
        put("id", id); put("type", "function")
        put("function", JSONObject().apply { put("name", name); put("arguments", arguments) })
    }

    private class Fixture(val group: String, val user: String) {
        lateinit var plugin: HuHoBot
        val requests = CopyOnWriteArrayList<JSONObject>()
        val logs = CopyOnWriteArrayList<String>()
        val output = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)
        var workerFreedBeforeWaitEnded = false
    }

    private fun runCase(maxSteps: Int, tools: List<JSONObject>, repeatTools: Boolean = false, verify: (Fixture) -> Unit) {
        val f = Fixture("fixture-${UUID.randomUUID()}", "fixture-user")
        val worker = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            try {
                val request = JSON.parseObject(exchange.requestBody.bufferedReader().use { it.readText() })
                f.requests.add(request)
                val message = JSONObject().apply {
                    put("role", "assistant")
                    if (f.requests.size == 1 || repeatTools) { put("content", ""); put("tool_calls", tools) }
                    else put("content", "fixture-finished")
                }
                val bytes = JSON.toJSONString(mapOf("choices" to listOf(mapOf("message" to message)))).toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        val config = AgentConfig(true,"http://127.0.0.1:${server.address.port}/v1","local-fixture","fixture",AgentCommandMode.AUTO,
            false,"","",AgentThinkVisibility.LOG,false,"",AgentContextConfig(4000,20,"",10),maxSteps=maxSteps,maxWaitSeconds=3600)
        f.plugin = Proxy.newProxyInstance(javaClass.classLoader,arrayOf(HuHoBot::class.java)) { _, method, args ->
            when (method.name) {
                "getAgentConfig" -> config
                "getPlatform", "getServerVersion", "getPluginVersion" -> "fixture"
                "submitAsync" -> { val future = worker.submit(args!![0] as Runnable); object : Cancelable { override fun cancel() { future.cancel(true) } } }
                "sendMarkdownToGroup" -> {
                    val text = args!![1].toString(); f.output.add(text)
                    if (text.contains("fixture-finished") || text.contains("步上限")) f.finished.countDown()
                    null
                }
                "log_info", "log_error", "log_warning" -> { f.logs.add(args!![0].toString()); null }
                "isAgentFetchResultHidden", "isTtsEnabled" -> method.name == "isAgentFetchResultHidden"
                "toString" -> "fixture-plugin"
                else -> null
            }
        } as HuHoBot
        val meta = JSONObject().apply { put("group_openid", f.group) }
        val raw = RawMessage().setId("fixture-inbound").setContent("/agent fixture")
        val member = Member(JSONObject().apply { put("member_openid", f.user); put("username", "Fixture") })
        val event = Proxy.newProxyInstance(javaClass.classLoader,arrayOf(GroupMessageEvent::class.java)) { _, method, _ ->
            when(method.name) { "getMetadata" -> meta; "getGroupId", "getGroupOpenId" -> f.group; "getRawMessage" -> raw; "getSender" -> member; else -> null }
        } as GroupMessageEvent
        try {
            AgentManager.startAgent(f.plugin, event, "run fixture utilities")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var session: AgentSession?
            do {
                session = AgentManager.findSession(f.group, f.user)
                if ((session?.waiting == true && !session.isRunning) || (session?.finished == true && !session.isRunning)) break
                Thread.sleep(10)
            } while (System.nanoTime() < deadline)
            assertNotNull(session)
            val captured = session!!
            worker.submit { f.workerFreedBeforeWaitEnded = captured.waiting || captured.finished }.get(1, TimeUnit.SECONDS)
            verify(f)
        } finally {
            AgentManager.clearSession(f.plugin, f.group, f.user)
            worker.shutdownNow()
            server.stop(0)
        }
    }
}
