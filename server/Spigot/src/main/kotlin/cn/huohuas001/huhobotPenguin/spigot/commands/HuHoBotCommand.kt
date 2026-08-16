package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.bot.web.WebUiPassword
import cn.huohuas001.bot.web.WebUiServer
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

class HuHoBotCommand(private val plugin: HuHoBotSpigot) : TabExecutor {
    override fun onCommand(
        sender: CommandSender,
        _command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                plugin.reloadPluginConfig()
                sender.sendMessage(ChatColor.GOLD.toString() + "已重载配置文件。")
            }

            "info" -> sender.sendMessage(
                "平台: ${plugin.getPlatform()}\n版本: ${plugin.getPluginVersion()}"
            )

            "password" -> handlePassword(sender, args)
            "webui" -> sender.sendMessage(
                "WebUI 地址: http://localhost:5678\n" +
                    "WebUI 密码: ${currentPasswordHint()}"
            )

            else -> sendHelp(sender, label)
        }
        return true
    }

    private fun handlePassword(sender: CommandSender, args: Array<out String>) {
        val newPassword = args.getOrNull(1)
        if (newPassword == null) {
            sender.sendMessage("用法: /hb password <新密码>")
            return
        }
        if (WebUiPassword.changePassword(newPassword)) {
            WebUiServer.invalidateAllTokens()
            sender.sendMessage(ChatColor.GREEN.toString() + "WebUI 密码已修改。")
        } else {
            sender.sendMessage(ChatColor.RED.toString() + "密码修改失败（密码需至少 6 位）。")
        }
    }

    /** 密码文件不存在时返回"启动时自动生成"，否则提示查看配置文件。 */
    private fun currentPasswordHint(): String {
        return if (WebUiPassword.isConfigured()) "已设置（可用 /hb password 修改）" else "首次启动时自动生成"
    }

    override fun onTabComplete(
        _sender: CommandSender,
        _command: Command,
        _alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase()
        return SUBCOMMANDS.filter { it.startsWith(prefix) }
    }

    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage("/$label reload - 重载配置文件")
        sender.sendMessage("/$label info - 查看适配器信息")
        sender.sendMessage("/$label password <新密码> - 修改 WebUI 登录密码")
        sender.sendMessage("/$label webui - 查看 WebUI 地址")
    }

    private companion object {
        val SUBCOMMANDS = listOf("reload", "info", "password", "webui", "help")
    }
}
