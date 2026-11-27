package agentcheck

import agent.spigot.scripting.RhinoBridge
import agent.spigot.scripting.ScriptPackage
import java.nio.file.Files
import java.util.function.Function
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class SmallRuntimeTest {
    @Test fun bundledJavaScriptHandlesDeferredCallbacksCollectionsAndUnloading() {
        val js=RhinoBridge()
        val raw=js.eval("(function(x){return x.toUpperCase() + ':' + JSON.stringify([1,2,3].map(function(v){return v*v;}));})")
        @Suppress("UNCHECKED_CAST") val fn=RhinoBridge.adapt(raw,Function::class.java) as Function<String,Any>
        assertEquals("OK:[1,4,9]",CompletableFuture.supplyAsync{fn.apply("ok")}.get().toString())
        js.close();assertFails { fn.apply("stale") }
    }
    @Test fun runawayJavaScriptDoesNotKeepWorkerAliveForever() {
        val started=System.nanoTime()
        RhinoBridge().use { js->assertFails { js.eval("while(true) {}") } }
        assertTrue((System.nanoTime()-started)/1_000_000 < 8000)
    }
    @Test fun javaScriptNeedsNoExternalLanguageVm() {
        val root=Files.createTempDirectory("small-addon").toFile();val dir=root.resolve("a").apply{mkdir()}
        // 1.24 兼容层：main.py 恢复为合法入口（engines/ 放回旧 GraalPy 引擎即可运行）
        dir.resolve("main.py").writeText("pass")
        assertEquals(".py",ScriptPackage.open(dir,root)!!.language())
        dir.resolve("main.js").writeText("JSON.stringify({ok:true})")
        assertEquals(".js",ScriptPackage.open(dir,root)!!.language())
    }
}
