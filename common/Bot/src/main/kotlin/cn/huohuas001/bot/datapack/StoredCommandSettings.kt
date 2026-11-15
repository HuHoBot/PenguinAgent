package cn.huohuas001.bot.datapack

/**
 * 一条绑定记录。
 *
 * 绑定以 openid 为唯一标识（见 [cn.huohuas001.bot.state.BindingRepository]），
 * 因此这里保存的是「QQ openid → MC 玩家名列表 + QQ 昵称 + 显示名称偏好」。
 *
 * 一个 QQ 号可以绑定多个游戏账号，上限由 `binding.max-bindings` 决定；
 * [playerNames] 按绑定先后排列，第一个即主账号，单账号服务器与旧数据里
 * 它就等于原来的 playerName。
 */
data class BindingInfo(
    val playerNames: List<String> = emptyList(),
    val qqDisplayNameMode: String = "QQ",
    val mcDisplayNameMode: String = "QQ",
    val qqUsername: String = "",
    /** 已锁定的游戏账号（始终取自 [playerNames]，大小写与绑定时一致）。 */
    val lockedPlayerNames: List<String> = emptyList()
) {
    /** 主账号：多账号时用于默认展示，单账号时就是唯一角色。 */
    val playerName: String get() = playerNames.firstOrNull().orEmpty()

    /** 是否绑定了该游戏账号（大小写不敏感）。 */
    fun hasPlayer(target: String): Boolean =
        playerNames.any { it.equals(target, ignoreCase = true) }

    /** 该游戏账号是否已被锁定（大小写不敏感）。 */
    fun hasLockedPlayer(target: String): Boolean =
        lockedPlayerNames.any { it.equals(target, ignoreCase = true) }
}

internal data class StoredCommandSettings(
    val administrators: Map<String, Set<String>> = emptyMap(),
    val authenticatedUsers: Map<String, Set<String>> = emptyMap(),
    val administratorModes: Map<String, AdministratorAccessMode> = emptyMap(),
    val fullForwarding: Map<String, Boolean> = emptyMap(),
    /** openid → 绑定记录；同一 openid 在所有群共享同一条记录。 */
    val bindings: Map<String, BindingInfo> = emptyMap(),
    /** `groupOpenId:memberOpenId` → member_role，供 Agent 审批鉴权使用的被动角色缓存。 */
    val memberRoles: Map<String, String> = emptyMap()
)