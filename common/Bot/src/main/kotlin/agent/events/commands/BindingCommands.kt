package agent.events.commands

import agent.HuHoBot
import agent.NicknameManager
import agent.QClient
import agent.datapack.BindingInfo
import agent.state.BindResult
import agent.state.CommandRepositories
import agent.state.LockResult
import agent.state.PendingBindingStore
import agent.update.UpdateChecker
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 角色绑定、账号锁定、显示名称切换和版本查询命令。 */
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

        if (argument.isBlank()) {
            reply(plugin, event, "用法: /绑定 <游戏ID>\n示例: /绑定 Steve\n${bindingLimitHint(plugin)}")
            return
        }

        val playerName = sanitizePlayerName(argument)
        if (playerName == null) {
            reply(plugin, event, "游戏ID格式不正确：不能为空，也不能包含空格、逗号或冒号")
            return
        }

        // 绑定以 openid 为准：任意群绑定成功后，所有群都视为已绑定
        val maxBindings = plugin.getMaxBindings()
        val existing = CommandRepositories.bindings.getBinding(userId)

        if (existing != null && existing.hasPlayer(playerName)) {
            reply(plugin, event, "你已经绑定过角色：${QClient.escapeMarkdown(playerName)}")
            return
        }
        if (existing != null && existing.playerNames.size >= maxBindings) {
            reply(
                plugin, event,
                "你已绑定 ${existing.playerNames.size} 个角色（上限 $maxBindings）：${boundList(existing)}\n" +
                    "如需绑定新角色，请先用 /解除绑定 <游戏ID> 解除一个"
            )
            return
        }

        // MC 玩家名全局唯一，避免多人顶替同一个角色
        if (CommandRepositories.bindings.findByPlayerName(playerName) != null) {
            reply(plugin, event, "游戏ID「$playerName」已被其他用户绑定")
            return
        }

        val qqUsername = event.sender?.username ?: "未知用户"

        if (!plugin.getBindingRequireGameVerification()) {
            // 无需游戏内验证，直接绑定
            when (completeBind(groupId(event), userId, playerName, qqUsername, maxBindings)) {
                BindResult.SUCCESS -> syncWhitelistAdd(plugin, playerName)
                BindResult.PLAYER_TAKEN -> reply(plugin, event, "绑定失败，该角色已被其他用户绑定")
                BindResult.ALREADY_BOUND ->
                    reply(plugin, event, "你已经绑定过角色：${QClient.escapeMarkdown(playerName)}")
                BindResult.LIMIT_REACHED -> reply(plugin, event, limitReachedMessage(plugin))
                BindResult.INVALID_NAME -> reply(plugin, event, "游戏ID格式不正确，请重新输入")
            }
            return
        }

        // 需要游戏内验证
        val code = PendingBindingStore.create(groupId(event), userId, playerName, qqUsername)
        val safeName = playerName.replace("_", "\\_")
        reply(plugin, event, "请使用角色 $safeName 进入服务器执行 ${plugin.getGameCommandPath("qqbind")} $code\n验证码 5 分钟内有效")
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
        // 防爆破：连续输错验证码会临时锁定
        if (PendingBindingStore.isBlocked(userId)) {
            val left = PendingBindingStore.lockRemainingSeconds(userId)
            reply(plugin, event, "验证码错误次数过多，请 ${left / 60 + 1} 分钟后再试")
            return
        }
        // 先看上限再消费验证码：达到上限时验证码保留，玩家解除旧角色后还能继续用
        val maxBindings = plugin.getMaxBindings()
        val bound = CommandRepositories.bindings.getBinding(userId)
        if (bound != null && bound.playerNames.size >= maxBindings) {
            reply(
                plugin, event,
                "你已绑定 ${bound.playerNames.size} 个角色（上限 $maxBindings）：${boundList(bound)}\n" +
                    "请先用 /解除绑定 <游戏ID> 解除一个，再重新进入服务器获取新的验证码"
            )
            return
        }

        val pending = PendingBindingStore.consumeForceCode(code)
        if (pending == null) {
            PendingBindingStore.recordFailure(userId)
            reply(plugin, event, "验证码无效或已过期，请重新进入服务器获取新的验证码")
            return
        }
        PendingBindingStore.clearFailures(userId)

        val playerName = pending.playerName
        val qqUsername = event.sender?.username ?: "未知用户"

        when (completeBind(groupId(event), userId, playerName, qqUsername, maxBindings)) {
            BindResult.SUCCESS -> {
                val safeName = QClient.escapeMarkdown(playerName)
                reply(
                    plugin, event,
                    "绑定成功：${safeName}\n请重新进入服务器即可正常游玩"
                )
                plugin.log_info("强制绑定：$userId 绑定角色 $playerName 成功")
            }
            BindResult.PLAYER_TAKEN ->
                reply(plugin, event, "该验证码已被使用，游戏ID「$playerName」已绑定其他用户")
            BindResult.ALREADY_BOUND ->
                reply(plugin, event, "你已绑定过角色：${QClient.escapeMarkdown(playerName)}，请重新进入服务器")
            BindResult.LIMIT_REACHED -> reply(plugin, event, limitReachedMessage(plugin))
            BindResult.INVALID_NAME -> reply(plugin, event, "游戏ID格式不正确，请联系管理员处理")
        }
    }

    @Commands(command = "解除绑定", describe = "解除 QQ 绑定")
    fun unbind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val userId = userId(event)

        val existing = CommandRepositories.bindings.getBinding(userId)
        if (existing == null) {
            reply(plugin, event, "你还没有绑定任何角色")
            return
        }

        val requested = params.trim()
        val target = if (requested.isEmpty()) {
            if (existing.playerNames.size > 1) {
                reply(
                    plugin, event,
                    "你绑定了多个角色，请指定要解除的角色：/解除绑定 <游戏ID>\n" +
                        "当前绑定：${boundList(existing)}"
                )
                return
            }
            existing.playerName
        } else {
            existing.playerNames.firstOrNull { it.equals(requested, ignoreCase = true) } ?: run {
                reply(plugin, event, "你未绑定角色「$requested」\n当前绑定：${boundList(existing)}")
                return
            }
        }

        if (target.isBlank()) {
            reply(plugin, event, "你还没有绑定任何角色")
            return
        }

        // 解绑会连带移出白名单，先发确认键盘，30 秒内未确认视为取消
        UnbindConfirmation.request(plugin, groupId(event), userId, target)
    }

    /**
     * 管理员强制解绑：撤销冒名顶替的绑定。
     *
     * 支持用 MC 玩家名、openid、QQ 昵称指定目标，也支持直接 @某人。
     * 指定的是角色名时只解绑该角色；指定 openid 或 QQ 昵称时解绑该 QQ 的全部角色。
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
        val specific = info.playerNames.firstOrNull { name ->
            candidates.any { it.equals(name, ignoreCase = true) }
        }
        val removedNames = if (specific != null) {
            if (!bindings.removePlayer(openId, specific)) {
                reply(plugin, event, "解绑失败：绑定关系已变更，请重试")
                return
            }
            listOf(specific)
        } else {
            val names = info.playerNames
            bindings.removeBinding(openId)
            names
        }

        val qqName = info.qqUsername.ifBlank { "未知昵称" }
        val safeNames = removedNames.joinToString("、") { QClient.escapeMarkdown(it) }
        reply(
            plugin, event,
            "已强制解除绑定：$safeNames\n" +
                "QQ：$qqName\nopenid：$openId"
        )
        plugin.log_info("管理员 ${userId(event)} 强制解除了 ${removedNames.joinToString("、")}（openid=$openId）")

        // 强制绑定下同步断开在线会话；syncWhitelistRemove 内部已按开关跳过白名单操作
        removedNames.forEach { playerName ->
            if (plugin.isForceBindEnabled()) {
                plugin.kickUnboundPlayer(playerName)
            } else {
                syncWhitelistRemove(plugin, playerName)
            }
        }
    }

    /**
     * 锁定账号：被锁定的角色无法进入服务器，且在线会话会被立即断开。
     *
     * 多角色账号需要显式指定角色名；只有一个角色时可以省略。
     */
    @Commands(command = "锁定", describe = "锁定自己的游戏账号，禁止其进入服务器")
    fun lockAccount(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        changeLock(plugin, event, params, locked = true)
    }

    /** 解除锁定：之后该角色可以正常进入服务器。 */
    @Commands(command = "解除锁定", describe = "解除自己游戏账号的锁定")
    fun unlockAccount(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        changeLock(plugin, event, params, locked = false)
    }

    private fun changeLock(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        params: String,
        locked: Boolean
    ) {
        val userId = userId(event)
        val binding = CommandRepositories.bindings.getBinding(userId)
        if (binding == null) {
            reply(plugin, event, "你还没有绑定角色，请先使用 /绑定 <游戏ID>")
            return
        }

        val command = if (locked) "锁定" else "解除锁定"
        val target = if (params.trim().isEmpty()) {
            if (binding.playerNames.size > 1) {
                reply(
                    plugin, event,
                    "你绑定了多个角色，请指定要${command}的角色：/$command <游戏ID>\n" +
                        "当前绑定：${boundList(binding)}"
                )
                return
            }
            binding.playerName
        } else {
            binding.playerNames.firstOrNull { it.equals(params.trim(), ignoreCase = true) } ?: run {
                reply(plugin, event, "你未绑定角色「${params.trim()}」\n当前绑定：${boundList(binding)}")
                return
            }
        }

        if (target.isBlank()) {
            reply(plugin, event, "你还没有绑定角色，请先使用 /绑定 <游戏ID>")
            return
        }

        when (CommandRepositories.bindings.setLocked(userId, target, locked)) {
            LockResult.UPDATED -> {
                val safeName = QClient.escapeMarkdown(target)
                reply(
                    plugin, event,
                    if (locked) {
                        "已锁定角色：$safeName\n该账号现在无法进入服务器；如需恢复，请在 QQ 群执行 /解除锁定 $safeName"
                    } else {
                        "已解除锁定：$safeName\n该账号现在可以正常进入服务器"
                    }
                )
                plugin.log_info("$userId $command 角色 $target")
                // 锁定后立即踢出在线会话，否则锁定要等下次进服才生效
                if (locked) plugin.kickLockedPlayer(target)
            }
            LockResult.NOT_FOUND ->
                reply(plugin, event, "绑定关系已变更，请重新执行 /$command")
            LockResult.UNCHANGED ->
                reply(
                    plugin, event,
                    "角色「${QClient.escapeMarkdown(target)}」已经${if (locked) "被锁定" else "处于未锁定状态"}"
                )
        }
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

    /** 角色名不能为空，也不能包含会破坏存档格式的字符。 */
    private fun sanitizePlayerName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 32) return null
        if (name.any { it == ',' || it == ':' || it < ' ' || it == '\u007F' }) return null
        return name
    }

    private fun boundList(info: BindingInfo): String =
        info.playerNames.joinToString("、") { QClient.escapeMarkdown(it) }

    private fun bindingLimitHint(plugin: HuHoBot): String =
        "一个 QQ 号最多可绑定 ${plugin.getMaxBindings()} 个角色"

    private fun limitReachedMessage(plugin: HuHoBot): String =
        "你已达到绑定上限（${plugin.getMaxBindings()} 个角色），请先用 /解除绑定 <游戏ID> 解除一个"

    companion object {
        /**
         * 完成绑定验证后由游戏端 /qqbind 调用：保存绑定并通知 QQ 群。
         *
         * 冲突检查按 MC 玩家名全局进行——同一角色不允许被两个 openid 绑定，
         * 这也是防止未验证模式下冒名顶替的关键。
         *
         * @return 绑定结果；非 [BindResult.SUCCESS] 时调用方负责提示具体原因
         */
        fun completeBind(
            groupId: String,
            openId: String,
            playerName: String,
            qqUsername: String,
            maxBindings: Int
        ): BindResult {
            val result = CommandRepositories.bindings.addBinding(
                openId = openId,
                playerName = playerName,
                qqUsername = qqUsername,
                maxBindings = maxBindings
            )
            if (result != BindResult.SUCCESS) return result

            // 绑定成功同时记下 QQ 昵称，映射有变化就落盘，保证重启后 @昵称 仍可解析
            if (NicknameManager.put(qqUsername, openId)) NicknameManager.save()
            val safeName = QClient.escapeMarkdown(playerName)
            QClient.sendTextToGroup(groupId, "<@$openId> 成功绑定游戏账号：$safeName")
            return BindResult.SUCCESS
        }
    }
}
