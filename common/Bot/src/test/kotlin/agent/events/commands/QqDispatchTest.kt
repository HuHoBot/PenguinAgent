package agent.events.commands

import agent.HuHoBot
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage
import io.github.kloping.qqbot.entities.qqpd.v2.Member
import java.lang.reflect.Proxy
import kotlin.test.*

/** Runs real QQ SDK message entities through the plugin dispatcher. No QQ account or public send. */
class QqDispatchTest {
    class Probe:BaseCommand(){
        val received=mutableListOf<String>()
        @Commands(command="probe",describe="test") fun command(plugin:HuHoBot,event:GroupMessageEvent,text:String){received+=text}
    }
    private fun plugin():HuHoBot=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(HuHoBot::class.java)){_,m,_->
        when(m.name){"getCommandList"->emptyMap<String,Boolean>();"getCustomCommands"->emptyList<Any>();"getCommandAliasMap","getCommandAliasRename"->emptyMap<String,String>();"log_error"->throw AssertionError("Unexpected dispatch error")
        else->when(m.returnType){java.lang.Boolean.TYPE->false;java.lang.Integer.TYPE->0;else->null}}
    } as HuHoBot
    private fun event(text:String):GroupMessageEvent {
        val raw=RawMessage().setContent(text).setId("mock-message").setSrcGuildId("mock-group")
        val member=Member().apply { openid="mock-member";username="Tester" }
        return Proxy.newProxyInstance(javaClass.classLoader,arrayOf(GroupMessageEvent::class.java)){_,m,_->
            when(m.name){"getRawMessage"->raw;"getSender"->member;"getGroupOpenId","getGroupId"->"mock-group";else->null}
        } as GroupMessageEvent
    }
    @Test fun sdkEntitiesPreserveUnicodeSlashMentionAndCaseInsensitiveCommand(){
        val command=Probe()
        val p=plugin()
        for((input,expected)in listOf("/probe 你好 世界" to "你好 世界","<@!bot> /PrObE xYz" to "xYz","probe 保留 &7 <red>" to "保留 &7 <red>")){
            assertEquals(BaseCommand.DispatchResult.HANDLED,command.handleMessage(p,event(input),false))
            assertEquals(expected,command.received.last())
        }
        assertEquals(BaseCommand.DispatchResult.NOT_HANDLED,command.handleMessage(p,event("非命令消息"),false))
    }
    @Test fun repeatedReconnectRegistrationDoesNotDuplicateMenuMetadata(){
        repeat(20){Probe()}
        assertEquals(1,BaseCommand.allCommands().count { it.command=="probe" })
    }
}
