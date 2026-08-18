package cn.huohuas001.bot.events

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.NicknameManager
import cn.huohuas001.bot.agent.AgentCommands
import cn.huohuas001.bot.events.commands.AdministrationCommands
import cn.huohuas001.bot.events.commands.AuthenticationCommands
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.BindingCommands
import cn.huohuas001.bot.events.commands.MotdCommands
import cn.huohuas001.bot.events.commands.PublicCommands
import cn.huohuas001.bot.state.CommandRepositories
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
    }

    fun registerCommand(command: BaseCommand) {
        commands.add(command)
    }

    /** 公域机器人只有在被 @ 时才会收到此事件。 */
    @EventReceiver
    fun onGroupMessage(event: GroupMessageEvent) {
        val groupId = event.groupOpenId ?: event.groupId
        val content = event.rawMessage.content ?: return

        // 缓存发送者昵称（每次收到消息都更新）
        val senderName = event.sender?.username
        val senderOpenId = event.sender?.openid ?: event.sender?.id
        if (!senderName.isNullOrBlank() && !senderOpenId.isNullOrBlank()) {
            // 即使 username 是 openid 也缓存，用于反查
            NicknameManager.put(senderName, senderOpenId)
            val isNew = NicknameManager.getOpenId(senderName) != senderOpenId
            if (isNew) NicknameManager.save()
        }

        if(!content.contains("查信息")){
            if (!isAllowedGroup(groupId)) return
        }
        if (dispatchCommand(event)) return
        forwardFullGroupMessage(groupId, event)
    }

    private fun isAllowedGroup(groupId: String): Boolean {
        val allowedGroups = plugin.getGroupOpenIdList()
        return allowedGroups.isEmpty() || groupId in allowedGroups
    }

    private fun dispatchCommand(event: GroupMessageEvent): Boolean {
        for (command in commands) {
            try {
                if (command.handleMessage(plugin, event)) return true
            } catch (error: Exception) {
                plugin.log_error("指令处理异常: ${error.message}")
            }
        }
        return false
    }

    private fun forwardFullGroupMessage(groupId: String, event: GroupMessageEvent) {
        val enabled = CommandRepositories.groupSettings
            .fullForwarding(groupId, plugin.getFullAmount())
        if (!enabled || !plugin.getChatFormat().postChat) return

        val rawSenderName = event.sender?.username ?: "unknown"
        val senderOpenId = event.sender?.openid ?: event.sender?.id ?: ""
        // 缓存发送者昵称 → openid（仅当 username 不是 openid 时缓存）
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
                "QQ" -> binding.playerName
                else -> senderNickName
            }
        } else {
            senderNickName
        }

        // 从原始 JSON 提取附件信息（SDK 未映射 asr_refer_text 等新字段）
        val metadata = (event as? BaseMessageEvent<*>)?.metadata
        val attachmentsJson = metadata?.getJSONArray("attachments")

        val parts = mutableListOf<String>()

        // 文本内容（清理 SDK 原始图片/表情标签）
        val textContent = event.rawMessage.content?.trim().orEmpty()
            .replace(Regex("<faceType=[^>]*>"), "")
            .replace(Regex("<image[^>]*>"), "")
            .trim()
        if (textContent.isNotEmpty()) {
            parts.add(textContent)
        }

        // 附件内容（图片已在 SDK 卡片中渲染，不再重复添加 [图片]）
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
                        // 图片已在卡片内渲染，跳过
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

        val mentions = event.rawMessage.mentions
        if (mentions != null) {
            for (mention in mentions) {
                val mentionId = mention.id ?: continue
                val mentionName = mention.username ?: continue
                // 缓存被 @ 的成员昵称（拒绝 openid 作为昵称）
                if (mentionName != "unknown" && mentionId.isNotEmpty()) {
                    NicknameManager.put(mentionName, mentionId)
                }
                // 解析显示名：优先使用绑定的游戏ID
                val mentionBinding = CommandRepositories.bindings.getBinding(groupId, mentionId)
                val displayName = if (mentionBinding != null) {
                    when (mentionBinding.mcDisplayNameMode) {
                        "QQ" -> mentionBinding.playerName
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

        // 清理消息中残留的 raw openid 文本（SDK 可能在文本中包含 openid 字面量）
        message = message.replace(Regex("[0-9A-Fa-f]{20,}"), "")

        val filtered = plugin.auditText(message)

        // 清理 Minecraft 颜色/格式代码（§x），避免在聊天框显示为乱码
        val cleaned = filtered.replace(Regex("§[0-9a-fk-orA-FK-OR]"), "")

        // 检测在线玩家名，用 §9（蓝色）包裹，前面加 @；只保留消息中实际出现的玩家
        val onlinePlayers = plugin.getOnlineList()
        var highlighted = cleaned
        val mentionedPlayers = mutableListOf<String>()
        for (playerName in onlinePlayers) {
            if (playerName.length < 2) continue
            if (cleaned.contains(playerName)) {
                mentionedPlayers.add(playerName)
                highlighted = highlighted.replace(playerName, "§9@$playerName§r")
            }
        }

        plugin.broadcastMessage(plugin.formatGroupMessage(senderName, highlighted), mentionedPlayers)
    }
}
