package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.NicknameManager
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.addon.AddonManager
import cn.huohuas001.bot.onlinelist.OnlineListBridge
import cn.huohuas001.bot.onlinelist.OnlineListImageReply
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 普通群成员可使用的命令。 */
class PublicCommands : CommandSupport() {

    @Commands(command = "查信息", describe = "查询 OpenId 信息")
    fun queryInfo(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) {
            reply(
                plugin,
                event,
                "你的OpenId:${userId(event)}\n群的OpenId:${groupId(event)}"
            )
            return
        }

        if (!requireAdmin(plugin, event)) return

        val target = params.trim()
        val status = if (CommandRepositories.authentication.contains(groupId(event), target)) {
            "此用户已认证"
        } else {
            "此用户未认证"
        }
        reply(plugin, event, status)
    }

    @Commands(command = "发信息", describe = "发送消息到游戏服务器")
    fun sendGameMessage(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) return

        val filtered = plugin.auditText(params)
        if (plugin.getChatFormat().postChat) {
            // 绑定：如果发送者绑定了角色，使用游戏ID作为发送者名
            val senderId = userId(event)
            val binding = CommandRepositories.bindings.getBinding(senderId)
            val senderName = if (binding != null) {
                when (binding.mcDisplayNameMode) {
                    "MC" -> binding.playerName
                    else -> binding.qqUsername.ifEmpty { NicknameManager.getNickname(senderId) } ?: senderId
                }
            } else {
                NicknameManager.getNickname(senderId) ?: senderId
            }
            val formatted = plugin.formatGroupMessage(senderName, filtered)
            val colored = formatted.replace(Regex("&([0-9a-fk-orA-FK-OR])")) { "§${it.groupValues[1].lowercase()}" }
            plugin.broadcastMessage(colored)
        } else {
            sendMessage(event, "群聊转发功能已关闭")
        }
    }

    @Commands(command = "查在线", describe = "查询在线玩家列表")
    fun queryOnline(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val requested = params.trim()
        // 本地渲染支持分页：/查在线 [页码]，缺省为第 1 页
        val requestedPage = if (requested.isEmpty()) 1 else requested.toIntOrNull()
        if (requestedPage == null || requestedPage < 1) {
            reply(plugin, event, "用法: /查在线 [页码]\n示例: /查在线 1")
            return
        }

        // 平台支持本地渲染时（Spigot/Paper），优先发送本地渲染的在线列表图片
        val handled = OnlineListBridge.handle(groupId(event), requestedPage, object : OnlineListImageReply {
            override fun image(png: ByteArray) {
                replyWithImgBytes(plugin, event, " ", png)
            }

            override fun busy() {
                reply(plugin, event, "在线列表正在生成，请稍候再试。")
            }

            override fun outOfRange(page: Int, totalPages: Int) {
                reply(plugin, event, "没有第 $page 页，当前共 $totalPages 页。")
            }

            override fun failed(message: String) {
                plugin.log_warning("查在线图片发送失败: $message")
                reply(plugin, event, "在线列表生成或发送失败，请稍后再试。")
            }
        })
        if (handled) return

        val onlineList = plugin.getOnlineList()

        val motd = plugin.getMotd()
        val timestampSeconds = System.currentTimeMillis() / 1000
        val imgUrl = motd.api
            .replace("{ip}", motd.serverIP)
            .replace("{port}", motd.serverPort.toString())+"&${timestampSeconds}"



        if (!motd.useMarkdown) {
            val formattedPlayerList = onlineList.mapIndexed { _, name -> name }.joinToString("\n")
            val formatedText = plugin.applyPlaceholders(
                null,
                motd.text
                    .replace("{online}", onlineList.count().toString())
                    .replace("{players}", formattedPlayerList)
            )
            if (motd.postImg) {
                replyWithImg(plugin, event, formatedText, imgUrl)
            } else {
                reply(plugin, event, formatedText)
            }
            return
        }
        //开启Markdown
        var markdown = plugin.getMarkdown("queryOnline")
        if (markdown == null) {
            sendMessage(event, "未找到 Markdown 模板：queryOnline")
            return
        }

        val formattedPlayerList = onlineList.mapIndexed { index, name -> "${index + 1}. **${QClient.escapeMarkdown(name)}**" }.joinToString("\n")

        //替换文本内容
        markdown = plugin.applyPlaceholders(
            null,
            markdown
                .replace("{{.server}}", plugin.getServerName())
                .replace("{{.img_url}}", imgUrl)
                .replace("{{.player}}", formattedPlayerList)
                .replace("{{.online_num}}", onlineList.count().toString())
        )

        plugin.replyMarkdown(event, markdown)
    }

    @Commands(command = "在线服务器", describe = "查看已连接服务器")
    fun queryServers(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        reply(plugin, event, "当前已连接服务器：${plugin.getBotName()}")
    }

    @Commands(command = "帮助", describe = "查看所有命令帮助")
    fun help(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val md = StringBuilder()
        md.appendLine("# HuHoBot 命令帮助")
        md.appendLine()

        // 收集三处命令，按 key 去重并保留优先级最高的来源
        val addonCmdMap = mutableMapOf<String, Pair<RegisteredCommand, String>>()
        for (addon in AddonManager.allAddons()) {
            for (cmd in AddonManager.commandsOf(addon.name)) {
                addonCmdMap.putIfAbsent(cmd.command, cmd to addon.name)
            }
        }
        val customCmdMap = CustomCommandRegistry.snapshot().associateBy { it.key }
        val builtInCmdMap = BaseCommand.allCommands().associateBy { it.command }

        // 优先级：扩展 > 内置 > 自定义
        val addonKeys = addonCmdMap.keys
        val builtInKeys = builtInCmdMap.keys.filter { it !in addonKeys }.toSet()
        val customKeys = customCmdMap.keys.filter { it !in addonKeys && it !in builtInKeys }.toSet()

        data class HelpRow(val name: String, val description: String, val onlyAdmin: Boolean)
        val rows = mutableListOf<HelpRow>()
        for (key in builtInKeys) {
            val cmd = builtInCmdMap.getValue(key)
            rows += HelpRow(cmd.command, cmd.describe, cmd.onlyAdmin)
        }
        for (key in customKeys) {
            val cmd = customCmdMap.getValue(key)
            rows += HelpRow(cmd.key, "${cmd.command}（自定义）", cmd.permission > 0)
        }
        for ((cmd, addonName) in addonCmdMap.values) {
            rows += HelpRow(cmd.command, "${cmd.describe}（扩展：$addonName）", cmd.onlyAdmin)
        }

        fun appendSection(title: String, commands: List<HelpRow>) {
            if (commands.isEmpty()) return
            md.appendLine("## $title")
            md.appendLine()
            md.appendLine("| 命令 | 说明 | 权限 |")
            md.appendLine("| --- | --- | --- |")
            for (cmd in commands.sortedBy { it.name }) {
                val permission = if (cmd.onlyAdmin) "管理员" else "公开"
                md.appendLine("| /${cmd.name} | ${cmd.description} | $permission |")
            }
            md.appendLine()
        }

        appendSection("公开命令", rows.filterNot { it.onlyAdmin })
        if (isAdmin(plugin, event)) {
            appendSection("管理员命令", rows.filter { it.onlyAdmin })
        }

        plugin.replyMarkdown(event, md.toString().trimEnd())
    }

    @Commands(command = "执行", describe = "执行自定义命令")
    fun runCustomCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) {
            sendMessage(event, "参数不正确")
            return
        }
        executeCustomCommand(plugin, event, params, admin = isAdmin(plugin, event))
    }

    @Commands(command = "addons", describe = "查看已安装的扩展")
    fun listAddons(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val addons = AddonManager.allAddons()
        if (addons.isEmpty()) {
            reply(plugin, event, "当前没有已安装的扩展。")
            return
        }

        val md = StringBuilder()
        md.appendLine("# 已安装的扩展")
        md.appendLine()
        md.appendLine("| 名称 | 版本 | 说明 | 作者 | 命令数 |")
        md.appendLine("| --- | --- | --- | --- | --- |")
        for (addon in addons) {
            val cmdCount = AddonManager.commandsOf(addon.name).size
            val desc = addon.description.ifEmpty { "-" }
            val author = addon.author.ifEmpty { "-" }
            md.appendLine("| ${addon.name} | ${addon.version} | $desc | $author | $cmdCount |")
        }

        plugin.replyMarkdown(event, md.toString().trimEnd())
    }

}
