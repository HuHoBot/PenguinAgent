package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import java.awt.image.BufferedImage

/** 在线列表渲染用玩家快照（主线程快照，不可变）。 */
class OnlineListPlayer(
    val name: String,
    val uuid: String,
    val skinUrl: String?,
    val administrator: Boolean,
    /** LuckPerms 前缀（可能含颜色代码）；空 = 无。 */
    val prefix: String = "",
    /** LuckPerms 后缀（可能含颜色代码）；空 = 无。 */
    val suffix: String = "",
    /** 头像直链（如 mc-heads 头像 API）；下载后直接使用，不做皮肤裁剪。 */
    val headUrl: String = ""
)

/** 在线列表渲染用服务器快照。 */
class ServerSnapshot(
    val serverName: String,
    val maxPlayers: Int,
    val totalOnlinePlayers: Int,
    val players: List<OnlineListPlayer>,
    val capturedAt: Long = System.currentTimeMillis(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    /** 服务器 MOTD（保留 § 颜色代码，渲染时解析成彩色）。 */
    val motd: String = "",
    /** 服务器图标（server-icon.png，未配置时为 null）。 */
    val icon: BufferedImage? = null,
    /** 对外连接地址；为空表示用户尚未在 config.yml 的 motd.server-ip 配置。 */
    val serverIp: String = "",
    val serverPort: Int = 25565,
    /** 服务端品牌（Paper/Leaf/Purpur/Folia 等）。 */
    val brand: String = "",
    /** Bukkit/Minecraft 核心版本。 */
    val coreVersion: String = "",
    /** 协议版本描述（如 1.21.11 / 774）。 */
    val protocolVersion: String = "",
    /** ViaVersion 等插件推算出的支持版本范围；为空表示未检测到 Via。 */
    val viaRange: String = "",
    /** 服务器 TPS（Paper/Leaf/Folia 提供；老服务端为 null）。 */
    val tps: Double? = null,
    /** 平均 tick 耗时（毫秒），同样仅新服务端可用。 */
    val mspt: Double? = null,
    /** JVM 已用堆内存（MB）。 */
    val memoryUsedMb: Long = 0,
    /** JVM 最大堆内存（MB）。 */
    val memoryMaxMb: Long = 0,
    /** 地址是否为自动探测获得（渲染时可标注）。 */
    val ipAutoDetected: Boolean = false
)
