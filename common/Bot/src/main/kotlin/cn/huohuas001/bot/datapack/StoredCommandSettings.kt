package cn.huohuas001.bot.datapack

internal data class StoredCommandSettings(
    val administrators: Map<String, Set<String>> = emptyMap(),
    val authenticatedUsers: Map<String, Set<String>> = emptyMap(),
    val administratorModes: Map<String, AdministratorAccessMode> = emptyMap(),
    val fullForwarding: Map<String, Boolean> = emptyMap()
)