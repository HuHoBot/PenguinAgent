package agent.ai

import com.alibaba.fastjson.JSONObject
import kotlin.test.*

class AgentUtilityToolsTest {
    @Test fun gameAgentExposesCalculatorRandomAndUnitWaitToolsWithTypedSchemas() {
        val defs=AgentTools.buildTools()
        val byName=(0 until defs.size).mapNotNull { defs.getJSONObject(it)?.getJSONObject("function") }.associateBy { it.getString("name") }
        assertTrue(setOf(AgentTools.TOOL_CALCULATE,AgentTools.TOOL_RANDOM_CHOICE,AgentTools.TOOL_WAIT).all(byName::containsKey))
        val random=byName.getValue(AgentTools.TOOL_RANDOM_CHOICE).getJSONObject("parameters").getJSONObject("properties").getJSONObject("options")
        assertEquals("array",random.getString("type"))
        val wait=byName.getValue(AgentTools.TOOL_WAIT).getJSONObject("parameters").getJSONObject("properties")
        assertEquals("number",wait.getJSONObject("duration").getString("type"))
    }
    @Test fun waitDurationsConvertUnitsAndRespectConfiguredMaximum() {
        assertEquals(7200,AgentTools.waitSeconds(JSONObject.parseObject("{\"duration\":2,\"unit\":\"hours\"}"),7200))
        assertEquals(3600,AgentTools.waitSeconds(JSONObject.parseObject("{\"duration\":99,\"unit\":\"minutes\"}"),3600))
        assertFails { AgentTools.waitSeconds(JSONObject.parseObject("{\"duration\":1,\"unit\":\"fortnights\"}"),7200) }
    }
}
