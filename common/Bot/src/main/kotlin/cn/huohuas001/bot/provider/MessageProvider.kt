package cn.huohuas001.bot.provider

import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard

interface MessageProvider {
    fun broadcastMessage(msg: String)

    /** 广播消息，同时对 highlightedPlayers 中的在线玩家播放提示音。 */
    fun broadcastMessage(msg: String, highlightedPlayers: List<String>) = broadcastMessage(msg)

    /** QQ 群消息收到后的平台回调，返回 true 表示阻止后续全量聊天转发。 */
    fun onBotReceivedGroupMessage(event: GroupMessageEvent, messageSequence: Int): Boolean = false

    /** 命中平台注册的自定义命令时回调，返回 true 表示阻止后续全量聊天转发。 */
    fun onBotCommand(event: GroupMessageEvent, messageSequence: Int): Boolean = false

    /** 向配置中的所有 QQ 群发送普通文本。 */
    fun sendText(text: String)

    /** 向配置中的所有 QQ 群发送自定义 Markdown，可选附带消息键盘。 */
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null)

    /** 向指定 QQ 群发送自定义 Markdown，可选附带消息键盘。 */
    fun sendMarkdownToGroup(groupOpenId: String, markdownContent: String, keyboard: Keyboard? = null)

    /** 回复指定的 QQ 群消息，发送普通文本。 */
    fun replyText(event: GroupMessageEvent, text: String): Boolean

    /** 回复指定的 QQ 群消息，发送自定义 Markdown，可选附带消息键盘。 */
    fun replyMarkdown(
        event: GroupMessageEvent,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): Boolean

    /** 回复指定的 QQ 群消息，同时发送文本和网络图片。 */
    fun replyWithImg(
        event: GroupMessageEvent,
        text: String,
        imgUrl: String
    ): Boolean
}
