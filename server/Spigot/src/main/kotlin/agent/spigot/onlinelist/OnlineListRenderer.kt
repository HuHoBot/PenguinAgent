package agent.spigot.onlinelist

import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.awt.geom.Path2D
import java.awt.geom.Ellipse2D
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/** 玩家优先的亮色仪表板。所有块先测量再绘制；侧栏/文本均有严格裁剪范围。 */
class OnlineListRenderer(
    private val avatarCache: AvatarCache,
    columns: Int = 3,
    configuredFont: String = "",
    private val footerText: String = "Powered by HuHoBot",
    private val protocolSummary: String? = null,
    private val maxRenderPlayers: Int = 60,
    private val customLines: List<String> = emptyList(),
    private val title: String = ""
) {
    companion object {
        const val WIDTH = 1200
        private const val M = 24
        private const val GAP = 10
        private const val CARD_HEIGHT=76
        private const val ROW_GAP=8
        private const val TITLE_HEIGHT=36
        private const val MIN_PLAYER_ROWS=3
        private const val PANEL_HEIGHT=192
        private val INK = Color(35,47,65)
        private val MUTED = Color(88,105,126)
        private val GREEN = Color(13,123,77)
        private val RED = Color(183,40,52)
        private val BORDER = Color(192,211,230,210)
        private val TRACK = Color(213,225,239,210)
        private val TEAL = Color(27,139,152)
        private val CARD = Color(255,255,255,172)
        private val CARD_EDGE=Color(179,199,225,225)
        private val GOLD_EDGE=Color(220,151,62)
        private val VIOLET=Color(125,95,180)
        private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))
        private val assetPrepares=java.util.concurrent.atomic.AtomicInteger()
        internal fun assetPreparationCount():Int=assetPrepares.get()
        private val sharedMascot:BufferedImage? by lazy {
            OnlineListRenderer::class.java.getResourceAsStream("/online/HuHo.png")?.use { input ->
                val source=ImageIO.read(input)?:return@use null
                val image=BufferedImage(source.width,source.height,BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().also { it.drawImage(source,0,0,null);it.dispose() }
                val pixels=(image.raster.dataBuffer as java.awt.image.DataBufferInt).data
                for(i in pixels.indices)if((pixels[i] ushr 24)<36)pixels[i]=pixels[i] and 0x00ffffff
                assetPrepares.incrementAndGet()
                image
            }
        }
        private val sharedSymbols:Font? by lazy { runCatching {
            OnlineListRenderer::class.java.getResourceAsStream("/fonts/HuHoSymbols.ttf")?.use { Font.createFont(Font.TRUETYPE_FONT,it) }
        }.getOrNull() }
        private val sharedHeads:List<BufferedImage> by lazy {
            listOf("steve","alex","ari","efe","kai","makena","noor","sunny","zuri").mapNotNull { name ->
                OnlineListRenderer::class.java.getResourceAsStream("/online/head-$name.png")?.use { ImageIO.read(it) }
            }
        }
    }
    private val cols = columns.coerceIn(1,3)
    private val SIDE=(WIDTH-M*2-GAP*cols)/(cols+1)
    private val MAIN=cols*SIDE+(cols-1)*GAP
    private val family by lazy { agent.spigot.manager.FontManager.chooseFont(configuredFont) }
    private val mascot:BufferedImage? get()=sharedMascot
    private val symbolFont:Font? get()=sharedSymbols
    private val defaultHeads:List<BufferedImage> get()=sharedHeads
    data class Box(val x: Int,val y: Int,val w: Int,val h: Int)
    data class Layout(val players: Box,val sidebar: Box,val trend: Box,val metrics: Box,val footer: Box,val height: Int)

    internal fun playerBoxes(snapshot: ServerSnapshot): List<Box> =
        List(snapshot.players.size.coerceAtMost(maxRenderPlayers)) { index ->
            Box(M+(index%cols)*(SIDE+GAP), M+TITLE_HEIGHT+(index/cols)*(CARD_HEIGHT+ROW_GAP), SIDE, CARD_HEIGHT)
        }

    /** 左栏始终是玩家网格，右栏独占服务器资料；下排固定高度，不继承上方空隙。 */
    fun layout(s: ServerSnapshot): Layout {
        val probe=BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB).createGraphics()
        probe.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val infoHeight=try { sidebar(probe,s,false) } finally { probe.dispose() }
        val shown=s.players.size.coerceAtMost(maxRenderPlayers)
        val rows=maxOf(MIN_PLAYER_ROWS, (shown+cols-1)/cols)
        val playerHeight=TITLE_HEIGHT+rows*CARD_HEIGHT+(rows-1)*ROW_GAP
        val panelTop=M+maxOf(playerHeight,infoHeight)+GAP
        val footerTop=panelTop+PANEL_HEIGHT+8;val footerH=136
        return Layout(Box(M,M,MAIN,playerHeight),Box(M+MAIN+GAP,M,SIDE,infoHeight),
            Box(M,panelTop,MAIN,PANEL_HEIGHT),Box(M+MAIN+GAP,panelTop,SIDE,PANEL_HEIGHT),
            Box(0,footerTop,WIDTH,footerH),footerTop+footerH)
    }
    fun render(snapshot: ServerSnapshot): ByteArray {
        val shown=snapshot.players.take(maxRenderPlayers)
        val heads=avatarCache.loadAll(shown)
        val l=layout(snapshot)
        val image=BufferedImage(WIDTH,l.height,BufferedImage.TYPE_INT_RGB)
        val g=image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // 多段柔和底色，拒绝纯白画布；卡片稍透，让色彩与立绘从下面透出。
            g.paint=LinearGradientPaint(0f,0f,WIDTH.toFloat(),l.height.toFloat(),
                floatArrayOf(0f,0.48f,1f),arrayOf(Color(210,240,250),Color(226,232,250),Color(239,227,244)),
                MultipleGradientPaint.CycleMethod.NO_CYCLE)
            g.fillRect(0,0,WIDTH,l.height)
            // HuHo 娘放大、置中、柔和淡化为背景；只清理原图近透明边缘，不叠加外发光。
            mascot?.let { m ->
                val old=g.composite
                g.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.19f)
                val w=minOf(1180,(l.height*0.86*m.width/m.height).toInt())
                val h=(w.toDouble()*m.height/m.width).toInt()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(m,(WIDTH-w)/2,(l.height-h)/2,w,h,null)
                // 第二个淡化插画锚定左下，绘制在卡片底层而不是盖住趋势文字。
                val miniW=112;val miniH=(miniW.toDouble()*m.height/m.width).toInt()
                val clip=g.clip;g.clipRect(0,l.footer.y,200,l.footer.h)
                g.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.90f)
                g.drawImage(m,14,l.footer.y+4,miniW,miniH,null)
                g.clip=clip;g.composite=old
            }
            drawPlayers(g,snapshot,heads,l.players)
            card(g,l.sidebar)
            clipped(g,l.sidebar) { sidebar(g,snapshot,true) }
            drawTrend(g,l.trend)
            drawMetrics(g,snapshot,l.metrics)
            drawFooter(g,snapshot,l.footer)
        } finally { g.dispose() }
        return ByteArrayOutputStream().use { out -> ImageIO.write(image,"png",out);out.toByteArray() }
    }
    private fun drawPlayers(g: Graphics2D,s: ServerSnapshot,heads: Map<String,BufferedImage>,box: Box) {
        rich(g,title.ifBlank { s.configuredTitle.ifBlank { "在线列表" } },box.x,box.y+25,MAIN,24,1)
        if(s.players.isEmpty()) {
            val b=Box(box.x,box.y+36,MAIN,82);card(g,b)
            text(g,"当前没有玩家在线",b.x+18,b.y+34,19,true)
            text(g,"服务器仍在运行",b.x+18,b.y+59,15,false,MUTED);return
        }
        val boxes=playerBoxes(s)
        s.players.take(maxRenderPlayers).zip(boxes).forEach { (p,b) ->
            val x=b.x;val y=b.y;val cw=b.w
            drawPlayerCard(g,b,p)
            clipped(g,b) {
                val head=heads[p.uuid] ?: defaultHeads.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(p.uuid.hashCode(),it.size)] }
                if(head!=null) {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                    g.drawImage(roundHead(head),x+10,y+12,null)
                }
                drawPlayerName(g,p,x+57,y+23,cw-67)
                val status=playerStatus(p)
                g.font=font(statusSize(),true);g.color=if(p.administrator)Color(141,91,23)else Color(39,128,99)
                g.drawString(status,x+57,y+62)
                val ping=if(p.pingMs<0)"延迟 —"else "${p.pingMs} ms"
                val start=x+57+g.fontMetrics.stringWidth(status)+9
                g.font=font(latencySize(),true);g.color=latencyColour(p.pingMs);g.drawString(ping,start,y+62)
            }
        }
    }
    internal fun playerStatus(player:OnlineListPlayer):String=if(player.administrator)"ADMIN"else "ONLINE"
    internal fun latencySize():Int=15
    internal fun statusSize():Int=11
    internal fun highlighted(p:OnlineListPlayer):Boolean=p.administrator
    private fun drawPlayerCard(g:Graphics2D,b:Box,p:OnlineListPlayer){
        val admin=highlighted(p)
        val start=if(admin)Color(255,247,224,230)else Color(255,255,255,174)
        val end=if(admin)Color(255,226,204,226)else Color(224,242,253,183)
        val shape=RoundRectangle2D.Float(b.x.toFloat(),b.y.toFloat(),b.w.toFloat(),b.h.toFloat(),17f,17f)
        g.paint=GradientPaint(b.x.toFloat(),b.y.toFloat(),start,b.x.toFloat(),(b.y+b.h).toFloat(),end);g.fill(shape)
        g.color=if(admin)GOLD_EDGE else CARD_EDGE;g.stroke=BasicStroke(if(admin)2.2f else 1f);g.draw(shape)
        if(admin){g.color=Color(255,255,255,110);g.stroke=BasicStroke(.8f);g.drawRoundRect(b.x+3,b.y+3,b.w-6,b.h-6,13,13)}
        // No lines or badges above any avatar, including administrators.
    }

    fun latencyColour(ping: Int): Color = when { ping<0 -> MUTED; ping<=100 -> GREEN; ping<=200 -> Color(154,96,16); else -> RED }

    /** 0..1 fill contracts used by the visible three progress bars. MSPT measures load against 100 ms. */
    fun performanceFractions(snapshot:ServerSnapshot):List<Double> = listOf(
        ((snapshot.tps?:0.0)/20.0).coerceIn(0.0,1.0),
        snapshot.mspt?.let { (it/50.0).coerceIn(0.0,1.0) } ?: 0.0,
        if(snapshot.memoryMaxMb>0)(snapshot.memoryUsedMb.toDouble()/snapshot.memoryMaxMb).coerceIn(0.0,1.0)else 0.0
    )

    private fun sidebar(g: Graphics2D,s: ServerSnapshot,draw: Boolean,detailsStart:(Int)->Unit={}): Int {
        val x=M+MAIN+GAP+20;val w=SIDE-40;var y=M+16
        if(s.icon!=null) {
            if(draw) {
                val oldClip=g.clip
                g.clip=java.awt.geom.RoundRectangle2D.Float(x.toFloat(),y.toFloat(),52f,52f,14f,14f)
                g.drawImage(s.icon,x,y,52,52,null);g.clip=oldClip
                g.color=BORDER;g.stroke=BasicStroke(1.2f);g.drawRoundRect(x,y,52,52,14,14)
            }
        }
        val tx=if(s.icon==null)x else x+64
        val nw=if(s.icon==null)w else w-64
        val lines=rich(g,s.serverName,tx,y+22,nw,22,3,draw)
        y+=if(s.icon==null)lines*28+7 else maxOf(56,lines*28+5)
        val count="在线玩家 ${s.totalOnlinePlayers} / ${s.maxPlayers}"
        y+=rich(g,"<bold>$count</bold>",x,y+20,w,17,2,draw)*23+6
        // 两行 JSON/MiniMessage MOTD 换行保留；侧栏窄时可自然延伸，所有已测行计入高度。
        if(s.motd.isNotBlank()) {
            val motdPlain=McTextParser.plainText(s.motd)
            // 长 MOTD：缩小字号并允许多行，尽量完整呈现，不挤占下方信息。
            val motdSize=if(motdPlain.length>90)15 else 17
            val motdLines=if(motdPlain.length>90)12 else 8
            y+=rich(g,s.motd.replace("\\n","\n"),x,y+20,w,motdSize,motdLines,draw)*(motdSize+6)+7
        }
        if(s.serverIp.isNotBlank()) { y+=rich(g,"<bold>服务器地址：</bold>${s.serverIp}",x,y+20,w,17,3,draw)*23+7 }
        for(line in customLines.filter { McTextParser.plainText(it).isNotBlank() }.take(6))
            y+=rich(g,line,x,y+18,w,16,5,draw)*22+5
        val version=Regex("\\d+\\.\\d+(?:\\.\\d+)?").find(s.coreVersion)?.value.orEmpty()
        val facts=listOf("核心" to s.brand, "版本" to version,"协议" to s.protocolVersion,
            "兼容" to s.viaRange).filter { it.second.isNotBlank() }
        if(facts.isNotEmpty())y+=4
        detailsStart(y)
        for((label,value) in facts) {
            val used=rich(g,"<bold>$label</bold> · $value",x,y+18,w,16,4,draw)
            y+=used*22+5
        }
        return (y-M+12).coerceAtLeast(150)
    }
    internal fun detailsTop(s:ServerSnapshot):Int {
        val g=BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB).createGraphics();var result=0
        try{sidebar(g,s,false){result=it}}finally{g.dispose()};return result
    }

    internal fun metricColour(index:Int,s:ServerSnapshot):Color {
        val severity=when(index){
            0->s.tps?.takeIf { it.isFinite() }?.let { ((20.0-it)/8.0).coerceIn(0.0,1.0) }
            1->s.mspt?.takeIf { it.isFinite() }?.let { ((it-20.0)/50.0).coerceIn(0.0,1.0) }
            else->if(s.memoryMaxMb<=0)null else ((s.memoryUsedMb.toDouble()/s.memoryMaxMb-.50)/.45).coerceIn(0.0,1.0)
        }?:return MUTED
        val green=if(index==1)Color(25,139,155)else Color(24,147,113)
        val amber=Color(194,138,40);val red=Color(208,53,76)
        val from=if(severity<.5)green else amber;val to=if(severity<.5)amber else red
        val t=if(severity<.5)severity*2 else (severity-.5)*2
        return Color((from.red+(to.red-from.red)*t).toInt(),(from.green+(to.green-from.green)*t).toInt(),(from.blue+(to.blue-from.blue)*t).toInt())
    }

    private fun drawMetrics(g: Graphics2D,s: ServerSnapshot,b: Box) {
        card(g,b,CARD)
        text(g,"实时性能",b.x+18,b.y+29,21,true)
        val used=s.memoryUsedMb.toDouble().coerceAtLeast(0.0)
        val max=s.memoryMaxMb.toDouble().coerceAtLeast(0.0)
        val ratios=performanceFractions(s)
        val memoryRatio=ratios[2]
        val data=listOf(
            Triple("TPS",s.tps?.let { String.format(java.util.Locale.ROOT,"%.1f",it.coerceAtMost(20.0)) }.orEmpty(),ratios[0]),
            Triple("MSPT",s.mspt?.let { String.format(java.util.Locale.ROOT,"%.1f ms",it) }.orEmpty(),ratios[1]),
            Triple("内存",if(max>0)String.format(java.util.Locale.ROOT,"%.1f / %.1f GiB",used/1024,max/1024) else "—",ratios[2])
        )
        val barW=b.w-38
        data.forEachIndexed { i,item ->
            val rowY=b.y+52+i*49
            text(g,item.first,b.x+18,rowY,15,false,MUTED)
            val value=item.second.ifBlank { "—" }
            g.font=font(18,true)
            val valueColour=metricColour(i,s)
            g.color=valueColour
            g.drawString(value,b.x+b.w-18-g.fontMetrics.stringWidth(value),rowY)
            val trackY=rowY+8
            g.color=TRACK;g.fillRoundRect(b.x+18,trackY,barW,8,8,8)
            val fill=(barW*item.third).toInt().coerceIn(0,barW)
            if(fill>0){
                val barColor=metricColour(i,s)
                g.paint=GradientPaint(b.x+18f,trackY.toFloat(),barColor,b.x+18f+fill,trackY.toFloat(),
                    barColor.brighter())
                g.fillRoundRect(b.x+18,trackY,fill,8,8,8)
                g.paint=Color.BLACK
            }
        }
    }

    private fun drawTrend(g: Graphics2D,b: Box) {
        card(g,b,CARD)
        text(g,"在线趋势 · 最近 48 小时",b.x+18,b.y+29,21,true)
        val pts=OnlineHistoryStore.recent(48)
        if(pts.size<2){text(g,"正在记录在线人数，进出服立即采样",b.x+18,b.y+91,18,false,MUTED);return}
        val x=b.x+43;val y=b.y+47;val w=b.w-57;val h=b.h-85
        val top=maxOf(1,pts.maxOf { it.second });val first=pts.first().first;val span=maxOf(1L,pts.last().first-first)
        val xy=pts.map { p ->
            x+w.toDouble()*(p.first-first)/span to y+h-h.toDouble()*p.second/top
        }
        g.stroke=BasicStroke(1f)
        listOf(0,top/4,top/2,(top*3)/4,top).distinct().forEach { n ->
            val yy=y+h-(h.toDouble()*n/top).toInt()
            g.color=BORDER;g.drawLine(x,yy,x+w,yy);text(g,n.toString(),b.x+14,yy+5,14,false,MUTED)
        }
        val curve=Path2D.Double();curve.moveTo(xy.first().first,xy.first().second)
        val secants=(0 until xy.lastIndex).map { i ->
            (xy[i+1].second-xy[i].second)/maxOf(1e-9,xy[i+1].first-xy[i].first)
        }
        val slopes=DoubleArray(xy.size)
        slopes[0]=secants.first();slopes[slopes.lastIndex]=secants.last()
        for(i in 1 until xy.lastIndex){
            val left=secants[i-1];val right=secants[i]
            slopes[i]=if(left*right<=0.0)0.0 else {
                val dx0=xy[i].first-xy[i-1].first;val dx1=xy[i+1].first-xy[i].first
                val weighted=(left*dx1+right*dx0)/(dx0+dx1)
                kotlin.math.sign(weighted)*minOf(kotlin.math.abs(weighted),3.0*minOf(kotlin.math.abs(left),kotlin.math.abs(right)))
            }
        }
        for(i in 0 until xy.lastIndex){
            val p=xy[i];val q=xy[i+1];val dx=q.first-p.first
            var cy1=p.second+slopes[i]*dx/3.0
            var cy2=q.second-slopes[i+1]*dx/3.0
            val low=minOf(p.second,q.second);val high=maxOf(p.second,q.second)
            cy1=cy1.coerceIn(low,high);cy2=cy2.coerceIn(low,high)
            curve.curveTo(p.first+dx/3.0,cy1,q.first-dx/3.0,cy2,q.first,q.second)
        }
        val area=Path2D.Double(curve);area.lineTo(xy.last().first,(y+h).toDouble());area.lineTo(xy.first().first,(y+h).toDouble());area.closePath()
        g.paint=GradientPaint(0f,y.toFloat(),Color(43,157,169,105),0f,(y+h).toFloat(),Color(97,145,206,8))
        g.fill(area)
        g.color=TEAL;g.stroke=BasicStroke(3f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);g.draw(curve)
        val peakIndex=pts.indices.maxBy { pts[it].second };val peak=pts[peakIndex];val peakXY=xy[peakIndex]
        g.color=Color.WHITE;g.fill(Ellipse2D.Double(peakXY.first-6,peakXY.second-6,12.0,12.0))
        g.color=TEAL;g.stroke=BasicStroke(2f);g.draw(Ellipse2D.Double(peakXY.first-6,peakXY.second-6,12.0,12.0))
        val label="峰值 ${peak.second} 人"
        g.font=font(16,true);g.color=MUTED;g.drawString(label,b.x+b.w-18-g.fontMetrics.stringWidth(label),b.y+29)
        val labels=trendTimeLabels(pts,7,ZoneId.of("Asia/Shanghai"))
        g.font=font(11);g.color=MUTED
        labels.forEach { (fraction,label) ->
            val at=x+(w*fraction).toInt();val width=g.fontMetrics.stringWidth(label)
            val left=(at-width/2).coerceIn(x,x+w-width)
            g.drawString(label,left,b.y+b.h-11)
        }
    }

    internal fun trendTimeLabels(points:List<Pair<Long,Int>>,slots:Int=7,zone:ZoneId=ZoneId.of("Asia/Shanghai")):List<Pair<Double,String>> {
        if(points.isEmpty())return emptyList()
        val first=points.first().first;val last=points.last().first
        val span=(last-first).coerceAtLeast(0L)
        val count=if(span==0L)1 else slots.coerceIn(2,7)
        val formatter=DateTimeFormatter.ofPattern(if(span<120000L)"HH:mm:ss" else "MM-dd HH:mm").withZone(zone)
        return (0 until count).map { i ->
            val fraction=if(count==1)0.0 else i.toDouble()/(count-1)
            fraction to formatter.format(Instant.ofEpochMilli(first+(span*fraction).toLong()))
        }.distinctBy { it.second }
    }

    private fun drawFooter(g: Graphics2D,s: ServerSnapshot,b: Box) {
        center(g,footerText.ifBlank { "Powered by HuHoBot" },b.y+34,16)
        if(s.totalPages>1)center(g,"第 ${s.currentPage} / ${s.totalPages} 页",b.y+58,15,true)
        center(g,"更新 ${TIME.format(Instant.ofEpochMilli(s.capturedAt))}",b.y+84,13)
        if(s.players.size>maxRenderPlayers)center(g,"其余玩家见文字名单",b.y+110,13)
    }

    private fun roundHead(head: BufferedImage): BufferedImage = BufferedImage(38,38,BufferedImage.TYPE_INT_ARGB).also { tile ->
        val mask=tile.createGraphics()
        try {
            mask.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
            mask.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            mask.color=Color.WHITE
            mask.fillRoundRect(0,0,38,38,12,12)
            mask.composite=AlphaComposite.SrcIn
            mask.drawImage(head,0,0,38,38,null)
        } finally { mask.dispose() }
    }

    private fun drawPlayerName(g: Graphics2D,p: OnlineListPlayer,x: Int,base: Int,w: Int) {
        val display=p.displayName.ifBlank { p.prefix+p.name+p.suffix }
        val segments=McTextParser.parse(display.take(768),INK)
        val plain=segments.joinToString(""){it.text}
        val nameAt=plain.lastIndexOf(p.name).takeIf { it>=0 && p.name.isNotEmpty() }
        fun slice(start:Int,end:Int):List<TextSegment> {
            if(end<=start)return emptyList()
            var offset=0
            return segments.mapNotNull { segment ->
                val from=maxOf(start-offset,0);val to=minOf(end-offset,segment.text.length)
                offset+=segment.text.length
                if(from<to)segment.copy(text=segment.text.substring(from,to))else null
            }
        }
        // IDs are atomic identities, not ordinary words. Never wrap one around a long title.
        val identity=if(nameAt==null)listOf(TextSegment(p.name,INK))else slice(nameAt,nameAt+p.name.length)
        val metadata=if(nameAt==null)segments else slice(0,nameAt)+slice(nameAt+p.name.length,plain.length)
        val size=listOf(18,16,14).firstOrNull { !wrapRich(g,identity,w,it,1,true).trimmed } ?: 14
        richSegments(g,identity,x,base,w,size,1,bold=true)
        if(metadata.any { it.text.isNotBlank() })richSegments(g,metadata,x,base+20,w,14,1)
    }

    private data class Glyph(val value:String,val style:TextSegment,val width:Int)
    private data class Wrapped(val rows:List<List<Glyph>>,val trimmed:Boolean)

    private fun wrapRich(g:Graphics2D,segments:List<TextSegment>,w:Int,size:Int,maxLines:Int,bold:Boolean):Wrapped {
        val rows=mutableListOf(mutableListOf<Glyph>());var width=0;var trimmed=false
        val plain=segments.joinToString(""){it.text}
        val breaker=java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);breaker.setText(plain)
        var segment=0;var segmentEnd=segments.firstOrNull()?.text?.length?:0
        var begin=breaker.first();var end=breaker.next()
        while(end!=java.text.BreakIterator.DONE){
            while(segment<segments.lastIndex&&begin>=segmentEnd){segment++;segmentEnd+=segments[segment].text.length}
            val seg=segments.getOrNull(segment)?:break
            val txt=plain.substring(begin,end);begin=end;end=breaker.next()
            if(txt=="\r")continue
            if(txt.contains('\n')) {
                if(rows.size>=maxLines){trimmed=true;break}
                rows.add(mutableListOf());width=0;continue
            }
            g.font=glyphFont(txt,size,bold||seg.bold,seg.italic);val cw=g.fontMetrics.stringWidth(txt)
            if(width+cw>w&&rows.last().isNotEmpty()){
                if(rows.size>=maxLines){trimmed=true;break}
                rows.add(mutableListOf());width=0
            }
            rows.last().add(Glyph(txt,seg,cw));width+=cw
        }
        if(trimmed) {
            val row=rows.last();val style=row.lastOrNull()?.style ?: segments.first()
            val dots=g.getFontMetrics(glyphFont("…",size,bold||style.bold,style.italic)).stringWidth("…")
            while(row.isNotEmpty()&&width+dots>w)width-=row.removeAt(row.lastIndex).width
            row.add(Glyph("…",style,dots))
        }
        return Wrapped(rows,trimmed)
    }
    /** 先测每个字素，再换行或省略；同一行的样式和渐变不会被重新拼接。 */
    private fun rich(g:Graphics2D,raw:String,x:Int,base:Int,w:Int,size:Int,maxLines:Int,draw:Boolean=true,bold:Boolean=false):Int =
        richSegments(g,McTextParser.parse(raw.take(1536),INK),x,base,w,size,maxLines,draw,bold)

    private fun richSegments(g:Graphics2D,segments:List<TextSegment>,x:Int,base:Int,w:Int,size:Int,maxLines:Int,draw:Boolean=true,bold:Boolean=false):Int {
        val rows=wrapRich(g,segments,w,size,maxLines,bold).rows
        if(draw) {
            val old=g.clip;g.clipRect(x,base-size-3,w,maxLines*(size+6)+6)
            rows.forEachIndexed { row,letters -> var xx=x;val yy=base+row*(size+6)
                for(ch in letters){g.font=glyphFont(ch.value,size,bold||ch.style.bold,ch.style.italic);g.color=ch.style.color
                    if(ch.style.color.red*0.2126+ch.style.color.green*0.7152+ch.style.color.blue*0.0722>215) {
                        val shape=g.font.createGlyphVector(g.fontRenderContext,ch.value).getOutline(xx.toFloat(),yy.toFloat())
                        g.color=Color(85,103,126);g.stroke=BasicStroke(1.4f);g.draw(shape);g.color=ch.style.color
                    }
                    g.drawString(ch.value,xx,yy)
                    if(ch.style.underline)g.drawLine(xx,yy+2,xx+ch.width,yy+2)
                    if(ch.style.strike)g.drawLine(xx,yy-size/3,xx+ch.width,yy-size/3)
                    xx+=ch.width}
            };g.clip=old
        }
        return rows.size
    }
    private fun text(g:Graphics2D,t:String,x:Int,y:Int,size:Int,bold:Boolean=false,c:Color=INK){g.font=font(size,bold);g.color=c;g.drawString(t,x,y)}
    private fun center(g:Graphics2D,t:String,y:Int,size:Int,bold:Boolean=false){g.font=font(size,bold);g.color=MUTED;val safe=t.take(90);g.drawString(safe,(WIDTH-g.fontMetrics.stringWidth(safe))/2,y)}
    private fun glyphFont(text:String,size:Int,bold:Boolean,italic:Boolean):Font =
        agent.spigot.manager.FontManager.fontFor(text,family,(if(bold)Font.BOLD else 0)or(if(italic)Font.ITALIC else 0),size)
    private fun font(size:Int,bold:Boolean=false,italic:Boolean=false):Font =
        agent.spigot.manager.FontManager.font(family,(if(bold)Font.BOLD else 0)or(if(italic)Font.ITALIC else 0),size)
    private fun card(g:Graphics2D,b:Box,fill:Color=CARD){val r=RoundRectangle2D.Float(b.x.toFloat(),b.y.toFloat(),b.w.toFloat(),b.h.toFloat(),18f,18f);g.paint=GradientPaint(b.x.toFloat(),b.y.toFloat(),fill,(b.x+b.w).toFloat(),(b.y+b.h).toFloat(),Color(226,239,253,fill.alpha));g.fill(r);g.color=BORDER;g.stroke=BasicStroke(1f);g.draw(r)}
    private fun clipped(g:Graphics2D,b:Box,action:()->Unit){val old=g.clip;g.clipRect(b.x,b.y,b.w,b.h);try{action()}finally{g.clip=old}}
    private fun resource(path:String):BufferedImage?=runCatching { javaClass.getResourceAsStream(path)?.use { ImageIO.read(it) } }.getOrNull()
}
