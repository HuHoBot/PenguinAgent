package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** Agent 内置背包与末影箱查询；每个 QQ 用户只对应一个绑定账户。 */
class InventoryCommands : CommandSupport() {

    @Commands(command = "我的背包", describe = "查看绑定账户的背包")
    fun myInventory(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val playerName = boundPlayer(event) ?: run {
            reply(plugin, event, "你还没有绑定 Minecraft 账户，请先使用 /绑定 <游戏ID>")
            return
        }
        sendInventory(plugin, event, playerName)
    }

    @Commands(command = "我的末影箱", describe = "查看绑定账户的末影箱")
    fun myEnderChest(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val playerName = boundPlayer(event) ?: run {
            reply(plugin, event, "你还没有绑定 Minecraft 账户，请先使用 /绑定 <游戏ID>")
            return
        }
        sendEnderChest(plugin, event, playerName)
    }

    @Commands(command = "背包查看", describe = "查看指定玩家的背包", onlyAdmin = true)
    fun viewInventory(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val playerName = requestedPlayer(plugin, event, params, "/背包查看 <玩家名>") ?: return
        sendInventory(plugin, event, playerName)
    }

    @Commands(command = "末影箱查看", describe = "查看指定玩家的末影箱", onlyAdmin = true)
    fun viewEnderChest(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val playerName = requestedPlayer(plugin, event, params, "/末影箱查看 <玩家名>") ?: return
        sendEnderChest(plugin, event, playerName)
    }

    private fun boundPlayer(event: GroupMessageEvent): String? =
        CommandRepositories.bindings.getBinding(groupId(event), userId(event))?.playerName

    private fun requestedPlayer(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        input: String,
        usage: String
    ): String? {
        val requested = input.trim()
        if (requested.isBlank()) {
            reply(plugin, event, "用法：$usage")
            return null
        }
        if (!requested.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            reply(plugin, event, "玩家名格式不正确")
            return null
        }
        return requested
    }

    private fun sendInventory(plugin: HuHoBot, event: GroupMessageEvent, playerName: String) {
        val online = plugin.getOnlineList().any { it.equals(playerName, ignoreCase = true) }
        val image = plugin.getPlayerInventoryImage(playerName)
        if (image != null) {
            replyWithImgBytes(plugin, event, "$playerName 的背包${if (online) "" else "（离线快照）"}", image)
            return
        }
        val inventory = plugin.getPlayerInventory(playerName)
        if (inventory.isNullOrBlank()) {
            reply(plugin, event, missingSnapshotMessage(playerName))
        } else {
            reply(plugin, event, "=== ${QClient.escapeMarkdown(playerName)} 的背包${if (online) "" else "（离线快照）"} ===\n$inventory")
        }
    }

    private fun sendEnderChest(plugin: HuHoBot, event: GroupMessageEvent, playerName: String) {
        val online = plugin.getOnlineList().any { it.equals(playerName, ignoreCase = true) }
        val image = plugin.getPlayerEnderChestImage(playerName)
        if (image == null) {
            reply(plugin, event, missingSnapshotMessage(playerName))
            return
        }
        replyWithImgBytes(plugin, event, "$playerName 的末影箱${if (online) "" else "（离线快照）"}", image)
    }

    private fun missingSnapshotMessage(playerName: String): String =
        "暂无 ${QClient.escapeMarkdown(playerName)} 的离线背包快照；玩家需在安装此版本后至少登录并退出一次"
}
