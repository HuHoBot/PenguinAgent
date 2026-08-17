package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.BindingInfo
import java.util.concurrent.ConcurrentHashMap

/** 保存各群的 QQ openid ↔ Minecraft 玩家名绑定关系。 */
class BindingRepository internal constructor(
    private val persist: () -> Unit
) {
    private val bindingsByGroup = ConcurrentHashMap<String, ConcurrentHashMap<String, BindingInfo>>()

    fun getBinding(groupId: String, openId: String): BindingInfo? =
        bindingsByGroup[groupId]?.get(openId)

    fun setBinding(groupId: String, openId: String, playerName: String): Boolean {
        val group = bindingsByGroup.computeIfAbsent(groupId) { ConcurrentHashMap() }
        val old = group.put(openId, BindingInfo(playerName))
        persist()
        return old?.playerName != playerName
    }

    fun removeBinding(groupId: String, openId: String): Boolean {
        val changed = bindingsByGroup[groupId]?.remove(openId) != null
        if (bindingsByGroup[groupId].isNullOrEmpty()) bindingsByGroup.remove(groupId)
        if (changed) persist()
        return changed
    }

    fun findByPlayerName(groupId: String, playerName: String): Map.Entry<String, BindingInfo>? =
        bindingsByGroup[groupId]?.entries?.find { it.value.playerName.equals(playerName, ignoreCase = true) }

    fun allInGroup(groupId: String): Map<String, BindingInfo> =
        bindingsByGroup[groupId]?.toMap() ?: emptyMap()

    fun updateSettings(groupId: String, openId: String, qqMode: String?, mcMode: String?): Boolean {
        val group = bindingsByGroup[groupId] ?: return false
        val info = group[openId] ?: return false
        val updated = info.copy(
            qqDisplayNameMode = qqMode ?: info.qqDisplayNameMode,
            mcDisplayNameMode = mcMode ?: info.mcDisplayNameMode
        )
        group[openId] = updated
        persist()
        return true
    }

    fun allBindings(): Map<String, Map<String, BindingInfo>> =
        bindingsByGroup.mapValues { (_, m) -> m.toMap() }

    fun replaceAll(values: Map<String, Map<String, BindingInfo>>) {
        bindingsByGroup.clear()
        values.forEach { (groupId, m) ->
            bindingsByGroup[groupId] = ConcurrentHashMap(m)
        }
    }

    internal fun snapshot(): Map<String, Map<String, BindingInfo>> = allBindings()
}
