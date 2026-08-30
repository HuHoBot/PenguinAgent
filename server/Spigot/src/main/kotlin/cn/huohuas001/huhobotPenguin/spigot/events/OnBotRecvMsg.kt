package cn.huohuas001.huhobotPenguin.spigot.events

import cn.huohuas001.huhobotPenguin.adapter.api.MsgPack
import io.github.kloping.qqbot.entities.ex.Keyboard
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.Cancellable

/** QQ Bot 收到群消息时触发的 Bukkit 事件。 */
class OnBotRecvMsg(
    val msgPack: MsgPack,
    private val replyTextAction: (String) -> Boolean,
    private val replyMarkdownAction: (String, Keyboard?) -> Boolean
) : Event(), Cancellable {
    constructor(msgPack: MsgPack) : this(msgPack, { false }, { _, _ -> false })

    /** Java/Kotlin 插件使用的消息快照别名。 */
    val message: MsgPack
        get() = msgPack

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    /** 回复触发此事件的 QQ 群消息。 */
    fun reply(text: String): Boolean = replyTextAction(text)

    fun replyText(text: String): Boolean = reply(text)

    fun replyMarkdown(markdown: String): Boolean = replyMarkdownAction(markdown, null)

    fun replyMarkdown(markdown: String, keyboard: Keyboard?): Boolean =
        replyMarkdownAction(markdown, keyboard)

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
