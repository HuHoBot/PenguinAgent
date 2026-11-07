package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import org.bukkit.entity.Player
import java.util.Base64

/**
 * 从 Bukkit/Paper PlayerProfile 或 CraftPlayer GameProfile 中读取当前皮肤材质地址。
 */
object SkinResolver {
    private val TEXTURE_URL = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")

    fun resolve(player: Player): String? {
        modernProfile(player)?.let { return normalize(it) }
        legacyProfile(player)?.let { return normalize(it) }
        return null
    }

    private fun modernProfile(player: Player): String? {
        return try {
            val profile = player.javaClass.getMethod("getPlayerProfile").invoke(player)
            val textures = profile!!.javaClass.getMethod("getTextures").invoke(profile)
            val skin = textures!!.javaClass.getMethod("getSkin").invoke(textures)
            skin?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun legacyProfile(player: Player): String? {
        return try {
            val profile = player.javaClass.getMethod("getProfile").invoke(player)
            val properties = profile!!.javaClass.getMethod("getProperties").invoke(profile)
            val values = properties!!.javaClass
                .getMethod("get", String::class.java)
                .invoke(properties, "textures")
            if (values !is Collection<*>) return null
            for (property in values) {
                val encoded = property!!.javaClass.getMethod("getValue").invoke(property)
                if (encoded == null) continue
                val json = String(Base64.getDecoder().decode(encoded.toString()), Charsets.UTF_8)
                val match = TEXTURE_URL.find(json)
                if (match != null) return match.groupValues[1].replace("\\/", "/")
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalize(url: String): String =
        if (url.startsWith("http://textures.minecraft.net/")) "https://" + url.substring("http://".length)
        else url
}
