package cn.huohuas001.bot.datapack

/**
 * 一条绑定记录。
 *
 * 绑定以 openid 为唯一标识（见 [cn.huohuas001.bot.state.BindingRepository]），
 * 因此这里保存的是「QQ openid → MC 玩家名 + QQ 昵称 + 显示名称偏好」。
 */
data class BindingInfo(
    val playerName: String,
    val qqDisplayNameMode: String = "QQ",
    val mcDisplayNameMode: String = "QQ",
    val qqUsername: String = ""
)

internal data class StoredCommandSettings(
    val administrators: Map<String, Set<String>> = emptyMap(),
    val authenticatedUsers: Map<String, Set<String>> = emptyMap(),
    val administratorModes: Map<String, AdministratorAccessMode> = emptyMap(),
    val fullForwarding: Map<String, Boolean> = emptyMap(),
    /** openid → 绑定记录；同一 openid 在所有群共享同一条记录。 */
    val bindings: Map<String, BindingInfo> = emptyMap()
)