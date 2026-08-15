package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 普通群成员可使用的命令。 */
class PublicCommands : CommandSupport() {

    @Commands("查信息")
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

    @Commands("发信息")
    fun sendGameMessage(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) return

        val filtered = plugin.auditText(params)
        if (plugin.getChatFormat().postChat) {
            plugin.broadcastMessage(plugin.formatGroupMessage(userId(event), filtered))
        } else {
            event.sendMessage("群聊转发功能已关闭")
        }
    }

    @Commands("查在线")
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
            event.sendMessage("未找到 Markdown 模板：queryOnline")
            return
        }

        val formattedPlayerList = onlineList.mapIndexed { index, name -> "${index + 1}. **$name**" }.joinToString("\n")

        //替换文本内容
        markdown = markdown
            .replace("{{.server}}", plugin.getServerName())
            .replace("{{.img_url}}", imgUrl)
            .replace("{{.player}}", formattedPlayerList)
            .replace("{{.online_num}}", onlineList.count().toString())

        plugin.replyMarkdown(event, markdown)
    }

    @Commands("在线服务器")
    fun queryServers(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        reply(plugin, event, "当前已连接服务器：${plugin.getBotName()}")
    }

    @Commands("执行")
    fun runCustomCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) {
            event.sendMessage("参数不正确")
            return
        }
        executeCustomCommand(plugin, event, params, admin = false)
    }
}
