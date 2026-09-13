package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

class SendCommand(private val plugin: HuHoBotSpigot) : TabExecutor {
    override fun onCommand(
        sender: CommandSender,
        _command: Command,
        _label: String,
        args: Array<out String>
    ): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("用法: /send <消息内容>")
            return true
        }
        val message = args.joinToString(" ")
        plugin.sendText(message)
        sender.sendMessage(ChatColor.GREEN.toString() + "已发送到 QQ 群。")
        return true
    }

    override fun onTabComplete(
        _sender: CommandSender,
        _command: Command,
        _alias: String,
        _args: Array<out String>
    ): List<String> = emptyList()
}
