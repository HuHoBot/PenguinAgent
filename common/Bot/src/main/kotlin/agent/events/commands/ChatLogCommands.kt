package agent.events.commands

import agent.HuHoBot
import agent.chatlog.ChatLogBridge
import agent.chatlog.ChatLogReply
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** Public read-only views of recent server chat, rendered in the same visual language as 查在线. */
class ChatLogCommands : CommandSupport() {

    @Commands(command = "聊天记录", describe = "查看服务器最近公共聊天（图片，可翻页）")
    fun recentChat(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val page = pageOrReply(plugin, event, params, "/聊天记录 [页码]") ?: return
        request(plugin, event, null, page)
    }

    @Commands(command = "玩家聊天", describe = "查看指定玩家最近的公共聊天（图片，可翻页）")
    fun playerChat(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val pieces = params.trim().split(Regex("\\s+"), limit = 2)
        val player = pieces.firstOrNull()?.trim().orEmpty()
        if (!player.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            reply(plugin, event, "用法：/玩家聊天 <玩家名> [页码]")
            return
        }
        val page = pageOrReply(plugin, event, pieces.getOrNull(1) ?: "", "/玩家聊天 <玩家名> [页码]") ?: return
        request(plugin, event, player, page)
    }

    private fun pageOrReply(plugin: HuHoBot, event: GroupMessageEvent, raw: String, usage: String): Int? {
        val text = raw.trim()
        val page = if (text.isEmpty()) 1 else text.toIntOrNull()
        if (page == null || page < 1 || page > 999) {
            reply(plugin, event, "用法：$usage")
            return null
        }
        return page
    }

    private fun request(plugin: HuHoBot, event: GroupMessageEvent, player: String?, page: Int) {
        if (!ChatLogBridge.available()) {
            reply(plugin, event, "当前服务端未启用聊天记录图片（platform=spigot 时可用）。")
            return
        }
        val handled = ChatLogBridge.handle(groupId(event), player, page, object : ChatLogReply {
            override fun image(png: ByteArray, caption: String) = replyWithImgBytes(plugin, event, caption, png)
            override fun text(message: String) = reply(plugin, event, message)
            override fun busy() = reply(plugin, event, "查询太频繁，请稍后再试。")
            override fun outOfRange(page: Int, totalPages: Int) =
                reply(plugin, event, "页码超出范围：当前共 $totalPages 页。")
            override fun failed(message: String) {
                plugin.log_warning("聊天记录图片失败: $message")
                reply(plugin, event, "聊天记录生成失败，请稍后再试。")
            }
        })
        if (!handled) reply(plugin, event, "聊天记录功能未就绪，请稍后再试。")
    }
}
