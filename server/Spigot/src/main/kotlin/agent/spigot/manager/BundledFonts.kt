package agent.spigot.manager

import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** Packed resources decode to the original sfnt bytes. No temporary plugin file, disk cache or network. */
internal object BundledFonts {
    private val packed = linkedMapOf(
        "HuHoCJK.otf" to 8785260,
        "HuHoSans.ttf" to 374792,
        "HuHoSymbolsWide.ttf" to 677104,
        "HuHoEmoji.ttf" to 863164
    )
    val names: List<String> = packed.keys.toList() + "HuHoSymbols.ttf"

    fun open(name: String): InputStream? {
        val length = packed[name]
        if (length == null) {
            return if (name == "HuHoSymbols.ttf") javaClass.getResourceAsStream("/fonts/$name") else null
        }
        val resource = javaClass.getResourceAsStream("/fonts/$name.xz") ?: return null
        val bytes = ByteArray(length)
        resource.use { source ->
            DataInputStream(XZInputStream(source, 8192)).use { stream ->
                stream.readFully(bytes)
                // Read through the stream footer/CRC and reject unexpected trailing decompressed data.
                if (stream.read() != -1) throw IOException("Unexpected bundled font size: $name")
            }
        }
        return ByteArrayInputStream(bytes)
    }
}
