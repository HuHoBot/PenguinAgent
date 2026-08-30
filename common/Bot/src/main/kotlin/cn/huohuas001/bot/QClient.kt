package cn.huohuas001.bot

import cn.huohuas001.bot.NicknameManager
import cn.huohuas001.bot.agent.AgentInteractionListener
import cn.huohuas001.bot.addon.Addon
import cn.huohuas001.bot.addon.AddonManager
import cn.huohuas001.bot.events.GroupMessageHandler
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.state.CommandRepositories
import cn.huohuas001.bot.tools.QqBotConsoleOutputFilter
import com.alibaba.fastjson.JSON
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.api.Intents
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.entities.ex.Markdown
import io.github.kloping.qqbot.entities.ex.msg.MessageChain
import io.github.kloping.qqbot.entities.qqpd.Channel
import io.github.kloping.qqbot.http.data.V2MsgData

object QClient {
    private lateinit var starter: Starter
    private lateinit var groupMessageHandler: GroupMessageHandler

    /** 获取 QQ Bot Starter 实例（供 Agent 群管理 API 使用）。 */
    fun getStarter(): Starter? = if (::starter.isInitialized) starter else null

    /**
     * 注册指令处理器,收到群消息后会自动分发
     */
    fun registerCommand(command: BaseCommand) {
        check(::groupMessageHandler.isInitialized) {
            "QQ client has not been launched"
        }
        groupMessageHandler.registerCommand(command)
    }

    /**
     * 注册指令处理器并关联扩展元数据。
     * 命令会标记为该扩展的来源，可通过 [AddonManager] 查询。
     */
    fun registerCommand(addon: Addon, command: BaseCommand) {
        check(::groupMessageHandler.isInitialized) {
            "QQ client has not been launched"
        }
        groupMessageHandler.registerCommand(command, addon.name)
        // 将该扩展的命令信息注册到 AddonManager
        val addonCommands = command.registeredCommands().map {
            it.copy(source = addon.name)
        }
        AddonManager.register(addon, addonCommands)
    }

    fun syncGroupPanels() {
        if (!::starter.isInitialized || !::groupMessageHandler.isInitialized) return
        val plugin = BotShared.getPlugin()
        val builtInCommands = groupMessageHandler.registeredCommands()
            .filter { plugin.getCommandMenuList()[it.command] != false }
        val customCommands = CustomCommandRegistry.snapshot().filter { it.pushMenu }.map {
            RegisteredCommand(
                command = it.key,
                describe = "自定义命令",
                onlyAdmin = it.permission > 0
            )
        }
        MenuManager.syncGroupPanels(
            starter = starter,
            groupOpenIds = plugin.getGroupOpenIdList(),
            builtInCommands = builtInCommands,
            customCommands = customCommands
        )
    }

