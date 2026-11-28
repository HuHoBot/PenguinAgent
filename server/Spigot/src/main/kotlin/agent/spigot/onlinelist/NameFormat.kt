package agent.spigot.onlinelist

import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.awt.Color

/** 展示名顺序：TAB 完整格式 -> 服务端 tab/display 组件 -> LP 前缀+原名+后缀。不另配颜色。 */
object NameFormat {
    fun resolve(player: Player, prefix: String, suffix: String): String {
        tab(player)?.takeIf { meaningfulDisplayOverride(it,player.name) }?.let { return it }
        for (getter in listOf("playerListName","getPlayerListName","displayName","getDisplayName")) {
            val raw = SkinResolver.call(player,getter) ?: continue
            val formatted = if (raw is Component) MiniMessage.miniMessage().serialize(raw) else raw as? String ?: serverComponent(raw) ?: continue
            if (meaningfulDisplayOverride(formatted,player.name)) return formatted
        }
        // 不在 prefix 与 name 中间插入 reset：&7 会自然延续到玩家名。
        return prefix + player.name + suffix
    }
    /** 宿主 Adventure 与插件内重定位 Adventure 不同类加载器时，经 JSON 无损转换。 */
    internal fun serverComponent(raw: Any): String? = runCatching {
        if(raw is Component)return MiniMessage.miniMessage().serialize(raw)
        val hostLoader=raw.javaClass.classLoader
        // Split the host namespace so Shadow cannot rewrite reflection's host class names.
        val namespace=listOf("net","kyori","adventure","text").joinToString(".")+"."
        val hostComponent=Class.forName(namespace+"Component",true,hostLoader)
        if(!hostComponent.isInstance(raw))return null
        val hostGson=Class.forName(namespace+"serializer.gson.GsonComponentSerializer",true,hostLoader)
        val sourceSerializer=hostGson.getMethod("gson").invoke(null)
        val json=hostGson.getMethod("serialize",hostComponent).invoke(sourceSerializer,raw) as? String ?: return null
        val component=net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(json)
        MiniMessage.miniMessage().serialize(component)
    }.getOrNull()
    internal fun meaningfulDisplayOverride(formatted:String,name:String):Boolean {
        if(formatted.isBlank())return false
        val parts=McTextParser.parse(formatted)
        val plain=parts.joinToString(""){it.text}.trim()
        if(plain.isBlank())return false
        if(plain!=name)return true
        // Bukkit's default tab-name component is commonly just an unstyled name.
        // Do not let it hide a LuckPerms prefix. Honour deliberate name-only colours/styles.
        val base=Color(35,47,65)
        return parts.any { it.color!=base || it.bold || it.italic || it.underline || it.strike }
    }

    private fun tab(player: Player): String? = runCatching {
        val api = Class.forName("me.neznamy.tab.api.TabAPI").getMethod("getInstance").invoke(null)
        val p = SkinResolver.call(api,"getPlayer",player.uniqueId) ?: return null
        val manager = SkinResolver.call(api,"getTabListFormatManager") ?: return null
        val prefix = SkinResolver.call(manager,"getOriginalReplacedPrefix",p) as? String
            ?: SkinResolver.call(manager,"getOriginalPrefix",p) as? String ?: ""
        val suffix = SkinResolver.call(manager,"getOriginalReplacedSuffix",p) as? String
            ?: SkinResolver.call(manager,"getOriginalSuffix",p) as? String ?: ""
        val name = SkinResolver.call(manager,"getOriginalReplacedName",p) as? String
            ?: SkinResolver.call(manager,"getOriginalName",p) as? String ?: player.name
        (prefix + name + suffix).takeIf { McTextParser.plainText(it).isNotBlank() }
    }.getOrNull()
}
