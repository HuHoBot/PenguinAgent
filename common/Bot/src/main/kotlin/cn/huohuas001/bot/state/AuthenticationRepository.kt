package cn.huohuas001.bot.state

import java.util.concurrent.ConcurrentHashMap

/** 保存各群已通过认证的 QQ OpenID。 */
class AuthenticationRepository internal constructor(
    private val persist: () -> Unit
) {
    private val usersByGroup = ConcurrentHashMap<String, MutableSet<String>>()

    fun contains(groupId: String, userId: String): Boolean =
        usersByGroup[groupId]?.contains(userId) == true

    fun authenticate(groupId: String, userId: String): Boolean {
        val changed = usersByGroup
            .computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }
            .add(userId)
        if (changed) persist()
        return changed
    }

    fun revoke(groupId: String, userId: String): Boolean {
        val changed = usersByGroup[groupId]?.remove(userId) == true
        if (usersByGroup[groupId].isNullOrEmpty()) usersByGroup.remove(groupId)
        if (changed) persist()
        return changed
    }

    internal fun replaceAll(values: Map<String, Set<String>>) {
        usersByGroup.clear()
        values.forEach { (groupId, users) ->
            usersByGroup[groupId] = ConcurrentHashMap.newKeySet<String>().apply { addAll(users) }
        }
    }

    internal fun snapshot(): Map<String, Set<String>> =
        usersByGroup.mapValues { (_, users) -> users.toSet() }
}
