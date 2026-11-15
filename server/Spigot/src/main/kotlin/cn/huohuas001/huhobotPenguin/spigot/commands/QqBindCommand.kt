package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.bot.events.commands.BindingCommands
import cn.huohuas001.bot.state.BindResult
import cn.huohuas001.bot.state.PendingBindingStore
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class QqBindCommand : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}此命令仅限游戏内使用")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("${ChatColor.RED}用法: /qqbind <验证码>")
            sender.sendMessage("${ChatColor.GRAY}验证码由 QQ 群内 /绑定 命令生成")
            return true
        }

        val code = args[0].trim()
        val pending = PendingBindingStore.consume(code)
        if (pending == null) {
            sender.sendMessage("${ChatColor.RED}验证码无效或已过期，请在 QQ 群中重新使用 /绑定 命令")
            return true
        }

        val playerName = sender.name

        // 验证请求中的游戏ID与当前玩家是否一致
        if (!pending.playerName.equals(playerName, ignoreCase = true)) {
            sender.sendMessage("${ChatColor.RED}此验证码绑定的游戏ID为「${pending.playerName}」，与你当前账号不匹配")
            return true
        }

        val plugin = HuHoBotSpigot.getInstance() ?: return true

        val result = BindingCommands.completeBind(
            groupId = pending.groupId,
            openId = pending.openId,
            playerName = playerName,
            qqUsername = pending.qqUsername,
            maxBindings = plugin.getMaxBindings()
        )

        if (result == BindResult.SUCCESS) {
            sender.sendMessage("${ChatColor.GREEN}已绑定QQ账号：${ChatColor.WHITE}${pending.qqUsername}")
            // 绑定成功后自动添加白名单
            val whitelist = plugin.getWhiteList()
            if (whitelist.addCommand.isNotBlank()) {
                val cmd = whitelist.addCommand.replace("{name}", playerName)
                Bukkit.getScheduler().runTask(plugin) { _: Any? ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                }
            }
        } else {
            val reason = when (result) {
                BindResult.PLAYER_TAKEN -> "游戏ID「$playerName」已被其他账号绑定"
                BindResult.ALREADY_BOUND -> "你的 QQ 已经绑定过角色「$playerName」"
                BindResult.LIMIT_REACHED -> "你的 QQ 已达到绑定上限（${plugin.getMaxBindings()} 个角色）"
                BindResult.INVALID_NAME -> "游戏ID格式不正确"
                BindResult.SUCCESS -> ""
            }
            sender.sendMessage("${ChatColor.RED}绑定失败：$reason")
        }
        return true
    }
}
