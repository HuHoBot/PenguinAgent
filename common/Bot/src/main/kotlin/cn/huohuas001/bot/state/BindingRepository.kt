package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.BindingInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * 保存 QQ openid ↔ Minecraft 玩家名的绑定关系。
 *
 * 绑定只以 openid 为准，与 QQ 昵称、所在群都无关：同一个 openid 在任意一个群里
 * 绑定成功后，该服务器的所有群都视为已绑定（openid 相同即同一个人），
 * 玩家只需绑定一次。MC 玩家名同样全局唯一，避免多人顶替同一个角色。
 */
class BindingRepository internal constructor(
    private val persist: () -> Unit
) {
    private val bindings = ConcurrentHashMap<String, BindingInfo>()

    fun getBinding(openId: String): BindingInfo? = bindings[openId]

    /**
     * 写入或更新绑定。
     *
     * @return 绑定的 MC 玩家名是否发生变化
     */
    fun setBinding(openId: String, playerName: String, qqUsername: String = ""): Boolean {
        if (openId.isBlank() || playerName.isBlank()) return false
        val old = bindings[openId]
        bindings[openId] = BindingInfo(
            playerName = playerName,
            // 换绑角色时保留用户已切换过的显示名称设置
            qqDisplayNameMode = old?.qqDisplayNameMode ?: "QQ",
            mcDisplayNameMode = old?.mcDisplayNameMode ?: "QQ",
            qqUsername = qqUsername.ifBlank { old?.qqUsername.orEmpty() }
        )
        persist()
        return old?.playerName != playerName
    }

    fun removeBinding(openId: String): Boolean {
        val changed = bindings.remove(openId) != null
        if (changed) persist()
        return changed
    }

    /** 按 MC 玩家名查找绑定（大小写不敏感）。 */
    fun findByPlayerName(playerName: String): Map.Entry<String, BindingInfo>? =
        bindings.entries.find { it.value.playerName.equals(playerName, ignoreCase = true) }

    /** 按 QQ 昵称反查 openid（昵称可能被玩家改过，仅供管理命令兜底使用）。 */
    fun findByQqUsername(qqUsername: String): Map.Entry<String, BindingInfo>? =
        bindings.entries.find { it.value.qqUsername.equals(qqUsername, ignoreCase = true) }

    /**
     * 按任意标识查找绑定：先按 MC 玩家名，再按 openid，最后按 QQ 昵称。
     *
     * 供管理员的强制解绑命令使用，避免用户不知道对方的 openid 时无从下手。
     */
    fun find(target: String): Map.Entry<String, BindingInfo>? {
        val key = target.trim()
        if (key.isEmpty()) return null
        return findByPlayerName(key)
            ?: bindings.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?: findByQqUsername(key)
    }

    fun updateSettings(openId: String, qqMode: String?, mcMode: String?): Boolean {
        val info = bindings[openId] ?: return false
        bindings[openId] = info.copy(
            qqDisplayNameMode = qqMode ?: info.qqDisplayNameMode,
            mcDisplayNameMode = mcMode ?: info.mcDisplayNameMode
        )
        persist()
        return true
    }

    fun allBindings(): Map<String, BindingInfo> = bindings.toMap()

    fun replaceAll(values: Map<String, BindingInfo>) {
        bindings.clear()
        bindings.putAll(values)
    }

    internal fun snapshot(): Map<String, BindingInfo> = allBindings()
}