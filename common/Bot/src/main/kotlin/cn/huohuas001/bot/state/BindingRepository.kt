package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.BindingInfo
import java.util.concurrent.ConcurrentHashMap

/** 绑定操作的结果。 */
enum class BindResult {
    /** 绑定成功 */
    SUCCESS,

    /** 该 QQ 号已经绑定过这个角色 */
    ALREADY_BOUND,

    /** 已达到 `binding.max-bindings` 上限 */
    LIMIT_REACHED,

    /** 该角色已被其他 QQ 号绑定 */
    PLAYER_TAKEN,

    /** 角色名为空或含有会破坏存档格式的字符 */
    INVALID_NAME
}

/** 锁定/解锁操作的结果。 */
enum class LockResult {
    /** 状态已改变 */
    UPDATED,

    /** 该角色不属于这个 QQ 号 */
    NOT_FOUND,

    /** 已经处于目标状态 */
    UNCHANGED
}

/**
 * 保存 QQ openid ↔ Minecraft 玩家名的绑定关系。
 *
 * 绑定只以 openid 为准，与 QQ 昵称、所在群都无关：同一个 openid 在任意一个群里
 * 绑定成功后，该服务器的所有群都视为已绑定（openid 相同即同一个人），
 * 玩家只需绑定一次。MC 玩家名同样全局唯一，避免多人顶替同一个角色。
 *
 * 一个 openid 可以绑定多个 MC 玩家名（上限由调用方按配置传入），
 * 每个角色还可以单独锁定，被锁定的角色无法进入服务器。
 */
class BindingRepository internal constructor(
    private val persist: () -> Unit
) {
    private val bindings = ConcurrentHashMap<String, BindingInfo>()

    fun getBinding(openId: String): BindingInfo? = bindings[openId]

    /** 该 openid 已绑定的角色列表（按绑定顺序，第一个为主账号）。 */
    fun playerNames(openId: String): List<String> = bindings[openId]?.playerNames.orEmpty()

    /** 该 openid 是否已绑定指定角色。 */
    fun hasPlayer(openId: String, playerName: String): Boolean =
        bindings[openId]?.hasPlayer(playerName) == true

    /**
     * 新增一条绑定。
     *
     * 校验与写入放在同一个同步块里：两个群同时收到绑定请求时不会双双通过
     * 「还没到上限」「角色没被占用」的检查。
     *
     * @param maxBindings 该 QQ 号最多允许绑定的角色数，小于 1 时按 1 处理
     */
    @Synchronized
    fun addBinding(
        openId: String,
        playerName: String,
        qqUsername: String = "",
        maxBindings: Int = 1
    ): BindResult {
        val name = playerName.trim()
        if (openId.isBlank() || name.isEmpty()) return BindResult.INVALID_NAME
        // 逗号用于存档里的角色列表分隔，冒号用于字段分隔，控制字符会破坏 INI 行
        if (name.any { it == ',' || it == ':' || it < ' ' || it == '\u007F' }) return BindResult.INVALID_NAME

        val old = bindings[openId]
        if (old != null && old.hasPlayer(name)) return BindResult.ALREADY_BOUND
        if (findByPlayerName(name) != null) return BindResult.PLAYER_TAKEN

        val players = old?.playerNames.orEmpty()
        if (players.size >= maxBindings.coerceAtLeast(1)) return BindResult.LIMIT_REACHED

        bindings[openId] = BindingInfo(
            playerNames = players + name,
            // 换绑角色时保留用户已切换过的显示名称设置
            qqDisplayNameMode = old?.qqDisplayNameMode ?: "QQ",
            mcDisplayNameMode = old?.mcDisplayNameMode ?: "QQ",
            qqUsername = qqUsername.ifBlank { old?.qqUsername.orEmpty() },
            lockedPlayerNames = old?.lockedPlayerNames.orEmpty()
        )
        persist()
        return BindResult.SUCCESS
    }

    /**
     * 移除该 openid 下的指定角色。
     *
     * 移除最后一个角色时整条记录一起删除，避免留下空记录。
     *
     * @return 该角色原本是否存在
     */
    @Synchronized
    fun removePlayer(openId: String, playerName: String): Boolean {
        val info = bindings[openId] ?: return false
        val remaining = info.playerNames.filterNot { it.equals(playerName, ignoreCase = true) }
        if (remaining.size == info.playerNames.size) return false

        if (remaining.isEmpty()) {
            bindings.remove(openId)
        } else {
            bindings[openId] = info.copy(
                playerNames = remaining,
                lockedPlayerNames = info.lockedPlayerNames
                    .filterNot { it.equals(playerName, ignoreCase = true) }
            )
        }
        persist()
        return true
    }

    /** 移除该 openid 的全部绑定（管理员强制解绑用）。 */
    @Synchronized
    fun removeBinding(openId: String): Boolean {
        val changed = bindings.remove(openId) != null
        if (changed) persist()
        return changed
    }

    /** 按 MC 玩家名查找绑定（大小写不敏感）。 */
    fun findByPlayerName(playerName: String): Map.Entry<String, BindingInfo>? =
        bindings.entries.find { it.value.hasPlayer(playerName) }

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

    /**
     * 锁定或解锁某个角色。
     *
     * 只能操作该 openid 自己名下的角色，调用方仍需先做归属校验。
     */
    @Synchronized
    fun setLocked(openId: String, playerName: String, locked: Boolean): LockResult {
        val info = bindings[openId] ?: return LockResult.NOT_FOUND
        val actual = info.playerNames.firstOrNull { it.equals(playerName, ignoreCase = true) }
            ?: return LockResult.NOT_FOUND
        if (info.hasLockedPlayer(actual) == locked) return LockResult.UNCHANGED

        val lockedNames = if (locked) {
            info.lockedPlayerNames + actual
        } else {
            info.lockedPlayerNames.filterNot { it.equals(actual, ignoreCase = true) }
        }
        bindings[openId] = info.copy(lockedPlayerNames = lockedNames)
        persist()
        return LockResult.UPDATED
    }

    /** 该 MC 玩家名是否被任意 QQ 用户锁定。 */
    fun isLocked(playerName: String): Boolean =
        bindings.values.any { it.hasLockedPlayer(playerName) }

    /** 当前所有被锁定的 MC 玩家名。 */
    fun lockedPlayerNames(): Set<String> =
        bindings.values.flatMap { it.lockedPlayerNames }.toSet()

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