    fun launchClient(appid: String, secret: String, logFilePattern: String? = null) {
        val plugin = BotShared.getPlugin()
        val suppressConsoleOutput = plugin.shouldSuppressQqBotConsoleOutput()
        if (suppressConsoleOutput) {
            QqBotConsoleOutputFilter.install()
        } else {
            QqBotConsoleOutputFilter.uninstall()
        }

        try {
            groupMessageHandler = GroupMessageHandler(plugin)
            starter = Starter(appid, "", secret)
            starter.config.code = Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS)
            starter.run()
            starter.registerListenerHost(groupMessageHandler)
            starter.registerListenerHost(AgentInteractionListener())
            starter.APPLICATION.logger.setLogLevel(1)
            starter.APPLICATION.logger.setOutFile(logFilePattern)
            syncGroupPanels()
            // 加载本地昵称缓存
            NicknameManager.load()
        } catch (error: Exception) {
            if (suppressConsoleOutput) {
                QqBotConsoleOutputFilter.uninstall()
            }
            throw error
        }
    }

    /** 将游戏聊天按配置格式发送到 bot.groups 中的 QQ 群。 */
    fun broadcastGameMessage(playerName: String, message: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        val format = plugin.getChatFormat()
        if (!format.postChat) return
        if (!message.startsWith(format.startWith)) return

        val messageWithoutPrefix = message.removePrefix(format.startWith)
        val filtered = plugin.auditText(messageWithoutPrefix)

        // 检测 @昵称 并转换为 QQ @格式 <@openid>
        val processed = resolveAtMentions(filtered)

        // 绑定：查找发送者是否绑定到某个 QQ 用户
        val binding = findBindingByPlayerName(playerName)
        val qqSenderName = if (binding != null) {
            when (binding.binding.qqDisplayNameMode) {
                "QQ" -> binding.qqName
                else -> escapeMarkdown(playerName)
            }
        } else {
            escapeMarkdown(playerName)
        }

        val safeProcessed = processed.replace("_", "\\_")
        val content = plugin.formatGameMessage(qqSenderName, safeProcessed)
        val markdown = Markdown().setContent(content)
        val payload = V2MsgData()
            .setContent(content)
            .setMsg_type(2)
            .setMarkdown(markdown)
        Thread {
            plugin.getGroupOpenIdList().forEach { groupId ->
                try {
                    starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
                } catch (e: Exception) {
                    plugin.log_error("向QQ群 $groupId 转发游戏聊天失败: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 根据 Minecraft 玩家名查找绑定信息（跨群搜索）。
     * 返回 BindingInfo + QQ 昵称。
     */
    private fun findBindingByPlayerName(playerName: String): BindingLookupResult? {
        val plugin = BotShared.getPlugin()
        for (groupId in plugin.getGroupOpenIdList()) {
            val entry = CommandRepositories.bindings.findByPlayerName(groupId, playerName)
            if (entry != null) {
                val qqName = entry.value.qqUsername.ifEmpty {
                    NicknameManager.getNickname(entry.key)
                        ?: NicknameManager.all().firstOrNull { it.second == entry.key }?.first
                        ?: "QQ用户"
                }
                return BindingLookupResult(entry.value, qqName)
            }
        }
        // 也搜索所有群（包括未配置的群）
        for (groupId in CommandRepositories.bindings.allBindings().keys) {
            val entry = CommandRepositories.bindings.findByPlayerName(groupId, playerName)
            if (entry != null) {
                val qqName = entry.value.qqUsername.ifEmpty {
                    NicknameManager.getNickname(entry.key)
                        ?: NicknameManager.all().firstOrNull { it.second == entry.key }?.first
                        ?: "QQ用户"
                }
                return BindingLookupResult(entry.value, qqName)
            }
        }
        return null
    }

    private data class BindingLookupResult(
        val binding: cn.huohuas001.bot.datapack.BindingInfo,
        val qqName: String
    )

    /**
     * 将消息中的 @昵称 转换为 QQ 的 <@openid> 格式。
     * 支持：@张三、@张三 你好、@张三@李四
     * 也处理直接输入的 @openid（转为昵称显示）。
     * 如果 @的是绑定的 MC 玩家名，也会解析为对应的 QQ @。
     */
    private fun resolveAtMentions(text: String): String {
        var result = text

        // 匹配 @昵称 后面是空格、标点或字符串结尾
        val allMembers = NicknameManager.all()
        if (allMembers.isNotEmpty()) {
            val sorted = allMembers.sortedByDescending { it.first.length }
            for ((nickname, openId) in sorted) {
                val pattern = Regex("@${Regex.escape(nickname)}(?=\\s|[，。！？、；：,.!?;:]|\$)")
                result = result.replace(pattern) { match ->
                    "<@$openId>"
                }
            }
        }

        // 匹配绑定的 MC 玩家名：@PlayerName 或 PlayerName → <@openid>
        val plugin = BotShared.getPlugin()
        for (groupId in plugin.getGroupOpenIdList()) {
            val bindings = CommandRepositories.bindings.allInGroup(groupId)
            for ((_, info) in bindings) {
                val mcName = info.playerName
                val pattern = Regex("(?<![<a-zA-Z0-9])@?${Regex.escape(mcName)}(?![>a-zA-Z0-9])")
                result = result.replace(pattern) { _ ->
                    val entry = CommandRepositories.bindings.findByPlayerName(groupId, mcName)
                    if (entry != null) "<@${entry.key}>" else mcName
                }
            }
        }
        // 处理直接输入的 @openid：尝试转为昵称，找不到就去掉
        result = result.replace(Regex("@([0-9A-Fa-f]{20,})(?=\\s|\$)")) { match ->
            val oid = match.groupValues[1]
            val nick = NicknameManager.getNickname(oid)
            if (nick != null) "@$nick" else ""
        }
        return result
    }

    /** 按配置向所有 QQ 群发送玩家进服通知。 */
    fun broadcastPlayerJoin(playerName: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        if (!plugin.getPlayerEventFormat().joinEnabled) return
        sendTextToGroups(plugin.formatPlayerJoinMessage(escapeMarkdown(playerName)), "发送玩家进服通知")
    }

    /** 按配置向所有 QQ 群发送玩家退服通知。 */
    fun broadcastPlayerQuit(playerName: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        if (!plugin.getPlayerEventFormat().quitEnabled) return
        sendTextToGroups(plugin.formatPlayerQuitMessage(escapeMarkdown(playerName)), "发送玩家退服通知")
    }

    /** 向指定 QQ 群发送文本消息（始终使用 markdown 模式）。 */
    fun sendTextToGroup(groupOpenId: String, content: String) {
        if (!::starter.isInitialized) return
        if (content.isBlank()) return
        val plugin = BotShared.getPlugin()
        val markdown = Markdown().setContent(content)
        val payload = V2MsgData().setContent(content).setMsg_type(2).setMarkdown(markdown)
        Thread {
            try {
                starter.bot.groupBaseV2.send(groupOpenId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            } catch (e: Exception) {
                plugin.log_error("向QQ群 $groupOpenId 发送消息失败: ${e.message}")
            }
        }.start()
    }

    internal fun sendTextToGroups(content: String, action: String) {
        if (content.isBlank()) return
        val plugin = BotShared.getPlugin()
        val markdown = Markdown().setContent(content)
        val payload = V2MsgData().setContent(content).setMsg_type(2).setMarkdown(markdown)
        Thread {
            plugin.getGroupOpenIdList().forEach { groupId ->
                try {
                    starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
                } catch (e: Exception) {
                    plugin.log_error("向QQ群 $groupId ${action}失败: ${e.message}")
                }
            }
        }.start()
    }

    /** 向 bot.groups 中配置的所有 QQ 群发送自定义 Markdown。 */
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null) {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法发送 Markdown")
            return
        }

        val markdown = Markdown().setContent(markdownContent)
        val payload = V2MsgData()
            .setContent(markdownContent)
            .setMsg_type(2)
            .setMarkdown(markdown)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
            payload.setKeyboard(keyboard)
        }

        plugin.getGroupOpenIdList().forEach { groupId ->
            try {
                starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            } catch (e: Exception) {
                plugin.log_error("向QQ群 $groupId 发送 Markdown 失败: ${e.message}")
            }
        }

    }

    /** 向指定 QQ 群发送自定义 Markdown。 */
    fun sendMarkdownToGroup(groupOpenId: String, markdownContent: String, keyboard: Keyboard? = null) {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法发送 Markdown")
            return
        }
        if (markdownContent.isBlank()) return

        val markdown = Markdown().setContent(markdownContent)
        val payload = V2MsgData()
            .setContent(markdownContent)
            .setMsg_type(2)
            .setMarkdown(markdown)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
            payload.setKeyboard(keyboard)
        }

        try {
            starter.bot.groupBaseV2.send(groupOpenId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
        } catch (error: Exception) {
            plugin.log_error("向QQ群 $groupOpenId 发送 Markdown 失败: ${error.message}")
        }
    }

    /** 回复指定群消息并发送普通文本。 */
    fun replyText(event: GroupMessageEvent, text: String): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复文本")
            return false
        }
        if (text.isBlank()) return false
        val groupId = event.groupOpenId ?: event.groupId
        val messageId = event.rawMessage.id.orEmpty()
        val messageSequence = event.msgSeq
        val payload = V2MsgData()
            .setContent(text)
            .setMsg_id(messageId)
            .setMsg_seq(messageSequence)
        return try {
            starter.bot.groupBaseV2.send(
                groupId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("回复文本失败: ${error.message}")
            false
        }
    }

    /** 使用消息快照字段回复普通文本。 */
    fun replyText(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        text: String
    ): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复文本")
            return false
        }
        if (text.isBlank()) return false
        val payload = V2MsgData()
            .setContent(text)
            .setMsg_id(messageId)
            .setMsg_seq(messageSequence)
        return try {
            starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("回复文本失败: ${error.message}")
            false
        }
    }

    /** 使用消息快照字段回复 Markdown。 */
    fun replyMarkdown(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复 Markdown")
            return false
        }
        if (markdownContent.isBlank()) return false

        val markdown = Markdown().setContent(markdownContent)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
        }

        val payload = V2MsgData()
            .setContent(markdownContent)
            .setMsg_type(2)
            .setMarkdown(markdown)
            .setMsg_id(messageId)
            .setMsg_seq(messageSequence)
        if (keyboard != null) {
            payload.setKeyboard(keyboard)
        }

        return try {
            starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("回复 Markdown 失败: ${error.message}")
            false
        }
    }

    /** 回复指定群消息并发送自定义 Markdown。 */
    fun replyMarkdown(
        event: GroupMessageEvent,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复 Markdown")
            return false
        }
        if (markdownContent.isBlank()) return false

        val markdown = Markdown().setContent(markdownContent)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
        }

        val payload = V2MsgData()
            .setContent(markdownContent)
            .setMsg_type(2)
            .setMarkdown(markdown)
            .setMsg_id(event.rawMessage.id)
            .setMsg_seq(event.msgSeq)
        if (keyboard != null) {
            payload.setKeyboard(keyboard)
        }

        return try {
            val groupId = event.groupOpenId ?: event.groupId
            starter.bot.groupBaseV2.send(
                groupId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("回复 Markdown 失败: ${error.message}")
            false
        }
    }

    /** 回复指定群消息，同时发送文本和网络图片。 */
    fun replyWithImg(event: GroupMessageEvent, text: String, imgUrl: String): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复图片消息")
            return false
        }
        if (imgUrl.isBlank()) {
            plugin.log_warning("图片 URL 为空，无法回复图片消息")
            return false
        }

        val message = MessageChain()
            .apply { if (text.isNotBlank()) text(text) }
            .image(imgUrl)

        return try {
            event.sendMessage(message)
            true
        } catch (error: Exception) {
            plugin.log_error("回复图片消息失败: ${error.message}")
            false
        }
    }

    /**
     * /at 命令专用：以 markdown 格式发送 @消息到 QQ 群，跳过 startWith 前缀检查。
     * markdown 格式的 <@openid> 才会触发 QQ 的 @ 通知。
     */
    fun sendAtToGroups(playerName: String, message: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        val format = plugin.getChatFormat()
        if (!format.postChat) return

        val filtered = plugin.auditText(message)
        // markdown 下需转义玩家名中的 _ 等特殊字符，避免被识别为斜体
        val safeName = escapeMarkdown(playerName)
        val content = plugin.formatGameMessage(safeName, filtered)
        val markdown = Markdown().setContent(content)
        val payload = V2MsgData()
            .setContent(content)
            .setMsg_type(2)
            .setMarkdown(markdown)
        plugin.getGroupOpenIdList().forEach { groupId ->
            try {
                starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            } catch (e: Exception) {
                plugin.log_error("向QQ群 $groupId 转发@消息失败: ${e.message}")
            }
        }
    }

    /** 转义 Markdown 特殊字符，防止玩家名被渲染为格式符号。 */
    internal fun escapeMarkdown(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace(">", "\\>")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace(".", "\\.")
            .replace("!", "\\!")
            .replace("|", "\\|")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("{", "\\{")
            .replace("}", "\\}")
    }

    fun shutdown() {
        try {
            if (::starter.isInitialized) starter.shutdown()
        } finally {
            QqBotConsoleOutputFilter.uninstall()
        }
    }
}
