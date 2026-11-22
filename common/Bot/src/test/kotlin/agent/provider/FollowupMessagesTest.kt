package agent.provider

import agent.ai.ChatAiCommands
import agent.messages.Messages
import agent.state.SimpleYaml
import kotlin.test.*

class FollowupMessagesTest {
    @Test fun emptyHistoryStillDisplaysTheActualSettingAndCount() {
        Messages.initialize("zh_CN")
        assertEquals("聊天 AI：已开启\n语言：zh_CN\n本群记录条数：0",ChatAiCommands.statusText(true,"zh_CN",0))
        assertTrue(ChatAiCommands.statusText(false,"zh_CN",0).contains("未开启"))
    }

    @Test fun permissionYamlDoesNotMixTwoGroupsOrInlineLists() {
        val document=SimpleYaml.parse("""
            groups:
              - name: builders
                members:
                  - 'fixture-one'
                  - 'fixture-two'
                commands: ["查在线", "查信息"] # comment
                admin: false
              - name: ops
                members: ['fixture-admin']
                commands: []
                admin: true
        """.trimIndent())
        val groups=document["groups"] as List<*>
        assertEquals(2,groups.size)
        assertEquals(listOf("fixture-one","fixture-two"),(groups[0] as Map<*,*>)["members"])
        assertEquals(listOf("查在线","查信息"),(groups[0] as Map<*,*>)["commands"])
        assertEquals(listOf("fixture-admin"),(groups[1] as Map<*,*>)["members"])
        assertEquals(true,(groups[1] as Map<*,*>)["admin"])
    }
}
