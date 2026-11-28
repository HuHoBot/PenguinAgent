package agent.spigot.manager

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared font cascade. Parsing/registering fonts is worker-only; rendering chooses a face per grapheme. */
object FontManager {
    const val BUNDLED_FAMILY="HuHo CJK"
    @Volatile private var faces:List<Font> = emptyList()
    @Volatile private var ready=false
    @Volatile private var stopped=false
    @Volatile private var warming:CompletableFuture<Boolean>?=null
    private var executor:ExecutorService?=null
    private val derived=ConcurrentHashMap<String,Font>()
    private val glyphs=ConcurrentHashMap<String,Font>()
    private fun primaryThread():Boolean=runCatching { org.bukkit.Bukkit.getServer()!=null&&FoliaScheduler.isTickThread() }.getOrDefault(false)

    @Synchronized fun initializeAsync(dataFolder:File,logInfo:(String)->Unit={},logWarning:(String)->Unit={}):CompletableFuture<Boolean>{
        warming?.takeIf { !it.isDone }?.let { return it }
        stopped=false
        val service=executor?.takeUnless { it.isShutdown }?:Executors.newSingleThreadExecutor { r->Thread(r,"HuHoBot-Fonts").apply { isDaemon=true } }.also { executor=it }
        val future=CompletableFuture<Boolean>();warming=future
        service.submit { try{initialize(dataFolder,logInfo,logWarning);future.complete(ready)}catch(e:Throwable){future.completeExceptionally(e)} }
        return future
    }
    fun initialize(dataFolder:File,logInfo:(String)->Unit={},logWarning:(String)->Unit={}) {
        if(primaryThread()){initializeAsync(dataFolder,logInfo,logWarning);return}
        runCatching { agent.spigot.scripting.ModuleDownloads.applyPending(dataFolder) { if(!stopped)logWarning(it) } }
        val loaded=mutableListOf<Font>()
        fun remember(font:Font){GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);loaded+=font}
        for(name in BundledFonts.names){
            if(stopped||Thread.currentThread().isInterrupted())return
            try{BundledFonts.open(name)?.use { remember(Font.createFont(Font.TRUETYPE_FONT,it)) }}
            catch(e:Throwable){if(!stopped)logWarning("字体 $name 未就绪，保留其它回退字体: ${e.message}")}
        }
        // Only explicitly installed local font modules are loaded; startup never initiates a network request.
        for(file in File(dataFolder,"fonts").listFiles().orEmpty().filter { it.isFile&&it.extension.lowercase()in listOf("ttf","otf","ttc") }.take(24)){
            if(stopped||Thread.currentThread().isInterrupted())return
            try{
                if(file.length()>48L*1024*1024)continue
                val fonts=runCatching { @Suppress("UNCHECKED_CAST") (Font::class.java.getMethod("createFonts",File::class.java).invoke(null,file)as Array<Font>).toList() }
                    .getOrElse { listOf(Font.createFont(Font.TRUETYPE_FONT,file)) }
                fonts.forEach(::remember)
            }catch(e:Throwable){if(!stopped)logWarning("本地字体 ${file.name} 加载失败，使用回退字体: ${e.message}")}
        }
        if(stopped)return
        faces=loaded.distinctBy { it.getFamily(Locale.ROOT) };ready=faces.any { it.getFamily(Locale.ROOT)==BUNDLED_FAMILY }
        derived.clear();glyphs.clear()
        logInfo("字体后台准备完成：${faces.size} 个字体，中文/日韩/拉丁/符号/单色 Emoji 分字素回退")
    }
    private fun awaitWorkerFonts(){
        if(!ready&&!primaryThread())runCatching { warming?.get(30,TimeUnit.SECONDS) }
    }
    fun isBundledReady():Boolean=ready
    fun chooseFont(configured:String):String {
        awaitWorkerFonts()
        return configured.trim().ifBlank { if(ready)BUNDLED_FAMILY else Font.SANS_SERIF }
    }
    fun font(family:String,style:Int,size:Int):Font {
        awaitWorkerFonts();val sz=size.coerceIn(1,200)
        val base=if(family.isBlank()||family==Font.SANS_SERIF||family=="WenQuanYi Micro Hei")faces.firstOrNull()else faces.firstOrNull { it.getFamily(Locale.ROOT).equals(family,true) }
        val key="${base?.getFamily(Locale.ROOT)?:family}|$style|$sz"
        return derived.computeIfAbsent(key){base?.deriveFont(style,sz.toFloat())?:Font(family.ifBlank { Font.SANS_SERIF },style,sz)}
    }
    /** Variation selectors/ZWJ are formatting codepoints, not missing visible glyphs. */
    private fun supports(font:Font,text:String):Boolean=text.codePoints().allMatch { cp ->
        cp==0x200d||cp in 0xfe00..0xfe0f||cp in 0xe0100..0xe01ef||font.canDisplay(cp)
    }
    fun fontFor(text:String,family:String,style:Int,size:Int):Font {
        val key="$family|$style|$size|$text"
        glyphs[key]?.let { return it }
        val base=font(family,style,size)
        val emoji=text.codePoints().anyMatch { it>=0x1f000||it==0xfe0f }
        val candidates=if(emoji)faces.sortedBy { if(it.getFamily(Locale.ROOT).contains("Emoji"))0 else 1 }else faces
        val chosen=if(supports(base,text)&&!emoji)base else candidates.firstOrNull { supports(it,text) }?.deriveFont(style,size.toFloat())
            ?:base.takeIf { supports(it,text) }?:Font("Dialog",style,size)
        if(glyphs.size>8192)glyphs.clear()
        glyphs[key]=chosen;return chosen
    }
    @Synchronized fun shutdown(){stopped=true;warming?.cancel(true);executor?.shutdownNow()}
}
