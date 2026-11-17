package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import java.util.UUID

/**
 * LuckPerms 反射桥（可选依赖）。
 *
 * 未安装 LuckPerms 时所有方法安全返回空值；安装后提供
 * 前缀/后缀（含颜色代码）与权限组判断。全部反射调用，避免硬依赖。
 */
object LuckPermsBridge {

    private val providerClass: Class<*>? =
        runCatching { Class.forName("net.luckperms.api.LuckPermsProvider") }.getOrNull()

    fun available(): Boolean = providerClass != null

    private fun provider(): Any? =
        runCatching { providerClass?.getMethod("get")?.invoke(null) }.getOrNull()

    private fun user(uuid: UUID): Any? = runCatching {
        val plugin = provider() ?: return null
        val manager = plugin.javaClass.getMethod("getUserManager").invoke(plugin)
        manager.javaClass.getMethod("getUser", UUID::class.java).invoke(manager, uuid)
    }.getOrNull()

    /** 返回 (前缀, 后缀)，可能含 §/& 颜色代码。 */
    fun prefixSuffix(uuid: UUID): Pair<String, String> = runCatching {
        val target = user(uuid) ?: return "" to ""
        val cached = target.javaClass.getMethod("getCachedData").invoke(target)
        val meta = cached.javaClass.getMethod("getMetaData").invoke(cached)
        val prefix = meta.javaClass.getMethod("getPrefix").invoke(meta) as? String ?: ""
        val suffix = meta.javaClass.getMethod("getSuffix").invoke(meta) as? String ?: ""
        prefix to suffix
    }.getOrDefault("" to "")

    /** 玩家是否属于配置中的任一组（主组 + 继承组）。 */
    fun inGroups(uuid: UUID, interesting: List<String>): Boolean {
        if (interesting.isEmpty()) return false
        val wanted = interesting.map { it.trim().lowercase() }.toSet()
        return runCatching {
            val target = user(uuid) ?: return false
            // 主组
            val cached = target.javaClass.getMethod("getCachedData").invoke(target)
            val meta = cached.javaClass.getMethod("getMetaData").invoke(cached)
            val primary = meta.javaClass.getMethod("getPrimaryGroup").invoke(meta) as? String
            if (primary != null && primary.lowercase() in wanted) return true
            // 继承组：遍历节点，取 Group 类型节点的组名
            val nodes = target.javaClass.getMethod("getNodes").invoke(target) as? Collection<*> ?: return false
            nodes.any { rawNode ->
                val node = rawNode ?: return@any false
                runCatching {
                    node.javaClass.getMethod("isGroup").invoke(node) as? Boolean == true &&
                        wanted.any { group ->
                            (node.javaClass.getMethod("getGroupName").invoke(node) as? String)?.equals(group, true) == true
                        }
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }
}
