package agentcheck

import agent.spigot.scripting.ScriptAddonLoader
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class AddonShutdownTest {
    @Test fun closedLoaderRejectsQueuedActivationBeforeTouchingHostState() {
        val field=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible=true }
        val unsafe=field.get(null) as sun.misc.Unsafe
        val loader=unsafe.allocateInstance(ScriptAddonLoader::class.java) as ScriptAddonLoader
        ScriptAddonLoader::class.java.getDeclaredField("closing").apply { isAccessible=true }.setBoolean(loader,true)
        val executed=AtomicBoolean()
        val activate=ScriptAddonLoader::class.java.getDeclaredMethod("onMain",Callable::class.java).apply { isAccessible=true }
        val error=assertFails { activate.invoke(loader,Callable { executed.set(true); null }) }
        assertTrue(error.cause is CancellationException)
        assertFalse(executed.get())
    }
}
