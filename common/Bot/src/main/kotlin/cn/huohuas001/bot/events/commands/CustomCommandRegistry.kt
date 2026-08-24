package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.datapack.ResolvedCommand
import cn.huohuas001.bot.provider.CustomCommandDetail
import java.util.concurrent.ConcurrentHashMap

/** 平台无关的自定义命令加载与解析器。 */
object CustomCommandRegistry {
    // 配置命令和运行时命令分开保存，避免 reload 时清掉第三方插件注册的命令。
    private val configuredCommands = ConcurrentHashMap<String, CustomCommandDetail>()
    private val runtimeCommands = ConcurrentHashMap<String, CustomCommandDetail>()

    fun replace(values: Collection<CustomCommandDetail>) {
        configuredCommands.clear()
        values.filter { it.key.isNotBlank() && it.command.isNotBlank() }
            .forEach {
                val key = it.key.trim()
                configuredCommands[key] = CustomCommandDetail(key, it.command, it.permission, it.pushMenu)
            }
    }

    /** 注册一个运行时自定义命令，返回 false 表示 key 或 command 为空。 */
    fun register(value: CustomCommandDetail): Boolean {
        val key = value.key.trim()
        val command = value.command.trim()
        if (key.isEmpty() || command.isEmpty()) return false
        runtimeCommands[key] = CustomCommandDetail(key, command, value.permission, value.pushMenu)
        return true
    }

    /** 注销一个运行时自定义命令。配置文件中的命令不会被此方法删除。 */
    fun unregister(key: String): Boolean = runtimeCommands.remove(key.trim()) != null

    fun clearRuntime() {
        runtimeCommands.clear()
    }

    /** 返回当前已加载的自定义命令快照，供指令面板同步等只读场景使用。 */
    fun snapshot(): List<CustomCommandDetail> = (configuredCommands.values + runtimeCommands.values)
        .associateBy { it.key.trim() }
        .values
        .sortedBy { it.key }

    /** 按配置中的 key 查找自定义命令。 */
    fun find(key: String): CustomCommandDetail? {
        val normalizedKey = key.trim()
        return runtimeCommands[normalizedKey]
            ?: configuredCommands[normalizedKey]
            ?: runtimeCommands.values.firstOrNull { it.key.trim() == normalizedKey }
            ?: configuredCommands.values.firstOrNull { it.key.trim() == normalizedKey }
    }

    fun resolve(raw: String): ResolvedCommand {
        val parts = raw.trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 2 || parts[0] != "huhobot" || parts[1] !in setOf("run", "adminrun")) {
            return ResolvedCommand(command = raw)
        }
        if (parts.size < 5) return ResolvedCommand(error = "自定义命令参数不正确")

        val isAdmin = parts[1] == "adminrun"
        val groupId = parts[2]
        val userId = parts[3]
        val invocation = parts[4] + if (parts.size == 6) " ${parts[5]}" else ""
        val invocationParts = invocation.split(Regex("\\s+"), limit = 2)
        val key = invocationParts[0]
        val params = invocationParts.getOrElse(1) { "" }
        val detail = find(key) ?: return ResolvedCommand(error = "未找到自定义命令：$key")

        if (detail.permission > 0 && !isAdmin) {
            return ResolvedCommand(error = "此自定义命令仅管理员可执行")
        }

        var command = detail.command
            .replace("{params}", params)
            .replace("{group}", groupId)
            .replace("{user}", userId)
        val arguments = params.split(Regex("\\s+")).filter(String::isNotEmpty)
        arguments.forEachIndexed { index, value ->
            command = command
                .replace("{$index}", value)
                .replace("&${index + 1}", value)
        }
        return ResolvedCommand(command = command)
    }
}
