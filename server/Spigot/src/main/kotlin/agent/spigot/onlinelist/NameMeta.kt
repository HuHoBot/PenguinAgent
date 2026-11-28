package agent.spigot.onlinelist

import org.bukkit.entity.Player

/** Separate metadata from the final display name: colours alone do not count as titles/suffixes. */
object NameMeta {
    data class Parts(val prefix:String,val suffix:String,val explicit:Boolean=false)

    fun read(player:Player,prefixTemplate:String="",suffixTemplate:String=""):Parts {
        val lp=LuckPermsBridge.prefixSuffix(player.uniqueId)
        val tab=tab(player)
        val team=runCatching { player.scoreboard.getEntryTeam(player.name) }.getOrNull()
        val boardPrefix=text(SkinResolver.call(team,"prefix")?:SkinResolver.call(team,"getPrefix"))
        val boardSuffix=text(SkinResolver.call(team,"suffix")?:SkinResolver.call(team,"getSuffix"))
        val vault=if(lp.first.isBlank()&&lp.second.isBlank()&&tab==null)vault(player)else null
        val display=NameFormat.resolve(player,"","")
        val plain=McTextParser.plainText(display)
        val at=plain.indexOf(player.name)
        val inferred=if(at>=0)plain.take(at) to plain.substring(at+player.name.length)else "" to ""
        val selectedPrefix=listOf(tab?.first,lp.first,boardPrefix,vault?.first,inferred.first).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val teamColour=SkinResolver.call(team,"getColor")?.toString().orEmpty()
        val prefix=if(selectedPrefix.isBlank()||selectedPrefix==boardPrefix)selectedPrefix+teamColour else selectedPrefix
        val suffix=listOf(tab?.second,lp.second,boardSuffix,vault?.second,inferred.second).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val explicitPrefix=prefixTemplate.takeIf { it.isNotBlank() }?.let { placeholders(player,it) }?.takeUnless { Regex("%[^%]+%").containsMatchIn(it) }
        val explicitSuffix=suffixTemplate.takeIf { it.isNotBlank() }?.let { placeholders(player,it) }?.takeUnless { Regex("%[^%]+%").containsMatchIn(it) }
        return Parts(explicitPrefix?:prefix,explicitSuffix?:suffix,explicitPrefix!=null||explicitSuffix!=null)
    }
    private fun text(value:Any?):String=when(value){null->"";is String->value;else->NameFormat.serverComponent(value).orEmpty()}
    private fun placeholders(player:Player,input:String):String {
        val ping=(SkinResolver.call(player,"getPing")as?Number)?.toInt()?:-1
        // Proxies and test doubles may not expose a world/server; placeholders degrade, never throw.
        val world=runCatching{player.world.name}.getOrDefault("")
        val server=runCatching{player.server}.getOrNull()
        val builtin=PlaceholderText.expand(input,player.name,
            runCatching{McTextParser.plainText(player.displayName)}.getOrDefault(player.name),
            world,ping,server?.name?:"",runCatching{server?.onlinePlayers?.size}.getOrNull()?:0,server?.maxPlayers?:0)
        // Unknown PlaceholderAPI expansions stay visible so the owner can see a typo instead of silence.
        return runCatching {
            val c=Class.forName("me.clip.placeholderapi.PlaceholderAPI")
            val method=c.methods.first { it.name=="setPlaceholders"&&it.parameterCount==2&&it.parameterTypes[0].isAssignableFrom(player.javaClass) }
            method.invoke(null,player,builtin)as?String?:builtin
        }.getOrDefault(builtin)
    }
    private fun tab(player:Player):Pair<String,String>?=runCatching {
        val api=Class.forName("me.neznamy.tab.api.TabAPI").getMethod("getInstance").invoke(null)
        val p=SkinResolver.call(api,"getPlayer",player.uniqueId)?:return null
        val m=SkinResolver.call(api,"getTabListFormatManager")?:return null
        val pre=SkinResolver.call(m,"getOriginalReplacedPrefix",p)?:SkinResolver.call(m,"getOriginalPrefix",p)
        val suf=SkinResolver.call(m,"getOriginalReplacedSuffix",p)?:SkinResolver.call(m,"getOriginalSuffix",p)
        pre?.toString().orEmpty() to suf?.toString().orEmpty()
    }.getOrNull()
    private fun vault(player:Player):Pair<String,String>?=runCatching {
        val type=Class.forName("net.milkbowl.vault.chat.Chat")
        @Suppress("UNCHECKED_CAST")
        val provider=player.server.servicesManager.load(type as Class<Any>)?:return null
        fun get(name:String):String=SkinResolver.call(provider,name,player)?.toString()
            ?:SkinResolver.call(provider,name,player.world.name,player)?.toString().orEmpty()
        get("getPlayerPrefix") to get("getPlayerSuffix")
    }.getOrNull()
}
