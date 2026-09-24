package cn.huohuas001.huhobotPenguin.spigot.inventory

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

object DefaultPlayerSkinProvider {
    private val names = arrayOf("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri")
    private const val SKIN_COUNT = 18
    private const val WIDE_STEVE_INDEX = 15
    private val skins = ConcurrentHashMap<Int, PlayerSkin>()

    fun defaultSkin(uuid: UUID?, bukkitVersion: String): PlayerSkin = skinAt(indexFor(uuid, bukkitVersion))

    fun defaultSkin(): PlayerSkin = skinAt(WIDE_STEVE_INDEX)

    internal fun indexFor(uuid: UUID?, bukkitVersion: String): Int {
        if (uuid == null) return WIDE_STEVE_INDEX
        if (usesLegacyDefaults(bukkitVersion)) {
            return if (uuid.hashCode() and 1 == 0) WIDE_STEVE_INDEX else 0
        }
        return Math.floorMod(uuid.hashCode(), SKIN_COUNT)
    }

    private fun usesLegacyDefaults(version: String): Boolean {
        val match = Regex("^1\\.(\\d+)(?:\\.(\\d+))?").find(version) ?: return false
        val minor = match.groupValues[1].toIntOrNull() ?: return false
        val patch = match.groupValues[2].toIntOrNull() ?: 0
        return minor < 19 || (minor == 19 && patch < 3)
    }

    private fun skinAt(index: Int): PlayerSkin = skins.computeIfAbsent(index) {
        val slim = index < names.size
        val model = if (slim) "slim" else "wide"
        val name = names[index % names.size]
        val resource = "inventory/default-skins/$model/$name.png"
        val bytes = DefaultPlayerSkinProvider::class.java.classLoader
            .getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("缺少默认玩家皮肤: $resource")
        val image = ImageIO.read(bytes.inputStream())
            ?: error("默认玩家皮肤无法读取: $resource")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        PlayerSkin(image, digest, "BUNDLED_DEFAULT_$name", slim)
    }
}
