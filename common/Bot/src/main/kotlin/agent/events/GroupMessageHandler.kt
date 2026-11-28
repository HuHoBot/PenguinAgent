package agent.events

import agent.HuHoBot
import agent.NicknameManager
import agent.ai.AgentCommands
import agent.events.commands.AdministrationCommands
import agent.events.commands.BaseCommand
import agent.events.commands.BindingCommands
import agent.events.commands.ChatLogCommands
import agent.events.commands.InventoryCommands
import agent.events.commands.MotdCommands
import agent.events.commands.PublicCommands
import agent.events.commands.RegisteredCommand
import agent.state.CommandRepositories
import agent.state.GroupDirectory
import agent.tools.FaceEmojiParser
import agent.tools.MessageAttachmentParser
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.impl.ListenerHost
import io.github.kloping.qqbot.impl.message.v2.BaseMessageEvent
import java.util.concurrent.CopyOnWriteArrayList

/** QQ 群消息事件入口，负责群限制、命令分发和全量聊天转发。 */
class GroupMessageHandler(
    private val plugin: HuHoBot
) : ListenerHost() {
    private val commands = CopyOnWriteArrayList<BaseCommand>()

    init {
        registerCommand(PublicCommands())
        registerCommand(AdministrationCommands())
        registerCommand(AgentCommands())
        registerCommand(MotdCommands())
        registerCommand(BindingCommands())
        registerCommand(InventoryCommands())
        registerCommand(agent.ai.ChatAiCommands())
        registerCommand(ChatLogCommands())
    }

    fun registerCommand(command: BaseCommand) {
        commands.add(command)
    }

    /**
     * 注册指令处理器并标记来源扩展。
     * 注册后会将该处理器的所有命令标记为 [source] 扩展。
     */
    fun registerCommand(command: BaseCommand, source: String) {
        commands.add(command)
        // 回溯扫描刚注册的命令，将 source 写入 allRegisteredCommands 中的对应条目
        val registered = command.registeredCommands()
        // 先通过反射拿到可变列表并快照，避免多线程下索引偏移
        val mutableList = getRegisteredCommandsList() ?: return
        val snapshot = mutableList.toList()
        for (rc in registered) {
            val index = snapshot.indexOfFirst { it.command == rc.command }
            if (index >= 0) {
                val old = snapshot[index]
                if (old.source == null) {
                    mutableList[index] = old.copy(source = source)
                }
            }
        }
    }

    private fun getRegisteredCommandsList(): MutableList<RegisteredCommand>? {
        return try {
            val field = BaseCommand::class.java.getDeclaredField("allRegisteredCommands")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            field.get(null) as MutableList<RegisteredCommand>
        } catch (_: Exception) {
            null
        }
    }

    /** 汇总所有实际注册的指令，供 QQ 指令面板自动同步。 */
    fun registeredCommands(): List<RegisteredCommand> = commands
        .flatMap { it.registeredCommands() }
        .distinctBy { it.command }
        .sortedBy { it.command }

    /** 公域机器人只有在被 @ 时才会收到此事件。 */
    @EventReceiver
    fun onGroupMessage(event: GroupMessageEvent) {
        val groupId = event.groupOpenId ?: event.groupId
        val content = event.rawMessage.content ?: return

        // 缓存发送者昵称（每次收到消息都更新）
        val senderName = event.sender?.username
        val senderOpenId = event.sender?.openid ?: event.sender?.id
        if (!senderName.isNullOrBlank() && !senderOpenId.isNullOrBlank()) {
            // put 返回"映射是否变化"；变化才落盘，避免每条消息都重写 nicknames.dat
            if (NicknameManager.put(senderName, senderOpenId)) NicknameManager.save()
        }

        // 被动缓存群成员角色：群消息的 author 自带 member_role，供审批鉴权查询
        val senderRole = try {
            event.sender?.meta?.getString("member_role")
        } catch (_: Exception) {
            null
        }
        if (!senderOpenId.isNullOrBlank() && !senderRole.isNullOrBlank()) {
            CommandRepositories.memberRoles.put(groupId, senderOpenId, senderRole)
        }

        // 黑名单：直接忽略该用户的消息（可配置只拒绝入群而不拦消息）
        if (plugin.isBlacklistBlockMessages() && !senderOpenId.isNullOrBlank() &&
            senderOpenId in plugin.getBlacklistOpenIds()
        ) {
            return
        }

        val configured = plugin.getGroupOpenIdList()
        // 群列表为空或未收录该群时，收到任意消息即自动写入 bot.groups
        if (configured.isEmpty() || groupId !in configured) {
            if (plugin.addGroupOpenId(groupId)) {
                GroupDirectory.markAdded(groupId)
            }
        }
        if (!isAllowedGroup(groupId)) return
        runCatching {
            val plain = Regex("<@!?[^>]+>").replace(content, "").trim()
            if (plain.isNotEmpty()) {
                agent.chatlog.GroupChatLog.record(groupId, senderName ?: "QQ用户", plain)
            }
        }
        // 群被动窗口固定为入站后 60 分钟，发送次数由当前群的额度配置决定。
        agent.QClient.noteInbound(groupId, event.rawMessage.id.orEmpty())

        // QQ → 游戏：先审核再分发，避免违规内容进入命令、AI 或全量转发路径。
        // 提醒使用事件中的 member_openid 自动 @，不要求服主手抄 message_id。
        val moderation = plugin.audit(content)
        if (moderation.blocked) {
            plugin.log_warning("已拦截一条 QQ 入站违规消息（group=$groupId，命中=${moderation.matchedWords.size} 条${if (moderation.aiBlocked) "，AI复核" else ""}）")
            // 提示可配置：默认只撤回不发送拦截提示（notify-user: false）。
            if (plugin.isModerationNotifyUserEnabled()) {
                agent.QClient.replyModerationWarning(event, "你的消息包含违规内容，已被拦截。")
            }
            agent.QClient.autoRecallInbound(groupId, event.rawMessage.id.orEmpty(), "QQ入站违禁词")
            return
        }
        // 「记忆」命令：分页查看 / 添加 / 删除 AI 记忆（存储于 data/agent-memory/*.yml）。
        if (handleMemoryCommand(plugin, groupId, content.trim(), event)) return

        when (dispatchCommand(event)) {
            BaseCommand.DispatchResult.CUSTOM_COMMAND -> Unit

            BaseCommand.DispatchResult.HANDLED -> Unit
            BaseCommand.DispatchResult.NOT_HANDLED -> {
                // 聊天 AI：@机器人 的非命令消息交给聊天助手（启用时），不再转发进游戏
                val chatAiEnabled = plugin.getChatAiEnabled()
                if (chatAiEnabled) {
                    val mentionStripped = Regex("<@!?[^>]+>").replace(content, "").trim()
                    if (mentionStripped.isNotEmpty()) {
                        val handledByChatAi = agent.ai.ChatAiManager.handle(
                            plugin, groupId,
                            senderOpenId ?: "",
                            senderName ?: "QQ用户",
                            mentionStripped
                        ) { reply -> agent.QClient.replyText(event, reply) }
                        if (handledByChatAi) return
                    }
                }
                forwardFullGroupMessage(groupId, event)
            }
        }
    }

    private fun isAllowedGroup(groupId: String): Boolean {
        val allowedGroups = plugin.getGroupOpenIdList()
        return allowedGroups.isEmpty() || groupId in allowedGroups
    }

    private fun dispatchCommand(event: GroupMessageEvent): BaseCommand.DispatchResult {
        val content = event.rawMessage.content.orEmpty()
        val isSlashCommand = Regex("<@!?[^>]+>").replace(content, "").trim().startsWith("/")

        // 斜杠命令先让所有处理器完成内置命令匹配
        if (isSlashCommand) {
            for (command in commands) {
                try {
                    if (command.handleMessage(
                            plugin,
                            event,
                            allowCustomFallback = false
                        ) != BaseCommand.DispatchResult.NOT_HANDLED
                    ) {
                        return BaseCommand.DispatchResult.HANDLED
                    }
                } catch (error: Exception) {
                    plugin.log_error("指令处理异常: ${error.message}")
                }
            }
        }

        for (command in commands) {
            try {
                val result = command.handleMessage(plugin, event)
                if (result != BaseCommand.DispatchResult.NOT_HANDLED) return result
            } catch (error: Exception) {
                plugin.log_error("指令处理异常: ${error.message}")
            }
        }
        return BaseCommand.DispatchResult.NOT_HANDLED
    }

    private fun forwardFullGroupMessage(groupId: String, event: GroupMessageEvent) {
        val enabled = CommandRepositories.groupSettings
            .fullForwarding(groupId, plugin.getFullAmount())
        if (!enabled || !plugin.getChatFormat().postChat) return

        // 缓存发送者昵称（每次收到消息都更新）
        val rawSenderName = event.sender?.username ?: "unknown"
        val senderOpenId = event.sender?.openid ?: event.sender?.id ?: ""
        if (rawSenderName != "unknown" && senderOpenId.isNotEmpty()) {
            if (NicknameManager.put(rawSenderName, senderOpenId)) NicknameManager.save()
        }
        // 解析显示名：如果 username 是 openid，尝试从 NicknameManager 获取真实昵称
        val senderNickName = if (rawSenderName.matches(Regex("[0-9A-Fa-f]{20,}")) && senderOpenId.isNotEmpty()) {
            NicknameManager.getNickname(senderOpenId) ?: "QQ用户"
        } else {
            rawSenderName
        }

        // 绑定：根据用户设置决定显示名
        val binding = if (senderOpenId.isNotEmpty()) {
            CommandRepositories.bindings.getBinding(senderOpenId)
        } else null
        val senderName = if (binding != null) {
            when (binding.mcDisplayNameMode) {
                "MC" -> binding.playerName
                else -> senderNickName
            }
        } else {
            senderNickName
        }

        // 从原始 JSON 提取附件信息
        val metadata = (event as? BaseMessageEvent<*>)?.metadata
        val attachmentsJson = metadata?.getJSONArray("attachments")

        val parts = mutableListOf<String>()

        // 文本内容
        val textContent = event.rawMessage.content?.trim().orEmpty()
            .replace(Regex("<faceType=[^>]*>"), "")
            .replace(Regex("<image[^>]*>"), "")
            .trim()
        if (textContent.isNotEmpty()) {
            parts.add(textContent)
        }

        // 附件内容
        if (attachmentsJson != null) {
            for (i in attachmentsJson.indices) {
                val att = attachmentsJson.getJSONObject(i) ?: continue
                val contentType = att.getString("content_type") ?: ""
                when {
                    contentType == "voice" -> {
                        val asrText = att.getString("asr_refer_text")?.trim()
                        parts.add(if (!asrText.isNullOrEmpty()) "[语音消息] [$asrText]" else "[语音消息]")
                    }
                    contentType == "image/gif" -> {
                        parts.add("[表情包]")
                    }
                    contentType.startsWith("video/") -> {
                        parts.add("[视频]")
                    }
                    contentType.startsWith("image/") -> {
                        parts.add("[图片]")
                    }
                    else -> {
                        val filename = att.getString("filename") ?: "文件"
                        parts.add("[文件: $filename]")
                    }
                }
            }
        }

        if (parts.isEmpty()) return

        var message = parts.joinToString(" ")

        // 解析 @提及
        val mentions = event.rawMessage.mentions
        if (mentions != null) {
            var nicknamesChanged = false
            for (mention in mentions) {
                val mentionId = mention.id ?: continue
                val mentionName = mention.username ?: continue
                if (mentionName != "unknown" && mentionId.isNotEmpty()) {
                    if (NicknameManager.put(mentionName, mentionId)) nicknamesChanged = true
                }
                val mentionBinding = CommandRepositories.bindings.getBinding(mentionId)
                val displayName = if (mentionBinding != null) {
                    when (mentionBinding.mcDisplayNameMode) {
                        "MC" -> mentionBinding.playerName
                        else -> {
                            if (mentionName.matches(Regex("[0-9A-Fa-f]{20,}")) && mentionId.isNotEmpty()) {
                                NicknameManager.getNickname(mentionId) ?: "QQ用户"
                            } else {
                                mentionName
                            }
                        }
                    }
                } else {
                    if (mentionName.matches(Regex("[0-9A-Fa-f]{20,}")) && mentionId.isNotEmpty()) {
                        NicknameManager.getNickname(mentionId) ?: "QQ用户"
                    } else {
                        mentionName
                    }
                }
                message = message
                    .replace("<@!$mentionId>", "@$displayName")
                    .replace("<@$mentionId>", "@$displayName")
                    .replace("<$mentionId>", "@$displayName")
                    .replace(mentionId, "@$displayName")
            }
            // 一条消息里可能有多个新昵称，循环结束后统一落盘一次，避免多次整文件重写
            if (nicknamesChanged) NicknameManager.save()
        }

        // 清理消息中残留的 raw openid 文本
        message = message.replace(Regex("[0-9A-Fa-f]{20,}"), "")

        // 解析表情和附件标签
        message = MessageAttachmentParser.parse(message)
        message = FaceEmojiParser.parse(message)

        val filtered = plugin.auditText(message)

        // 清理终端格式化代码
        val cleaned = filtered
            .replace(Regex("\u001B\\[[0-9;]*m"), "")
            .replace(Regex("\\[[0-9;]*m"), "")
            .replace(Regex("§[0-9a-fk-orA-FK-OR]"), "")

        // 检测在线玩家名，用 §9（蓝色）包裹，前面加 @；跳过发送者自己
        val onlinePlayers = plugin.getOnlineList()
        var highlighted = cleaned
        val mentionedPlayers = mutableListOf<String>()
        for (playerName in onlinePlayers) {
            if (playerName.length < 2) continue
            if (playerName == senderName) continue
            if (cleaned.contains(playerName)) {
                mentionedPlayers.add(playerName)
                highlighted = highlighted.replace(playerName, "§9@$playerName§r")
            }
        }

        val formatted = plugin.applyPlaceholders(
            binding?.playerName,
            plugin.formatGroupMessage(senderName, highlighted)
        )
        val colored = formatted.replace(Regex("&([0-9a-fk-orA-FK-OR])")) { "§${it.groupValues[1].lowercase()}" }
        plugin.broadcastMessage(colored, mentionedPlayers)
    }
}


