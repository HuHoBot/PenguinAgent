package cn.huohuas001.bot.events

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.agent.AgentCommands
import cn.huohuas001.bot.events.commands.AdministrationCommands
import cn.huohuas001.bot.events.commands.AuthenticationCommands
import cn.huohuas001.bot.events.commands.BaseCommand
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
    }

    fun registerCommand(command: BaseCommand) {
        commands.add(command)
    }

    /** 公域机器人只有在被 @ 时才会收到此事件。 */
    @EventReceiver
    fun onGroupMessage(event: GroupMessageEvent) {
        val groupId = event.groupOpenId ?: event.groupId
        val content = event.rawMessage.content ?: return

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

        val senderName = event.sender?.username ?: "unknown"

        // 从原始 JSON 提取附件信息（SDK 未映射 asr_refer_text 等新字段）
        val metadata = (event as? BaseMessageEvent<*>)?.metadata
        val attachmentsJson = metadata?.getJSONArray("attachments")

        val parts = mutableListOf<String>()

        // 文本内容
        val textContent = event.rawMessage.content?.trim().orEmpty()
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
                    contentType.startsWith("image/") -> {
                        parts.add("[图片]")
                    }
                    contentType == "image/gif" -> {
                        parts.add("[表情包]")
                    }
                    contentType.startsWith("video/") -> {
                        parts.add("[视频]")
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
                message = message
                    .replace("<@!$mentionId>", "@$mentionName")
                    .replace("<@$mentionId>", "@$mentionName")
                    .replace("<$mentionId>", "@$mentionName")
                    .replace(mentionId, "@$mentionName")
            }
        }

        plugin.broadcastMessage(plugin.formatGroupMessage(senderName, plugin.auditText(message)))
    }
}
