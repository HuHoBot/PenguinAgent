package cn.huohuas001.bot.agent

import cn.huohuas001.bot.events.commands.UnbindConfirmation
import cn.huohuas001.bot.provider.BotShared
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.impl.ListenerHost

/**
 * 把 QQ 消息按钮的交互（INTERACTION_CREATE）转交给 [AgentManager] 与解绑确认流程。
 */
class AgentInteractionListener : ListenerHost() {

    @EventReceiver
    fun onInterAction(event: InterActionEvent) {
        val interaction = event.interAction
        val data = interaction?.data?.resolved?.button_data

        // 官方要求：收到互动事件后必须回应，否则客户端会一直 loading 直到超时，
        // 用户看到的是「请求第三方失败 / 请求超时」。先回应，再做业务处理。
        if (interaction != null) {
            respondQuietly(event)
        }

        if (data != null && UnbindConfirmation.onInteraction(
                interaction?.groupOpenid.orEmpty(),
                interaction?.groupMemberOpenid.orEmpty(),
                data
            )
        ) {
            return
        }
        AgentManager.onInteraction(BotShared.getPlugin(), event)
    }

    /**
     * 回应互动事件。
     *
     * code=0 表示成功；失败只记 debug 日志——这个端点偶发 405（SDK 内部走 jsoup 会先打日志），
     * 但不影响按钮功能。
     */
    private fun respondQuietly(event: InterActionEvent) {
        try {
            event.response(0)
        } catch (error: Throwable) {
            try {
                BotShared.getPlugin().log_debug("回应互动事件失败: ${error.message}")
            } catch (_: Throwable) {
            }
        }
    }
}