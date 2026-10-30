package cn.huohuas001.bot.agent

import cn.huohuas001.bot.InteractionOutcome
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
        // 用户看到的是「请求第三方失败 / 请求超时」。
        //
        // 但回 0（成功）时客户端会弹「操作成功」，所以回应交给具体流程按分支决定：
        // 真正处理成功才回 0；无权限、被他人点击、已过期等一律回非零让客户端静默。
        // respondOnce 保证只回应一次，末尾兜底回应避免漏回导致一直 loading。
        var responded = false
        val respondOnce: (Int) -> Unit = { code ->
            if (!responded) {
                responded = true
                respondQuietly(event, code)
            }
        }

        try {
            val handled = UnbindConfirmation.onInteraction(
                interaction?.groupOpenid.orEmpty(),
                interaction?.groupMemberOpenid.orEmpty(),
                data.orEmpty(),
                respondOnce
            ) != null
            if (!handled) {
                AgentManager.onInteraction(BotShared.getPlugin(), event, respondOnce)
            }
        } catch (error: Throwable) {
            try {
                BotShared.getPlugin().log_error("处理互动事件失败: ${error.message}")
            } catch (_: Throwable) {
            }
        } finally {
            // 流程里没有明确回应过（例如数据格式异常提前返回）时静默收尾
            respondOnce(InteractionOutcome.SILENT.code)
        }
    }

    /**
     * 回应互动事件。
     *
     * 失败只记 debug 日志——这个端点偶发 405（SDK 内部走 jsoup 会先打日志），
     * 但不影响按钮功能。
     */
    private fun respondQuietly(event: InterActionEvent, code: Int) {
        try {
            event.response(code)
        } catch (error: Throwable) {
            try {
                BotShared.getPlugin().log_debug("回应互动事件失败: ${error.message}")
            } catch (_: Throwable) {
            }
        }
    }
}