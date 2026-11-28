package agent.spigot.onlinelist

import org.bukkit.entity.Player
import com.alibaba.fastjson.JSON
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.Optional

/** 当前服务端材质优先：URL、Bukkit/Paper profile、Authlib record 和 SR 属性均可读取。 */
object SkinResolver {
    internal fun call(target: Any?, name: String, vararg args: Any): Any? {
        if (target == null) return null
        val method = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size && m.parameterTypes.indices.all { i -> m.parameterTypes[i].isAssignableFrom(args[i].javaClass) }
        } ?: return null
        return try { method.invoke(target,*args) } catch(_:IllegalAccessException) {
            runCatching { method.isAccessible=true;method.invoke(target,*args) }.getOrNull()
        } catch(_:Throwable){null}
    }
    fun resolve(player: Player): String? {
        val profile = call(player,"getPlayerProfile") ?: call(player,"getProfile")
        textureUrl(call(call(profile,"getTextures"),"getSkin"))?.let { return it }
        properties(profile)?.let { return it }
        // ChangeSkin/皮肤插件若更新 Authlib profile，此通道可直接读取，不依赖额外插件。
        properties(call(player,"getProfile"))?.let { return it }
        papi(player)?.let { return it }
        return null
    }
    /** SR 存储可能做 IO，只从皮肤工作线程调用。 */
    fun pluginTexture(uuid: java.util.UUID, name: String): String? = runCatching {
        val cls = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider")
        val api = cls.getMethod("get").invoke(null)
        val store = call(api,"getPlayerStorage")
        val property = call(store,"getSkinOfPlayer",uuid)
        textureUrl(property)?.let { return it }
        val id = call(store,"getSkinIdOfPlayer",uuid)
        val unwrapped = if (id is Optional<*>) id.orElse(null) else id
        val skins = call(api,"getSkinStorage")
        val data = unwrapped?.let { call(skins,"findSkinData",it) ?: call(skins,"getSkinDataByIdentifier",it) }
        textureUrl(data)
    }.getOrNull()

    private fun properties(profile: Any?): String? {
        val props = call(profile,"getProperties") ?: return null
        val entries = if (props is Collection<*>) props else call(props,"get","textures") as? Collection<*> ?: return null
        for (p in entries) {
            val name = call(p,"getName") ?: call(p,"name")
            if (name != null && name != "textures") continue
            textureUrl(call(p,"getValue") ?: call(p,"value"))?.let { return it }
        }
        return null
    }
    private fun papi(player: Player): String? = runCatching {
        val cls = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
        val method = cls.methods.firstOrNull { it.name == "setPlaceholders" && it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(player.javaClass) } ?: return null
        textureUrl(method.invoke(null,player,"%skinsrestorer_texture_url_or_empty%"))
    }.getOrNull()

    /** 可独立回归测试的材质转换，绝不把 CAPE url 当作皮肤。 */
    fun textureUrl(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 5) return null
        if (value is Optional<*>) return textureUrl(value.orElse(null),depth+1)
        if (value is URL || value is URI) return valid(value.toString())
        if (value is String) {
            valid(value)?.let { return it }
            val raw = if (value.startsWith("{")) value else runCatching { String(Base64.getDecoder().decode(value),Charsets.UTF_8) }.getOrNull() ?: return null
            return runCatching {
                val j = JSON.parseObject(raw)
                textureUrl(j?.getJSONObject("textures")?.getJSONObject("SKIN")?.getString("url")
                    ?: j?.getJSONObject("SKIN")?.getString("url"),depth+1)
            }.getOrNull()
        }
        for (n in listOf("getSkinTextureUrl","getTextureUrl","getURL","getUrl","getValue","value","getProperty")) {
            val v = call(value,n)
            if (v !== value) textureUrl(v,depth+1)?.let { return it }
        }
        return null
    }
    private fun valid(text: String): String? = runCatching {
        val uri = URI(text.trim())
        if (uri.scheme !in listOf("http","https") || uri.host.isNullOrBlank()) null
        else text.trim().replace("http://textures.minecraft.net/","https://textures.minecraft.net/")
    }.getOrNull()
}
