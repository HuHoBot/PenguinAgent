package agent.events.commands

import agent.HuHoBot
import agent.QClient
import agent.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** Agent 内置背包与末影箱查询；一个 QQ 用户可绑定多个游戏账户。 */
class InventoryCommands : CommandSupport() {

    @Commands(command = "我的背包", describe = "查看绑定账户的背包")
    fun myInventory(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val playerName = boundPlayer(plugin, event, params) ?: return
        sendInventory(plugin, event, playerName)
    }

    @Commands(command = "我的末影箱", describe = "查看绑定账户的末影箱")
    fun myEnderChest(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val playerName = boundPlayer(plugin, event, params) ?: return
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

    @Commands(command = "背包物品查询", describe = "查看玩家背包指定原版槽位的物品", onlyAdmin = true)
    fun viewInventoryItem(plugin: HuHoBot, event: GroupMessageEvent, params: String) =
        sendSingleItem(plugin, event, params, false)

    @Commands(command = "末影箱物品查询", describe = "查看玩家末影箱指定槽位的物品", onlyAdmin = true)
    fun viewEnderItem(plugin: HuHoBot, event: GroupMessageEvent, params: String) =
        sendSingleItem(plugin, event, params, true)

    private fun sendSingleItem(plugin: HuHoBot, event: GroupMessageEvent, params: String, ender: Boolean) {
        val pieces = params.trim().split(Regex("\\s+"), limit = 2)
        if (pieces.size != 2) {
            reply(plugin, event, if (ender) "用法：/末影箱物品查询 <玩家名> <0-26>" else "用法：/背包物品查询 <玩家名> <0-40>")
            return
        }
        val playerName = pieces[0]
        val slot = pieces[1].toIntOrNull()
        val max = if (ender) 26 else 40
        if (!playerName.matches(Regex("[A-Za-z0-9_]{1,16}")) || slot == null || slot !in 0..max) {
            reply(plugin, event, if (ender) "参数错误：末影箱槽位只能是 0-26" else "参数错误：背包槽位只能是 0-40")
            return
        }
        val image = plugin.getPlayerInventoryItemImage(playerName, slot, ender)
        val info = plugin.getPlayerInventoryItemInfo(playerName, slot, ender)
        if (image == null || info == null) {
            reply(plugin, event, "找不到该玩家、槽位为空，或玩家从未在本服产生可读的存档数据。")
            return
        }
        replyWithImgBytes(plugin, event, info.lineSequence().firstOrNull().orEmpty(), image)
        reply(plugin, event, info)
    }

    /**
     * 解析要查看的绑定账户。
     *
     * 不填参数时使用主账号；绑定多个账号时可显式指定，避免只能看第一个。
     */
    private fun boundPlayer(plugin: HuHoBot, event: GroupMessageEvent, params: String): String? {
        val binding = CommandRepositories.bindings.getBinding(userId(event))
        if (binding == null) {
            reply(plugin, event, "你还没有绑定 Minecraft 账户，请先使用 /绑定 <游戏ID>")
            return null
        }

        val requested = params.trim()
        if (requested.isEmpty()) return binding.playerName

        val target = binding.playerNames.firstOrNull { it.equals(requested, ignoreCase = true) }
        if (target == null) {
            val list = binding.playerNames.joinToString("、")
            reply(plugin, event, "你未绑定角色「$requested」\n当前绑定：$list")
        }
        return target
    }

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
        val resolvedName = plugin.getOnlineList().firstOrNull { it.equals(playerName, ignoreCase = true) }
            ?: playerName
        val online = plugin.getOnlineList().any { it.equals(resolvedName, ignoreCase = true) }
        val image = plugin.getPlayerInventoryImage(resolvedName)
        if (image != null) {
            replyWithImgBytes(plugin, event, "$resolvedName 的背包${if (online) "" else "（离线快照）"}", image)
            return
        }
        val inventory = plugin.getPlayerInventory(resolvedName)
        if (inventory.isNullOrBlank()) {
            reply(plugin, event, missingSnapshotMessage(resolvedName))
        } else {
            reply(plugin, event, "=== ${QClient.escapeMarkdown(resolvedName)} 的背包${if (online) "" else "（离线快照）"} ===\n$inventory")
        }
    }

    private fun sendEnderChest(plugin: HuHoBot, event: GroupMessageEvent, playerName: String) {
        val resolvedName = plugin.getOnlineList().firstOrNull { it.equals(playerName, ignoreCase = true) }
            ?: playerName
        val online = plugin.getOnlineList().any { it.equals(resolvedName, ignoreCase = true) }
        val image = try {
            plugin.getPlayerEnderChestImage(resolvedName)
        } catch (error: Exception) {
            plugin.log_error("末影箱渲染失败: ${error.message}")
            null
        }
        if (image == null) {
            if (online) {
                reply(plugin, event, "渲染失败：无法生成 ${QClient.escapeMarkdown(resolvedName)} 的末影箱图片，请稍后重试")
            } else {
                reply(plugin, event, missingSnapshotMessage(resolvedName))
            }
            return
        }
        replyWithImgBytes(plugin, event, "$resolvedName 的末影箱${if (online) "" else "（离线快照）"}", image)
    }

    private fun missingSnapshotMessage(playerName: String): String =
        "暂无 ${QClient.escapeMarkdown(playerName)} 的离线背包快照；玩家需在安装此版本后至少登录并退出一次"
}
