package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.NicknameManager
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.state.CommandRepositories
import cn.huohuas001.bot.state.PendingBindingStore
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 角色绑定、显示名称切换和版本查询命令。 */
class BindingCommands : CommandSupport() {

    @Commands(command = "绑定", describe = "绑定 QQ 号到 Minecraft 玩家")
    fun bind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)
        val groupId = groupId(event)

        // 检查是否已绑定
        val existing = CommandRepositories.bindings.getBinding(groupId, userId)
        if (existing != null) {
            reply(plugin, event, "你已绑定角色：${QClient.escapeMarkdown(existing.playerName)}，请先解除绑定再重新绑定")
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

        val qqUsername = event.sender?.username ?: "未知用户"

        if (!plugin.getBindingRequireGameVerification()) {
            // 无需游戏内验证，直接绑定
            val conflictByPlayer = CommandRepositories.bindings.findByPlayerName(groupId, playerName)
            if (conflictByPlayer != null) {
                reply(plugin, event, "游戏ID「$playerName」已被其他用户绑定")
                return
            }
            val ok = BindingCommands.Companion.completeBind(groupId, userId, playerName, qqUsername)
            if (ok) {
                val safeName = QClient.escapeMarkdown(playerName)
                reply(plugin, event, "已成功绑定游戏账号：$safeName")
                syncWhitelistAdd(plugin, event, playerName)
            } else {
                reply(plugin, event, "绑定失败，该角色可能已被绑定")
            }
            return
        }

        // 需要游戏内验证
        val code = PendingBindingStore.create(groupId, userId, playerName, qqUsername)
        val safeName = playerName.replace("_", "\\_")
        reply(plugin, event, "请使用角色 $safeName 进入服务器执行 /qqbind $code\n验证码 5 分钟内有效")
    }

    @Commands(command = "解除绑定", describe = "解除 QQ 绑定")
    fun unbind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)
        val groupId = groupId(event)

        val existing = CommandRepositories.bindings.getBinding(groupId, userId)
        if (existing == null) {
            reply(plugin, event, "你还没有绑定任何角色")
            return
        }

        CommandRepositories.bindings.removeBinding(groupId, userId)
        reply(plugin, event, "已解除角色绑定：${QClient.escapeMarkdown(existing.playerName)}")

        // 白名单同步
        syncWhitelistRemove(plugin, event, existing.playerName)
    }

    @Commands(command = "MC显示名称", describe = "切换 QQ→游戏 显示名称")
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

    @Commands(command = "QQ显示名称", describe = "切换游戏→QQ 显示名称")
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

    @Commands(command = "版本", describe = "查看版本信息")
    fun version(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val version = plugin.getPluginVersion()
        reply(
            plugin, event,
            "您正在使用 HuHoBot-Penguin $version 版本\n" +
                "开发者：Shabby-666（${QClient.escapeMarkdown("_Chinese_Player_")}）\n" +
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

    companion object {
        /** 完成绑定验证后由游戏端 /qqbind 调用：保存绑定并通知 QQ 群。 */
        fun completeBind(
            groupId: String,
            openId: String,
            playerName: String,
            qqUsername: String
        ): Boolean {
            val conflict = CommandRepositories.bindings.findByPlayerName(groupId, playerName)
            if (conflict != null) return false

            CommandRepositories.bindings.setBinding(groupId, openId, playerName, qqUsername)
            NicknameManager.put(qqUsername, openId)
            val safeName = QClient.escapeMarkdown(playerName)
            QClient.sendTextToGroup(groupId, "<@$openId> 成功绑定游戏账号：$safeName")
            return true
        }
    }
}
