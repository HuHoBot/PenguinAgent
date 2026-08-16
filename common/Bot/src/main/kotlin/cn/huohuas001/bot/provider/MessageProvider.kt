package cn.huohuas001.bot.provider

import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard

interface MessageProvider {
    fun broadcastMessage(msg: String)

    /** 广播消息，同时对 highlightedPlayers 中的在线玩家播放提示音。 */
    fun broadcastMessage(msg: String, highlightedPlayers: List<String>) = broadcastMessage(msg)

    /** 向配置中的所有 QQ 群发送自定义 Markdown，可选附带消息键盘。 */
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null)

    /** 向指定 QQ 群发送自定义 Markdown，可选附带消息键盘。 */
    fun sendMarkdownToGroup(groupOpenId: String, markdownContent: String, keyboard: Keyboard? = null)

    /** 回复指定的 QQ 群消息，发送自定义 Markdown，可选附带消息键盘。 */
    fun replyMarkdown(
        event: GroupMessageEvent,
        markdownContent: String,
        keyboard: Keyboard? = null
    )

    /** 回复指定的 QQ 群消息，同时发送文本和网络图片。 */
    fun replyWithImg(
        event: GroupMessageEvent,
        text: String,
        imgUrl: String
    )
}
