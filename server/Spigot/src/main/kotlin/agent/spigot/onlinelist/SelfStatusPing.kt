package agent.spigot.onlinelist

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 通过 Server List Ping 向本机服务器查询状态（与客户端按下"刷新"看到的信息一致）。
 *
 * 相比直接读 server.properties / server-icon.png，这种方式能拿到
 * **插件改写后的真实 MOTD**（如 MiniMOTD、彩色 hex MOTD）以及 ping 时下发的图标，
 * 与玩家在多人游戏列表里看到的效果完全一致。
 */
object SelfStatusPing {

    data class Info(
        val motd: String,
        val icon: BufferedImage?,
        val versionName: String?,
        val protocol: Int?
    )

    private const val CACHE_MS = 30_000L

    @Volatile
    private var cache: Info? = null

    @Volatile
    private var cachedAt = 0L

    @Volatile private var activeSocket:Socket?=null
    fun cancelRefresh(){runCatching { activeSocket?.close() };activeSocket=null;invalidate()}

    /** 只读缓存（不发起网络请求，可在主线程安全调用）。 */
    fun cached(): Info? {
        val cached = cache
        return if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) cached else null
    }

    /** 后台刷新（会发起 Socket 请求，只在异步线程调用）。 */
    fun refresh(port: Int) {
        if(Thread.currentThread().isInterrupted)return
        val info = runCatching { ping("127.0.0.1", port) }.getOrNull() ?: return
        if(Thread.currentThread().isInterrupted)return
        cache = info
        cachedAt = System.currentTimeMillis()
    }

    fun invalidate() {
        cache = null
        cachedAt = 0L
    }

    private fun ping(host: String, port: Int): Info? {
        val socket=Socket()
        activeSocket=socket
        try {
            return socket.use {
                socket.connect(InetSocketAddress(host, port),1500)
                socket.soTimeout=2000
                val output=DataOutputStream(socket.getOutputStream())
                val input=DataInputStream(socket.getInputStream())
                val handshake=ByteArrayOutputStream()
                val handshakeOut=DataOutputStream(handshake)
                writeVarInt(handshakeOut,0x00)
                writeVarInt(handshakeOut,767)
                writeString(handshakeOut,host)
                handshakeOut.writeShort(port)
                writeVarInt(handshakeOut,1)
                writeVarInt(output,handshake.size())
                output.write(handshake.toByteArray())
                writeVarInt(output,1);writeVarInt(output,0x00);output.flush()
                readVarInt(input);readVarInt(input)
                val length=readVarInt(input)
                if(length<=0||length>2_000_000)return null
                val bytes=ByteArray(length);input.readFully(bytes)
                val root=JSON.parseObject(String(bytes,StandardCharsets.UTF_8))?:return null
                val description=root.get("description")
                val motd=if(description is String)description else runCatching {
                    val component=net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                        .deserialize(JSON.toJSONString(description))
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(component)
                }.getOrDefault("")
                val icon=(root.getString("favicon")?:root.getJSONObject("favicon")?.toString())?.let(::decodeFavicon)
                val version=root.getJSONObject("version")
                Info(motd,icon,version?.getString("name"),version?.getInteger("protocol"))
            }
        } finally {
            if(activeSocket===socket)activeSocket=null
        }
    }

    private fun decodeFavicon(value: String): BufferedImage? = runCatching {
        val raw = value.substringAfter("base64,", value)
        val bytes = Base64.getDecoder().decode(raw)
        ImageIO.read(ByteArrayInputStream(bytes))
    }.getOrNull()

    private fun writeVarInt(output: DataOutputStream, value: Int) {
        var current = value
        while (true) {
            val bits = current and 0x7F
            current = current ushr 7
            if (current != 0) output.write(bits or 0x80) else { output.write(bits); break }
        }
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var position = 0
        while (true) {
            val read = input.readByte().toInt()
            value = value or ((read and 0x7F) shl position)
            if (read and 0x80 == 0) return value
            position += 7
            if (position > 35) return value
        }
    }

    private fun writeString(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(output, bytes.size)
        output.write(bytes)
    }
}
