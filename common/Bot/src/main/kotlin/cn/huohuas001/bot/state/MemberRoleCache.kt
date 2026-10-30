package cn.huohuas001.bot.state

import java.util.concurrent.ConcurrentHashMap

/**
 * 各群成员的被动角色缓存：`groupOpenId:memberOpenId -> owner/admin/member`。
 *
 * 群消息事件（GROUP_AT_MESSAGE_CREATE）的 author 自带 member_role，这是 QQ 已开放的能力；
 * 按钮互动事件（INTERACTION_CREATE）不带角色，「获取群成员信息」接口又是白名单能力（11253）。
 * 因此把消息里被动拿到的角色缓存下来，供审批鉴权查询。
 *
 * 角色发生变化时立即落盘（实时刷新），重启后可直接恢复。
 */
class MemberRoleCache internal constructor(
    private val persist: () -> Unit
) {
    private val roles = ConcurrentHashMap<String, String>()

    private fun key(groupOpenId: String, memberOpenId: String) = "$groupOpenId:$memberOpenId"

    fun role(groupOpenId: String, memberOpenId: String): String? =
        roles[key(groupOpenId, memberOpenId)]

    fun isGroupAdmin(groupOpenId: String, memberOpenId: String): Boolean =
        role(groupOpenId, memberOpenId) in setOf("owner", "admin")

    /** 记录角色；值有变化时立即落盘。 */
    fun put(groupOpenId: String, memberOpenId: String, role: String): Boolean {
        if (groupOpenId.isBlank() || memberOpenId.isBlank() || role.isBlank()) return false
        val normalized = role.lowercase()
        val previous = roles.put(key(groupOpenId, memberOpenId), normalized)
        val changed = previous != normalized
        if (changed) persist()
        return changed
    }

    internal fun replaceAll(values: Map<String, String>) {
        roles.clear()
        roles.putAll(values)
    }

    internal fun snapshot(): Map<String, String> = HashMap(roles)
}
