package cn.huohuas001.huhobotPenguin.spigot.onlinelist

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

    /** 只读缓存（不发起网络请求，可在主线程安全调用）。 */
    fun cached(): Info? {
        val cached = cache
        return if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) cached else null
    }

    /** 后台刷新（会发起 Socket 请求，只在异步线程调用）。 */
    fun refresh(port: Int) {
        val info = runCatching { ping("127.0.0.1", port) }.getOrNull() ?: return
        cache = info
        cachedAt = System.currentTimeMillis()
    }

    fun invalidate() {
        cache = null
        cachedAt = 0L
    }

    private fun ping(host: String, port: Int): Info? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 1500)
            socket.soTimeout = 2000
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // handshake
            val handshake = ByteArrayOutputStream()
            val handshakeOut = DataOutputStream(handshake)
            writeVarInt(handshakeOut, 0x00)
            writeVarInt(handshakeOut, 767)          // 协议号（仅用于查询，任意值均可）
            writeString(handshakeOut, host)
            handshakeOut.writeShort(port)
            writeVarInt(handshakeOut, 1)            // next state = status
            writeVarInt(output, handshake.size())
            output.write(handshake.toByteArray())

            // status request
            writeVarInt(output, 1)
            writeVarInt(output, 0x00)
            output.flush()

            readVarInt(input)                        // packet length
            readVarInt(input)                        // packet id
            val length = readVarInt(input)
            if (length <= 0 || length > 2_000_000) return null
            val bytes = ByteArray(length)
            input.readFully(bytes)
            val root = JSON.parseObject(String(bytes, StandardCharsets.UTF_8)) ?: return null

            val description = root.get("description")
            val motd = when (description) {
                is String -> description
                is JSONObject -> componentToLegacy(description)
                else -> root.getString("description").orEmpty()
            }
            val icon = (root.getString("favicon") ?: root.getJSONObject("favicon")?.toString())
                ?.let { decodeFavicon(it) }
            val version = root.getJSONObject("version")
            return Info(
                motd = motd,
                icon = icon,
                versionName = version?.getString("name"),
                protocol = version?.getInteger("protocol")
            )
        }
    }

    /** JSON 聊天组件 → § 代码文本（text + extra + color + bold）。 */
    private fun componentToLegacy(component: JSONObject, depth: Int = 0): String {
        if (depth > 8) return ""
        val builder = StringBuilder()
        val color = component.getString("color")
        if (color != null) {
            val legacy = legacyColorOf(color)
            if (legacy != null) builder.append('§').append(legacy)
            else if (color.startsWith("#")) {
                builder.append('§').append('x')
                color.removePrefix("#").forEach { builder.append('§').append(it) }
            }
        }
        if (component.getBooleanValue("bold")) builder.append("§l")
        if (component.getBooleanValue("italic")) builder.append("§o")
        if (component.getBooleanValue("underlined")) builder.append("§n")
        if (component.getBooleanValue("strikethrough")) builder.append("§m")
        builder.append(component.getString("text").orEmpty())
        val extra = component.get("extra")
        if (extra is JSONArray) {
            for (index in 0 until extra.size) {
                (extra[index] as? JSONObject)?.let { builder.append(componentToLegacy(it, depth + 1)) }
            }
        }
        return builder.toString()
    }

    private fun legacyColorOf(name: String): Char? = when (name.lowercase()) {
        "black" -> '0'; "dark_blue" -> '1'; "dark_green" -> '2'; "dark_aqua" -> '3'
        "dark_red" -> '4'; "dark_purple" -> '5'; "gold" -> '6'; "gray", "grey" -> '7'
        "dark_gray", "dark_grey" -> '8'; "blue" -> '9'; "green" -> 'a'; "aqua" -> 'b'
        "red" -> 'c'; "light_purple" -> 'd'; "yellow" -> 'e'; "white" -> 'f'
        else -> null
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
