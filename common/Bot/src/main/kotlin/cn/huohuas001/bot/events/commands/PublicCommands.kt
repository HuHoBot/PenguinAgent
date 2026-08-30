package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.NicknameManager
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.addon.AddonManager
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
            val binding = CommandRepositories.bindings.getBinding(groupId(event), senderId)
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
        val onlineList = plugin.getOnlineList()

        val motd = plugin.getMotd()
        val timestampSeconds = System.currentTimeMillis() / 1000
        val imgUrl = motd.api
            .replace("{ip}", motd.serverIP)
            .replace("{port}", motd.serverPort.toString())+"&${timestampSeconds}"



        if (!motd.useMarkdown) {
            val formattedPlayerList = onlineList.mapIndexed { _, name -> name }.joinToString("\n")
            val formatedText = motd.text
                .replace("{online}", onlineList.count().toString())
                .replace("{players}", formattedPlayerList)
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
        markdown = markdown
            .replace("{{.server}}", plugin.getServerName())
            .replace("{{.img_url}}", imgUrl)
            .replace("{{.player}}", formattedPlayerList)
            .replace("{{.online_num}}", onlineList.count().toString())

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

        // 收集三处命令，按 key 去重，每个 key 只归一个分类
        val addonCmdMap = mutableMapOf<String, RegisteredCommand>()
        for (addon in AddonManager.allAddons()) {
            for (cmd in AddonManager.commandsOf(addon.name)) {
                addonCmdMap.putIfAbsent(cmd.command, cmd)
            }
        }
        val customCmdMap = CustomCommandRegistry.snapshot().associateBy { it.key }
        val builtInCmdMap = BaseCommand.allCommands().associateBy { it.command }

        // 优先级：扩展 > 内置 > 自定义
        val addonKeys = addonCmdMap.keys
        val builtInKeys = builtInCmdMap.keys.filter { it !in addonKeys }.toSet()
        val customKeys = customCmdMap.keys.filter { it !in addonKeys && it !in builtInKeys }.toSet()

        // 内置命令
        if (builtInKeys.isNotEmpty()) {
            md.appendLine("## 内置命令")
            md.appendLine()
            md.appendLine("| 命令 | 说明 | 权限 |")
            md.appendLine("| --- | --- | --- |")
            for (key in builtInKeys.sorted()) {
                val cmd = builtInCmdMap[key]!!
                val perm = if (cmd.onlyAdmin) "管理员" else "公开"
                md.appendLine("| ${cmd.command} | ${cmd.describe} | $perm |")
            }
            md.appendLine()
        }

        // 自定义命令
        if (customKeys.isNotEmpty()) {
            md.appendLine("## 自定义命令")
            md.appendLine()
            md.appendLine("| 命令 | 说明 | 权限 |")
            md.appendLine("| --- | --- | --- |")
            for (key in customKeys.sorted()) {
                val c = customCmdMap[key]!!
                val perm = if (c.permission > 0) "管理员" else "公开"
                md.appendLine("| ${c.key} | ${c.command} | $perm |")
            }
            md.appendLine()
        }

        // 扩展命令
        for (addon in AddonManager.allAddons()) {
            val addonCmds = AddonManager.commandsOf(addon.name)
            if (addonCmds.isNotEmpty()) {
                md.appendLine("## 扩展：${addon.name}")
                md.appendLine()
                md.appendLine("| 命令 | 说明 | 权限 |")
                md.appendLine("| --- | --- | --- |")
                for (cmd in addonCmds.sortedBy { it.command }) {
                    val perm = if (cmd.onlyAdmin) "管理员" else "公开"
                    md.appendLine("| ${cmd.command} | ${cmd.describe} | $perm |")
                }
                md.appendLine()
            }
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
