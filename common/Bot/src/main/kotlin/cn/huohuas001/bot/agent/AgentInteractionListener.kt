package cn.huohuas001.bot.agent

import cn.huohuas001.bot.provider.BotShared
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.impl.ListenerHost

/** 接收 QQ 消息按钮的点击回调（INTERACTION_CREATE），转发给 [AgentManager]。 */
class AgentInteractionListener : ListenerHost() {

    @EventReceiver
    fun onInterAction(event: InterActionEvent) {
        AgentManager.onInteraction(BotShared.getPlugin(), event)
    }
}
