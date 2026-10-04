package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.NicknameManager
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.state.CommandRepositories
import cn.huohuas001.bot.state.PendingBindingStore
import cn.huohuas001.bot.update.UpdateChecker
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 角色绑定、显示名称切换和版本查询命令。 */
class BindingCommands : CommandSupport() {

    @Commands(command = "绑定", describe = "绑定 QQ 号到 Minecraft 玩家")
    fun bind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)
        val argument = params.trim()

        // 强制绑定：玩家进服被踢出时拿到的 5 位验证码，直接完成绑定
        if (argument.isNotBlank() && plugin.isForceBindEnabled() && isForceCode(argument)) {
            bindWithForceCode(plugin, event, userId, argument)
            return
        }

        // 绑定以 openid 为准：任意群绑定成功后，所有群都视为已绑定
        val existing = CommandRepositories.bindings.getBinding(userId)
        if (existing != null) {
            reply(plugin, event, "你已绑定角色：${QClient.escapeMarkdown(existing.playerName)}，请先解除绑定再重新绑定")
            return
        }

        val playerName = argument
        if (playerName.isBlank()) {
            reply(plugin, event, "用法: /绑定 <游戏ID>\n示例: /绑定 Steve")
            return
        }

        // MC 玩家名全局唯一，避免多人顶替同一个角色
        val conflict = CommandRepositories.bindings.findByPlayerName(playerName)
        if (conflict != null) {
            reply(plugin, event, "游戏ID「$playerName」已被其他用户绑定")
            return
        }

        val qqUsername = event.sender?.username ?: "未知用户"

        if (!plugin.getBindingRequireGameVerification()) {
            // 无需游戏内验证，直接绑定
            val ok = completeBind(groupId(event), userId, playerName, qqUsername)
            if (ok) {
                syncWhitelistAdd(plugin, playerName)
            } else {
                reply(plugin, event, "绑定失败，该角色可能已被绑定")
            }
            return
        }

        // 需要游戏内验证
        val code = PendingBindingStore.create(groupId(event), userId, playerName, qqUsername)
        val safeName = playerName.replace("_", "\\_")
        reply(plugin, event, "请使用角色 $safeName 进入服务器执行 /qqbind $code\n验证码 5 分钟内有效")
    }

    /** 5 位纯数字且当前确有强制绑定验证码在等待，才走验证码绑定流程。 */
    private fun isForceCode(argument: String): Boolean =
        argument.length == 5 && argument.all { it.isDigit() }

    /**
     * 强制绑定下的验证码绑定：验证码已由玩家进服时签发，
     * 这里只需核对验证码与角色占用，直接落库并放行。
     */
    private fun bindWithForceCode(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        userId: String,
        code: String
    ) {
        val pending = PendingBindingStore.consumeForceCode(code)
        if (pending == null) {
            reply(plugin, event, "验证码无效或已过期，请重新进入服务器获取新的验证码")
            return
        }

        val playerName = pending.playerName
        val qqUsername = event.sender?.username ?: "未知用户"

        // 验证码已被别人抢先使用，或该 openid 已绑定其它角色
        val bound = CommandRepositories.bindings.getBinding(userId)
        if (bound != null) {
            reply(plugin, event, "你已绑定角色：${QClient.escapeMarkdown(bound.playerName)}，请先解除绑定再重新绑定")
            return
        }
        val conflict = CommandRepositories.bindings.findByPlayerName(playerName)
        if (conflict != null) {
            reply(plugin, event, "该验证码已被使用，游戏ID「$playerName」已绑定其他用户")
            return
        }

        val ok = completeBind(groupId(event), userId, playerName, qqUsername)
        if (!ok) {
            reply(plugin, event, "绑定失败，该角色可能已被绑定")
            return
        }

        val safeName = QClient.escapeMarkdown(playerName)
        reply(
            plugin, event,
            "绑定成功：${safeName}\n请重新进入服务器即可正常游玩"
        )
        plugin.log_info("强制绑定：$userId 绑定角色 $playerName 成功")
    }

    @Commands(command = "解除绑定", describe = "解除 QQ 绑定")
    fun unbind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)

        val existing = CommandRepositories.bindings.getBinding(userId)
        if (existing == null) {
            reply(plugin, event, "你还没有绑定任何角色")
            return
        }

        // 解绑会连带移出白名单，先发确认键盘，30 秒内未确认视为取消
        UnbindConfirmation.request(plugin, groupId(event), userId, existing.playerName)
    }

    /**
     * 管理员强制解绑：撤销冒名顶替的绑定。
     *
     * 支持用 MC 玩家名、openid、QQ 昵称指定目标，也支持直接 @某人。
     */
    @Commands(command = "强制解绑", describe = "管理员强制解除某人的绑定", onlyAdmin = true)
    fun forceUnbind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return

        val bindings = CommandRepositories.bindings
        val candidates = resolveTargetCandidates(params)
        if (candidates.isEmpty()) {
            reply(plugin, event, "用法: /强制解绑 <MC玩家名 | @某人 | openid | QQ昵称>")
            return
        }

        val entry = candidates.firstNotNullOfOrNull { bindings.find(it) }
        if (entry == null) {
            reply(plugin, event, "未找到「${params.trim()}」的绑定记录")
            return
        }

        val (openId, info) = entry
        bindings.removeBinding(openId)
        val qqName = info.qqUsername.ifBlank { "未知昵称" }
        reply(
            plugin, event,
            "已强制解除绑定：${QClient.escapeMarkdown(info.playerName)}\n" +
                "QQ：$qqName\nopenid：$openId"
        )
        plugin.log_info("管理员 ${userId(event)} 强制解除了 ${info.playerName}（openid=$openId）")

        // 强制绑定下同步断开在线会话；syncWhitelistRemove 内部已按开关跳过白名单操作
        if (plugin.isForceBindEnabled()) {
            plugin.kickUnboundPlayer(info.playerName)
            return
        }

        // 白名单同步：与用户自行解绑保持一致
        syncWhitelistRemove(plugin, info.playerName)
    }

    @Commands(command = "添加免验证", describe = "把玩家加入免验证名单，无需 QQ 绑定即可进入", onlyAdmin = true)
    fun addVerifyExempt(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return

        val playerName = params.trim()
        if (playerName.isBlank()) {
            reply(plugin, event, "用法: /添加免验证 <MC玩家名>")
            return
        }
        if (!playerName.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            reply(plugin, event, "玩家名格式不正确（只允许字母、数字与下划线）")
            return
        }
        if (plugin.isVerifyExempt(playerName)) {
            reply(plugin, event, "「$playerName」已在免验证名单中")
            return
        }

        val updated = plugin.addVerifyExemptPlayer(playerName)
        if (!updated) {
            reply(plugin, event, "写入免验证名单失败，请查看控制台日志")
            return
        }
        val safeName = QClient.escapeMarkdown(playerName)
        reply(plugin, event, "已添加免验证：$safeName\n该玩家无需 QQ 绑定即可进入服务器")
        plugin.log_info("管理员 ${userId(event)} 添加免验证玩家 $playerName")
    }

    @Commands(command = "取消免验证", describe = "从免验证名单中移除玩家", onlyAdmin = true)
    fun removeVerifyExempt(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return

        val playerName = params.trim()
        if (playerName.isBlank()) {
            reply(plugin, event, "用法: /取消免验证 <MC玩家名>")
            return
        }
        if (!plugin.isVerifyExempt(playerName)) {
            reply(plugin, event, "「$playerName」不在免验证名单中")
            return
        }

        val updated = plugin.removeVerifyExemptPlayer(playerName)
        if (!updated) {
            reply(plugin, event, "写入免验证名单失败，请查看控制台日志")
            return
        }
        val safeName = QClient.escapeMarkdown(playerName)
        reply(plugin, event, "已取消免验证：$safeName\n若开启了强制绑定，该玩家下次进服需要完成 QQ 绑定")
        plugin.log_info("管理员 ${userId(event)} 取消免验证玩家 $playerName")
    }

    @Commands(command = "MC显示名称", describe = "切换 QQ→游戏 显示名称")
    fun setMcDisplayName(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)
        val binding = CommandRepositories.bindings.getBinding(userId)
        if (binding == null) {
            reply(plugin, event, "你还没有绑定角色，请先使用 /绑定 <游戏ID>")
            return
        }

        val mode = params.trim().uppercase()
        if (mode != "MC" && mode != "QQ") {
            reply(plugin, event, "用法: /MC显示名称 MC 或 /MC显示名称 QQ\n当前设置: ${binding.mcDisplayNameMode}")
            return
        }

        CommandRepositories.bindings.updateSettings(userId, qqMode = null, mcMode = mode)
        val modeDesc = if (mode == "MC") "游戏ID" else "QQ昵称"
        reply(plugin, event, "QQ→游戏 方向的发送者名称已切换为：$modeDesc")
    }

    @Commands(command = "QQ显示名称", describe = "切换游戏→QQ 显示名称")
    fun setQqDisplayName(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)
        val binding = CommandRepositories.bindings.getBinding(userId)
        if (binding == null) {
            reply(plugin, event, "你还没有绑定角色，请先使用 /绑定 <游戏ID>")
            return
        }

        val mode = params.trim().uppercase()
        if (mode != "MC" && mode != "QQ") {
            reply(plugin, event, "用法: /QQ显示名称 MC 或 /QQ显示名称 QQ\n当前设置: ${binding.qqDisplayNameMode}")
            return
        }

        CommandRepositories.bindings.updateSettings(userId, qqMode = mode, mcMode = null)
        val modeDesc = if (mode == "MC") "游戏ID" else "QQ昵称"
        reply(plugin, event, "游戏→QQ 方向的发送者名称已切换为：$modeDesc")
    }

    @Commands(command = "版本", describe = "查看版本信息")
    fun version(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val current = plugin.getPluginVersion()
        if (!plugin.isUpdateCheckEnabled()) {
            reply(plugin, event, versionText(current, "最新版本：未启用更新检查"))
            return
        }
        val cached = UpdateChecker.cachedState(plugin)
        if (cached != null) {
            reply(plugin, event, versionText(current, latestLine(cached)))
            return
        }
        plugin.submitAsync {
            val state = UpdateChecker.check(plugin, force = true)
            reply(plugin, event, versionText(current, latestLine(state)))
        }
    }

    private fun latestLine(state: UpdateChecker.UpdateState): String = when {
        !state.checked -> "最新版本：暂时无法检查"
        state.outdated -> "最新版本：${state.latest}（检测到更新，请前往官网下载）"
        else -> "最新版本：${state.latest}（已是最新）"
    }

    private fun versionText(current: String, latestLine: String): String =
        "您正在使用 HuHoBot-Penguin $current 版本\n" +
            "$latestLine\n" +
            "官网：${UpdateChecker.SITE_URL}\n" +
            "GitHub：${UpdateChecker.PROJECT}\n" +
            "文档：${UpdateChecker.DOCS}\n" +
            "开发者：Shabby-666（${QClient.escapeMarkdown("_Chinese_Player_")}）"

    /** 白名单同步：绑定时自动添加白名单。 */
    private fun syncWhitelistAdd(plugin: HuHoBot, playerName: String) {
        // 强制绑定本身就是准入控制，与白名单叠加会把未绑定玩家挡在白名单阶段
        if (plugin.isForceBindEnabled()) return
        val whitelist = plugin.getWhiteList()
        if (whitelist.addCommand.isBlank()) return
        val command = plugin.applyPlaceholders(playerName, whitelist.addCommand.replace("{name}", playerName))
        plugin.sendCommand(command).whenComplete { _, error ->
            if (error != null) plugin.log_error("绑定后添加白名单失败: ${error.message}")
        }
    }

    /** 白名单同步：解除绑定时自动移除白名单。 */
    private fun syncWhitelistRemove(plugin: HuHoBot, playerName: String) {
        if (plugin.isForceBindEnabled()) return
        val whitelist = plugin.getWhiteList()
        if (whitelist.delCommand.isBlank()) return
        val command = plugin.applyPlaceholders(playerName, whitelist.delCommand.replace("{name}", playerName))
        plugin.sendCommand(command).whenComplete { _, error ->
            if (error != null) plugin.log_error("解绑后移除白名单失败: ${error.message}")
        }
    }

    companion object {
        /**
         * 完成绑定验证后由游戏端 /qqbind 调用：保存绑定并通知 QQ 群。
         *
         * 冲突检查按 MC 玩家名全局进行——同一角色不允许被两个 openid 绑定，
         * 这也是防止未验证模式下冒名顶替的关键。
         */
        fun completeBind(
            groupId: String,
            openId: String,
            playerName: String,
            qqUsername: String
        ): Boolean {
            val conflict = CommandRepositories.bindings.findByPlayerName(playerName)
            if (conflict != null) return false

            CommandRepositories.bindings.setBinding(openId, playerName, qqUsername)
            NicknameManager.put(qqUsername, openId)
            val safeName = QClient.escapeMarkdown(playerName)
            QClient.sendTextToGroup(groupId, "<@$openId> 成功绑定游戏账号：$safeName")
            return true
        }
    }
}
