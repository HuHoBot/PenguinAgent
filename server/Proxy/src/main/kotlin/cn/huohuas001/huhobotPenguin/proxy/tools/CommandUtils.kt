package cn.huohuas001.huhobotPenguin.proxy.tools

/**
 * Proxy 命令路由结果。
 *
 * [serverName] 为空时在代理本地执行；否则通过 Redis 发送到指定子服。
 */
data class ParsedCommand(
    val serverName: String?,
    val command: String
)

object CommandUtils {
    /** 以第一个冒号分割 `服务器名:命令`，命令中的后续冒号保持不变。 */
    fun splitCommand(rawCommand: String): ParsedCommand {
        val parts = rawCommand.split(":", limit = 2)
        if (parts.size != 2) return ParsedCommand(null, rawCommand)

        val serverName = parts[0].trim()
        return if (serverName.isEmpty()) {
            ParsedCommand(null, rawCommand)
        } else {
            ParsedCommand(serverName, parts[1].trimStart())
        }
    }
}
