package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.bot.NicknameManager
import cn.huohuas001.bot.QClient
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

class AtCommand : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}此命令仅限游戏内使用")
            return true
        }
        if (args.size < 2) {
            sender.sendMessage("${ChatColor.RED}用法: /at <群成员昵称> <消息内容>")
            return true
        }
        val targetName = args[0]
        val message = args.drop(1).joinToString(" ")
        // 解析 @：找到目标昵称对应的 openid，构造 @格式 消息
        val openId = NicknameManager.getOpenId(targetName)
        val atText = if (openId != null) "<@$openId>" else "@$targetName"
        val fullMessage = "$atText $message"
        QClient.sendAtToGroups(sender.name, fullMessage)
        sender.sendMessage("${ChatColor.GREEN}已发送 @消息 到 QQ 群")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            return NicknameManager.all()
                .filter { (nick, oid) ->
                    nick != oid && (nick.lowercase().startsWith(prefix) || oid.startsWith(prefix))
                }
                .map { it.first }
                .distinct()
                .take(20)
        }
        return emptyList()
    }
}
