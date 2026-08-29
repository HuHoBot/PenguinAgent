package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 背包查看命令：查看指定在线玩家的背包内容（PNG 图片）。 */
class InventoryCommands : CommandSupport() {

    @Commands(command = "背包查看", describe = "查看在线玩家的背包内容", onlyAdmin = true)
    fun viewInventory(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val targetName = params.trim()
        if (targetName.isBlank()) {
            sendMessage(event, "用法: /背包查看 <玩家名>")
            return
        }

        val onlineList = plugin.getOnlineList()
        val matched = onlineList.firstOrNull { it.equals(targetName, ignoreCase = true) }
        if (matched == null) {
            sendMessage(event, "玩家 ${QClient.escapeMarkdown(targetName)} 不在线")
            return
        }

        // 尝试渲染 PNG 图片
        val imgBytes = plugin.getPlayerInventoryImage(matched)
        if (imgBytes != null) {
            replyWithImgBytes(plugin, event, "${matched} 的背包", imgBytes)
            return
        }

        // 回退到文本模式
        val inventory = plugin.getPlayerInventory(matched)
        if (inventory.isNullOrBlank()) {
            sendMessage(event, "无法获取 $matched 的背包信息")
            return
        }
        reply(plugin, event, "=== $matched 的背包 ===\n$inventory")
    }
}
