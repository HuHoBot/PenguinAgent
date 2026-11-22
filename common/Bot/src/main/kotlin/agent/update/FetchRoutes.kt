package agent.update

import java.net.URI

/** 固定 GitHub 加速路由：主站始终排首位，不把任意第三方 URL 转发给代理。 */
object FetchRoutes {
    fun candidates(address:String):List<String> {
        val uri=runCatching { URI(address) }.getOrNull()?:return listOf(address)
        if(uri.scheme!="https"||uri.userInfo!=null)return listOf(address)
        return when(uri.host){
            "raw.githubusercontent.com" -> {
                val parts=uri.path.trim('/').split('/')
                if(parts.size<4)return listOf(address)
                val cdn="https://cdn.jsdelivr.net/gh/${parts[0]}/${parts[1]}@${parts[2]}/${parts.drop(3).joinToString("/")}"
                listOf(address,cdn,"https://gh-proxy.org/$address","https://ghfast.top/$address")
            }
            "github.com","api.github.com" -> listOf(address,"https://gh-proxy.org/$address","https://ghfast.top/$address")
            else -> listOf(address)
        }.distinct()
    }

    fun releaseAsset(address:String):Boolean = runCatching {
        val u=URI(address)
        u.scheme=="https" && u.userInfo==null && u.host=="github.com" &&
            u.path.startsWith("/HuHoBot/PenguinAgent/releases/download/") && !u.path.contains("..")
    }.getOrDefault(false)
}
