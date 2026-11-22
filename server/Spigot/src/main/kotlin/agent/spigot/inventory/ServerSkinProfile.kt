package agent.spigot.inventory

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
            return fromProfile(profile) ?: fromUrl(agent.spigot.onlinelist.SkinResolver.resolve(player), false)
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
            if (raw.isNullOrBlank() || raw.length > 2048) return@runCatching null
            val uri = URI(raw)
            // DNS and every redirect are validated by SkinSources on the worker, never on the tick thread.
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
                uri.port !in -1..65535 || (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true))
            ) return@runCatching null
            ServerSkinProfile(raw.replace("http://textures.minecraft.net/", "https://textures.minecraft.net/"), slim)
        }.getOrNull()
    }
}
