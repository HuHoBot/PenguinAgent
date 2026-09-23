package cn.huohuas001.huhobotPenguin.spigot.inventory

import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.net.URI
import java.net.URL

data class ServerSkinProfile(val textureUrl: String, val slim: Boolean) {
    companion object {
        fun capture(player: Player): ServerSkinProfile? {
            val profile = runCatching {
                OfflinePlayer::class.java.getMethod("getPlayerProfile").invoke(player)
            }.getOrNull() ?: runCatching {
                player.javaClass.getMethod("getPlayerProfile").invoke(player)
            }.getOrNull()
            return fromProfile(profile)
        }

        fun fromProfile(profile: Any?): ServerSkinProfile? = runCatching {
            if (profile == null) return@runCatching null
            val textures = profile.javaClass.getMethod("getTextures").invoke(profile)
                ?: return@runCatching null
            val skin = textures.javaClass.getMethod("getSkin").invoke(textures) as? URL
                ?: return@runCatching null
            val model = textures.javaClass.getMethod("getSkinModel").invoke(textures)
            fromUrl(skin.toString(), model?.toString().equals("SLIM", true))
        }.getOrNull()

        fun fromUrl(raw: String?, slim: Boolean): ServerSkinProfile? = runCatching {
            if (raw.isNullOrBlank() || raw.length > 256) return@runCatching null
            val uri = URI(raw)
            if (!uri.host.equals("textures.minecraft.net", true) || uri.port != -1 ||
                uri.userInfo != null || uri.query != null || uri.fragment != null ||
                (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) ||
                !uri.path.matches(Regex("/texture/[a-zA-Z0-9]{32,128}"))
            ) return@runCatching null
            ServerSkinProfile("https://textures.minecraft.net${uri.path}", slim)
        }.getOrNull()
    }
}
