package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 角色绑定、显示名称切换和版本查询命令。 */
class BindingCommands : CommandSupport() {

    @Commands("绑定")
    fun bind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)
        val groupId = groupId(event)

        // 检查是否已绑定
        val existing = CommandRepositories.bindings.getBinding(groupId, userId)
        if (existing != null) {
            reply(plugin, event, "你已绑定角色：${existing.playerName}，请先解除绑定再重新绑定")
            return
        }

        val playerName = params.trim()
        if (playerName.isBlank()) {
            reply(plugin, event, "用法: /绑定 <游戏ID>\n示例: /绑定 Steve")
            return
        }

        // 检查该玩家名是否已被其他用户绑定
        val conflict = CommandRepositories.bindings.findByPlayerName(groupId, playerName)
        if (conflict != null) {
            reply(plugin, event, "游戏ID「$playerName」已被其他用户绑定")
            return
        }

        CommandRepositories.bindings.setBinding(groupId, userId, playerName)
        reply(plugin, event, "已绑定游戏ID：$playerName")

        // 白名单同步
        syncWhitelistAdd(plugin, event, playerName)
    }

    @Commands("解除绑定", "解绑")
    fun unbind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)
        val groupId = groupId(event)

        val existing = CommandRepositories.bindings.getBinding(groupId, userId)
        if (existing == null) {
            reply(plugin, event, "你还没有绑定任何角色")
            return
        }

        CommandRepositories.bindings.removeBinding(groupId, userId)
        reply(plugin, event, "已解除角色绑定：${existing.playerName}")

        // 白名单同步
        syncWhitelistRemove(plugin, event, existing.playerName)
    }

    @Commands("MC显示名称")
    fun setMcDisplayName(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)
        val groupId = groupId(event)
        val binding = CommandRepositories.bindings.getBinding(groupId, userId)
        if (binding == null) {
            reply(plugin, event, "你还没有绑定角色，请先使用 /绑定 <游戏ID>")
            return
        }

        val mode = params.trim().uppercase()
        if (mode != "MC" && mode != "QQ") {
            reply(plugin, event, "用法: /MC显示名称 MC 或 /MC显示名称 QQ\n当前设置: ${binding.mcDisplayNameMode}")
            return
        }

        CommandRepositories.bindings.updateSettings(groupId, userId, qqMode = null, mcMode = mode)
        val modeDesc = if (mode == "MC") "游戏ID" else "QQ昵称"
        reply(plugin, event, "QQ→游戏 方向的发送者名称已切换为：$modeDesc")
    }

    @Commands("QQ显示名称")
    fun setQqDisplayName(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)
        val groupId = groupId(event)
        val binding = CommandRepositories.bindings.getBinding(groupId, userId)
        if (binding == null) {
            reply(plugin, event, "你还没有绑定角色，请先使用 /绑定 <游戏ID>")
            return
        }

        val mode = params.trim().uppercase()
        if (mode != "MC" && mode != "QQ") {
            reply(plugin, event, "用法: /QQ显示名称 MC 或 /QQ显示名称 QQ\n当前设置: ${binding.qqDisplayNameMode}")
            return
        }

        CommandRepositories.bindings.updateSettings(groupId, userId, qqMode = mode, mcMode = null)
        val modeDesc = if (mode == "MC") "游戏ID" else "QQ昵称"
        reply(plugin, event, "游戏→QQ 方向的发送者名称已切换为：$modeDesc")
    }

    @Commands("版本")
    fun version(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val version = plugin.getPluginVersion()
        reply(
            plugin, event,
            "您正在使用 HuHoBot-Penguin $version 版本\n" +
                "开发者：Shabby-666（_Chinese_Player_）\n" +
                "Github：https://github.com/HuHoBot/PenguinAgent"
        )
    }

    /** 白名单同步：绑定时自动添加白名单。 */
    private fun syncWhitelistAdd(plugin: HuHoBot, event: GroupMessageEvent, playerName: String) {
        val whitelist = plugin.getWhiteList()
        if (whitelist.addCommand.isBlank()) return
        val command = whitelist.addCommand.replace("{name}", playerName)
        executeGameCommand(plugin, event, command, direct = true)
    }

    /** 白名单同步：解除绑定时自动移除白名单。 */
    private fun syncWhitelistRemove(plugin: HuHoBot, event: GroupMessageEvent, playerName: String) {
        val whitelist = plugin.getWhiteList()
        if (whitelist.delCommand.isBlank()) return
        val command = whitelist.delCommand.replace("{name}", playerName)
        executeGameCommand(plugin, event, command, direct = true)
    }
}
