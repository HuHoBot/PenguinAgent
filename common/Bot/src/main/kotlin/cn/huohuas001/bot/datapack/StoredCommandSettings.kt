package cn.huohuas001.bot.datapack

data class BindingInfo(
    val playerName: String,
    val qqDisplayNameMode: String = "QQ",
    val mcDisplayNameMode: String = "MC"
)

internal data class StoredCommandSettings(
    val administrators: Map<String, Set<String>> = emptyMap(),
    val authenticatedUsers: Map<String, Set<String>> = emptyMap(),
    val administratorModes: Map<String, AdministratorAccessMode> = emptyMap(),
    val fullForwarding: Map<String, Boolean> = emptyMap(),
    val bindings: Map<String, Map<String, BindingInfo>> = emptyMap()
)