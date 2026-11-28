package agent.state

import agent.provider.BotShared
import java.io.File

/**
 * 超级权限组（插件 data/permission-groups.yml）。
 *
 * 用法：在配置文件里新增一个权限组，把成员的 member_openid 填进 members，
 * 该组即可单独配置可用命令范围与是否视为管理员。命令名与 QQ 面板里的
 * 一致（不带斜杠）；`commands: []` 表示完全继承全局配置。
 *
 * 文件示例：
 * ```yaml
 * groups:
 *   - name: vip
 *     members: ["GXXXX", "GYYYY"]
 *     commands: ["motd", "在线"]
 *     admin: false
 *   - name: ops
 *     members: ["GZZZZ"]
 *     commands: []
 *     admin: true
 * ```
 */
object PermissionGroupsStore {

    data class Group(
        val name: String,
        val members: List<String>,
        val commands: List<String>,
        val admin: Boolean
    )

    @Volatile
    private var groups: List<Group> = emptyList()
    @Volatile
    private var file: File? = null

    fun configure(dataFolder: File?) {
        file = dataFolder?.resolve("permission-groups.yml")
        reload()
    }

    fun reload() {
        val f = file ?: return
        val loaded: List<Group> = runCatching { loadGroups(f) }
            .onFailure { error ->
                runCatching { BotShared.getPluginOrNull()?.log_error("权限组配置加载失败: ${error.message}") }
            }
            .getOrElse { return }
        groups = loaded
    }

    private fun loadGroups(f: File): List<Group> {
        if (!f.isFile) {
            // 首次生成示例文件
            SimpleYaml.write(
                f,
                linkedMapOf("groups" to mutableListOf<Any?>()),
                listOf(
                    "HuHoBot 权限组配置",
                    "groups 下新增权限组；members 填 member_openid（成员标识，不是群标识）；commands 空列表继承全局。",
                    "可复制的双组示例见 data/guide/permission-groups.example.yml；示例不会自动启用。",
                    "admin: true 会授予插件管理员能力，请勿用于普通成员；QQ 平台群管权限另行判定。"
                )
            )
            return emptyList()
        }
        val root = SimpleYaml.read(f)
        val raw = root["groups"] as? List<*> ?: error("groups 必须是列表；不用时写 groups: []")
        val out = ArrayList<Group>()
        for (item in raw) {
            val m = item as? Map<*, *> ?: continue
            val name = (m["name"] ?: "").toString().trim()
            if (name.isEmpty()) continue
            val members: List<String> = stringList(m["members"])
            val commands = stringList(m["commands"])
            val adminRaw = (m["admin"] ?: "").toString()
            val admin = m["admin"] == true || adminRaw.equals("true", ignoreCase = true)
            out.add(Group(name, members, commands, admin))
        }
        return out
    }

    private fun stringList(value: Any?): List<String> {
        val list = value as? List<*> ?: return emptyList()
        val out = ArrayList<String>()
        for (item in list) {
            val s = item?.toString()?.trim() ?: ""
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    fun all(): List<Group> = groups

    /** 返回该 openid 所属的第一个权限组（一个成员可被多个组包含，按文件顺序取第一个）。 */
    fun groupOf(openId: String): Group? =
        groups.firstOrNull { openId.isNotBlank() && openId in it.members }

    /** openid 是否在任何权限组中。 */
    fun contains(openId: String): Boolean = groupOf(openId) != null

    /**
     * 命令可见性判定：返回
     * - null → 该成员不在任何权限组，走全局配置；
     * - true → 权限组明确允许；
     * - false → 权限组未包含该命令。
     */
    fun commandAllowed(openId: String, command: String): Boolean? {
        val group = groupOf(openId) ?: return null
        if (group.admin || group.commands.isEmpty()) return null // 空列表=继承全局
        return group.commands.any { it.equals(command, ignoreCase = true) }
    }

    /** 权限组成员是否按组配置视为管理员。 */
    fun isAdminByGroup(openId: String): Boolean = groupOf(openId)?.admin == true

    /** 供 WebUI/命令列出；返回只读快照。 */
    fun snapshot(): List<Group> = groups.toList()
}
