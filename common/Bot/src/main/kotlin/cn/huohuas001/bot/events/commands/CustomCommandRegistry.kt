package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.datapack.ResolvedCommand
import cn.huohuas001.bot.provider.CustomCommandDetail
import java.util.concurrent.ConcurrentHashMap



/** 平台无关的自定义命令加载与解析器。 */
object CustomCommandRegistry {
    private val commands = ConcurrentHashMap<String, CustomCommandDetail>()

    fun replace(values: Collection<CustomCommandDetail>) {
        commands.clear()
        values.filter { it.key.isNotBlank() && it.command.isNotBlank() }
            .forEach { commands[it.key] = it }
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
        val detail = commands[key] ?: return ResolvedCommand(error = "未找到自定义命令：$key")

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