/**
 * 「记忆」命令处理：查看（分页）/ 添加 / 删除 / 清空。
 * - 记忆 [页码]         分页查看
 * - 记忆添加 <内容>     追加一条记忆（受 agent.memory.max-items 上限约束）
 * - 记忆删除 <序号>     删除指定序号
 * - 记忆清空            清空本群记忆
 */
private fun handleMemoryCommand(
    plugin: agent.HuHoBot,
    groupId: String,
    content: String,
    event: io.github.kloping.qqbot.api.v2.GroupMessageEvent
): Boolean {
    if (!plugin.isAgentMemoryEnabled()) return false
    val trimmed = content.trim()
    val isList = trimmed == "记忆" || trimmed.startsWith("记忆 ")
    val isAdd = trimmed.startsWith("记忆添加 ")
    val isDel = trimmed == "记忆删除" || trimmed.startsWith("记忆删除 ")
    val isClear = trimmed == "记忆清空"
    if (!isList && !isAdd && !isDel && !isClear) return false
    if (isList && trimmed.startsWith("记忆 ") && trimmed.substring(3).trim().toIntOrNull() == null) return false
    val pageSize = 8
    fun reply(text: String) {
        runCatching { agent.QClient.replyText(event, text) }
            .onFailure { plugin.log_warning("记忆命令回复失败: ${it.message}") }
    }
    when {
        isList -> {
            val pageArg = trimmed.substringAfter(" ", "").trim().toIntOrNull() ?: 1
            val items = agent.ai.MemoryStore.list(groupId)
            if (items.isEmpty()) { reply("本群还没有 AI 记忆。AI 会在需要时自动保存，也可以用「记忆添加 <内容>」手动添加。"); return true }
            val totalPages = (items.size + pageSize - 1) / pageSize
            val page = pageArg.coerceIn(1, totalPages)
            val sb = StringBuilder("AI 记忆（第 $page/$totalPages 页，共 ${items.size} 条）\n")
            val start = (page - 1) * pageSize
            items.drop(start).take(pageSize).forEachIndexed { i, e ->
                sb.append(start + i + 1).append(". ").append(e.text.take(60)).append('\n')
            }
            if (page < totalPages) sb.append("发送「记忆 ${page + 1}」查看下一页")
            reply(sb.toString().trim())
        }
        isAdd -> {
            val text = trimmed.removePrefix("记忆添加 ").trim()
            if (text.isEmpty()) { reply("用法：记忆添加 <内容>"); return true }
            reply(if (agent.ai.MemoryStore.add(groupId, text)) "已保存记忆" else "该记忆已存在或内容为空")
        }
        isDel -> {
            val arg = trimmed.removePrefix("记忆删除").trim()
            if (arg == "全部" || arg == "all") { agent.ai.MemoryStore.clear(groupId); reply("已清空本群全部记忆"); return true }
            val idx = arg.toIntOrNull()
            if (idx == null) { reply("用法：记忆删除 <序号> 或 记忆删除 全部"); return true }
            reply(if (agent.ai.MemoryStore.removeAt(groupId, idx)) "已删除第 $idx 条记忆" else "序号 $idx 不存在")
        }
        isClear -> { agent.ai.MemoryStore.clear(groupId); reply("已清空本群全部记忆") }
    }
    return true
}
