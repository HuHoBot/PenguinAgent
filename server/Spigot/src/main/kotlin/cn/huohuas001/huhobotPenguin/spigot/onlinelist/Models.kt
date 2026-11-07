package cn.huohuas001.huhobotPenguin.spigot.onlinelist

/** 在线列表渲染用玩家快照（主线程快照，不可变）。 */
class OnlineListPlayer(
    val name: String,
    val uuid: String,
    val skinUrl: String?,
    val administrator: Boolean
)

/** 在线列表渲染用服务器快照。 */
class ServerSnapshot(
    val serverName: String,
    val maxPlayers: Int,
    val totalOnlinePlayers: Int,
    val players: List<OnlineListPlayer>,
    val capturedAt: Long = System.currentTimeMillis(),
    val currentPage: Int = 1,
    val totalPages: Int = 1
)
