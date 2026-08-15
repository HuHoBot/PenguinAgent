package cn.huohuas001.huhobotPenguin.nukkit.commands

import cn.huohuas001.huhobotPenguin.nukkit.HuHoBotNukkit
import cn.nukkit.command.CommandSender
import cn.nukkit.command.PluginCommand
import cn.nukkit.utils.TextFormat

class HuHoBotCommand(private val plugin: HuHoBotNukkit) : PluginCommand<HuHoBotNukkit>("huhobot", plugin) {
    init { description = "HuHoBot control command"; usage = "/huhobot reload|info" }
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
        if (sender.isPlayer && !sender.isOp) { sender.sendMessage(TextFormat.DARK_RED.toString() + "你没有足够的权限。"); return true }
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> { plugin.reloadPluginConfig(); sender.sendMessage(TextFormat.GOLD.toString() + "已重载配置文件。") }
            "info" -> sender.sendMessage("平台: ${plugin.getPlatform()}\n版本: ${plugin.getPluginVersion()}")
            else -> { sender.sendMessage("/huhobot reload - 重载配置文件"); sender.sendMessage("/huhobot info - 查看适配器信息") }
        }
        return true
    }
}
