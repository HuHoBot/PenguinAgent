package agent.state

import agent.provider.BotShared

/**
 * 命令映射与切换。
 *
 * - 映射（map）：原名 → 新名，**原名与新名都能用**；
 * - 切换（rename）：原名 → 新名，**只有新名能用，原名彻底停用**。
 *
 * 两者都支持多条；配置文件中命令名一律不带斜杠。
 * 面板显示映射/切换后的名字；被切换掉的原名不再出现在面板。
 */
object CommandMappingStore {

    @Volatile
    private var aliasMap: Map<String, String> = emptyMap()

    @Volatile
    private var renameMap: Map<String, String> = emptyMap()

    /** 从配置刷新缓存（插件启用/重载时调用一次）。 */
    fun refresh() {
        val plugin = BotShared.getPluginOrNull() ?: return
        aliasMap = plugin.getCommandAliasMap()
            .mapKeys { it.key.trim() }
            .mapValues { it.value.trim() }
            .filter { it.key.isNotEmpty() && it.value.isNotEmpty() && it.key != it.value }
        renameMap = plugin.getCommandAliasRename()
            .mapKeys { it.key.trim() }
            .mapValues { it.value.trim() }
            .filter { it.key.isNotEmpty() && it.value.isNotEmpty() && it.key != it.value }
    }

    /**
     * 输入的命令名 → 路由用原名。
     * 返回 null 表示该输入是被切换掉的原名（应提示"已切换为 X"）；
     * 其余情况返回原名（未知命令原样返回，交给原链路处理）。
     */
    fun resolve(input: String): String? {
        val input0 = input.trim()
        if (input0.isEmpty()) return input0
        // 输入恰好是某个 rename 的新名 → 路由到原名
        renameMap.entries.firstOrNull { it.value == input0 }?.let { return it.key }
        // 输入是被切换掉的原名 → 拒绝
        if (renameMap.containsKey(input0)) return null
        // 输入是映射新名 → 路由到原名
        aliasMap.entries.firstOrNull { it.value == input0 }?.let { return it.key }
        return input0
    }

    /** 原名是否已被切换掉（不再可用）。 */
    fun isDisabled(originalName: String): Boolean = renameMap.containsKey(originalName)

    /** 被切换掉的原名对应的新名（用于提示）。 */
    fun aliasOf(disabledName: String): String? = renameMap[disabledName]

    /** 面板显示名：映射/切换后的名字（原名本身保留时显示映射新名）。 */
    fun displayName(originalName: String): String =
        aliasMap[originalName] ?: renameMap[originalName] ?: originalName

    /** 原名是否应从面板隐藏（被切换后只显示新名）。 */
    fun isHidden(originalName: String): Boolean =
        renameMap.containsKey(originalName) && renameMap[originalName] != originalName
}
