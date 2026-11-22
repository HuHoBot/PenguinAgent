package agentcheck

import agent.spigot.HuHoBotSpigot
import agent.spigot.commands.HuHoBotCommand
import java.lang.reflect.Proxy
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import kotlin.test.*

class CommandBoundaryTest {
    @Test fun fallbackCommandsCannotBypassAdministrativePermission() {
        // The denied branch must not access a server/plugin instance at all.
        val field=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible=true }
        val unsafe=field.get(null) as sun.misc.Unsafe
        val host=unsafe.allocateInstance(HuHoBotSpigot::class.java) as HuHoBotSpigot
        val handler=HuHoBotCommand(host)
        val messages=mutableListOf<String>()
        val sender=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(CommandSender::class.java)){_,method,args->
            when(method.name){"hasPermission"->false;"sendMessage"->{messages+=args?.firstOrNull().toString();null};"getName"->"Unprivileged";else->null}
        } as CommandSender
        val command=object:Command("fallback"){override fun execute(sender:CommandSender,label:String,args:Array<out String>)=true}
        for(action in listOf("reload","password","webui","scripts","modules")) {
            assertTrue(handler.onCommand(sender,command,"hb",arrayOf(action,"install","ignored")))
        }
        assertEquals(5,messages.size)
        assertTrue(messages.all { it.contains("权限") })
    }
}

class CommandRouteTest {
    @Test fun onlyConflictedCommandsMoveUnderTheEffectiveRoot() {
        val labels=setOf("huhobot","at","send","qqbind")
        fun route(taken: Set<String>,root:String="huhobotagent") =
            agent.spigot.commands.GameCommandRoutes.resolve({ it in labels-taken },root)
        assertTrue(route(emptySet()).handover.isEmpty())
        assertEquals("/qqbind", route(emptySet()).path("qqbind"))
        assertEquals(setOf("qqbind"),route(setOf("qqbind")).handover)
        assertEquals("/huhobot qqbind",route(setOf("qqbind")).path("qqbind"))
        assertEquals("/huhobotagent qqbind",route(setOf("huhobot","qqbind")).path("qqbind"))
        assertEquals("/send",route(setOf("huhobot","qqbind")).path("send"))
    }
}
