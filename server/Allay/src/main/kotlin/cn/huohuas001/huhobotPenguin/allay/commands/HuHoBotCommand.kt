package cn.huohuas001.huhobotPenguin.allay.commands

import cn.huohuas001.huhobotPenguin.allay.HuHoBotAllay
import org.allaymc.api.command.Command
import org.allaymc.api.command.tree.CommandTree
import org.allaymc.api.utils.TextFormat

class HuHoBotCommand(private val plugin: HuHoBotAllay) : Command(
    "huhobot", "HuHoBot control command", "huhobot.command"
) {
    override fun prepareCommandTree(tree: CommandTree) {
        tree.root.key("reload").exec { context ->
            plugin.reloadPluginConfig()
            context.addOutput(TextFormat.GOLD.toString() + "已重载配置文件。")
            context.success()
        }.root()
            .key("info").exec { context ->
                context.addOutput("平台: ${plugin.getPlatform()}\n版本: ${plugin.getPluginVersion()}")
                context.success()
            }.root()
            .key("help").exec { context ->
                context.addOutput("/huhobot reload - 重载配置文件")
                context.addOutput("/huhobot info - 查看适配器信息")
                context.success()
            }
    }
}
