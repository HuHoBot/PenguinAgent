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
        // SkinsRestorer 官方 API（离线服装 SR 后，玩家皮肤在 SR 存储里）
        skinsRestorerApiTexture(player)?.let { return it }
        legacyProfile(player)?.let { return normalize(it) }
        // SkinsRestorer（经 PlaceholderAPI，兼容未装 API 直连的环境）
        skinsRestorerTexture(player)?.let { return it }
        return null
    }

    /**
     * 通过 SkinsRestorer 官方 API 反射取皮肤纹理：
     * SkinsRestorerProvider.get() → getPlayerStorage().getSkinIdOfPlayer(uuid)
     *   → getSkinStorage().findSkinData(skinId) → getSkinTextureUrl()
     * 兼容 14.x/15.x 的多种方法名；任何一步失败都返回 null。
     */
    private fun skinsRestorerApiTexture(player: Player): String? = runCatching {
        val providerClass = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider")
        val api = providerClass.getMethod("get").invoke(null) ?: return null
        val playerStorage = api.javaClass.methods.firstOrNull { it.name == "getPlayerStorage" }?.invoke(api)
            ?: return null
        val skinId = playerStorage.javaClass.methods
            .firstOrNull { it.name == "getSkinIdOfPlayer" }
            ?.invoke(playerStorage, player.uniqueId) as? String
        if (skinId.isNullOrBlank()) return null
        val skinStorage = api.javaClass.methods.firstOrNull { it.name == "getSkinStorage" }?.invoke(api)
            ?: return null
        val finder = skinStorage.javaClass.methods.firstOrNull {
            it.name == "findSkinData" || it.name == "getSkinData"
        } ?: return null
        val raw = finder.invoke(skinStorage, skinId)
        val skinData = (raw as? java.util.Optional<*>)?.orElse(null) ?: raw ?: return null
        listOf("getSkinTextureUrl", "getSkin", "getTextureUrl").firstNotNullOfOrNull { name ->
            runCatching { skinData.javaClass.getMethod(name).invoke(skinData) as? String }.getOrNull()
        }?.takeIf { it.startsWith("http") }
    }.getOrNull()

    /**
     * 通过 PlaceholderAPI 读取 SkinsRestorer 的皮肤纹理地址。
     * 未安装 PlaceholderAPI 或 SkinsRestorer 时安全返回 null。
     */
    private fun skinsRestorerTexture(player: Player): String? = runCatching {
        val papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
        val result = papi
            .getMethod("setPlaceholders", org.bukkit.entity.Player::class.java, String::class.java)
            .invoke(null, player, "%skinsrestorer_texture_url_or_empty%") as? String
        val url = result?.trim().orEmpty()
        if (url.isEmpty() || url.equals("Error", true) || url.startsWith("%")) null else url
    }.getOrNull()

    private fun modernProfile(player: Player): String? {
        return try {
            val profile = player.javaClass.getMethod("getPlayerProfile").invoke(player)
            val textures = profile!!.javaClass.getMethod("getTextures").invoke(profile)
            val skin = textures!!.javaClass.getMethod("getSkin").invoke(textures) ?: return null
            // Paper 的 PlayerSkin 不是 String：必须调用 getURL()/getTextureUrl() 之类取真实地址，
            // 早期版本用 toString() 得到的是对象描述，导致皮肤永远下载失败（头像全变默认头像）。
            listOf("getURL", "getUrl", "getTextureUrl", "getTextureURL").firstNotNullOfOrNull { name ->
                runCatching { skin.javaClass.getMethod(name).invoke(skin) as? String }.getOrNull()
            }?.takeIf { it.startsWith("http") }
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
