package cn.huohuas001.bot.events

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.NicknameManager
import cn.huohuas001.bot.agent.AgentCommands
import cn.huohuas001.bot.events.commands.AdministrationCommands
import cn.huohuas001.bot.events.commands.AuthenticationCommands
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.BindingCommands
import cn.huohuas001.bot.events.commands.InventoryCommands
import cn.huohuas001.bot.events.commands.MotdCommands
import cn.huohuas001.bot.events.commands.PublicCommands
import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.state.CommandRepositories
import cn.huohuas001.bot.tools.FaceEmojiParser
import cn.huohuas001.bot.tools.MessageAttachmentParser
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
        registerCommand(AuthenticationCommands())
        registerCommand(AgentCommands())
        registerCommand(MotdCommands())
        registerCommand(BindingCommands())
        registerCommand(InventoryCommands())
    }

    fun registerCommand(command: BaseCommand) {
        commands.add(command)
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
            NicknameManager.put(senderName, senderOpenId)
            val isNew = NicknameManager.getOpenId(senderName) != senderOpenId
            if (isNew) NicknameManager.save()
        }

        if(!content.contains("查信息")){
            if (!isAllowedGroup(groupId)) return
        }
        when (dispatchCommand(event)) {
            BaseCommand.DispatchResult.CUSTOM_COMMAND -> Unit

            BaseCommand.DispatchResult.HANDLED -> Unit
            BaseCommand.DispatchResult.NOT_HANDLED -> {
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
            NicknameManager.put(rawSenderName, senderOpenId)
        }
        // 解析显示名：如果 username 是 openid，尝试从 NicknameManager 获取真实昵称
        val senderNickName = if (rawSenderName.matches(Regex("[0-9A-Fa-f]{20,}")) && senderOpenId.isNotEmpty()) {
            NicknameManager.getNickname(senderOpenId) ?: "QQ用户"
        } else {
            rawSenderName
        }

        // 绑定：根据用户设置决定显示名
        val binding = if (senderOpenId.isNotEmpty()) {
            CommandRepositories.bindings.getBinding(groupId, senderOpenId)
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
            for (mention in mentions) {
                val mentionId = mention.id ?: continue
                val mentionName = mention.username ?: continue
                if (mentionName != "unknown" && mentionId.isNotEmpty()) {
                    NicknameManager.put(mentionName, mentionId)
                }
                val mentionBinding = CommandRepositories.bindings.getBinding(groupId, mentionId)
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

        val formatted = plugin.formatGroupMessage(senderName, highlighted)
        val colored = formatted.replace(Regex("&([0-9a-fk-orA-FK-OR])")) { "§${it.groupValues[1].lowercase()}" }
        plugin.broadcastMessage(colored, mentionedPlayers)
    }
}
