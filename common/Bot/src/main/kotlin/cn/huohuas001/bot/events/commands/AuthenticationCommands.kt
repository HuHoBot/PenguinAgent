package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** QQ OpenId 认证状态命令。 */
class AuthenticationCommands : CommandSupport() {

    @Commands("认证")
    fun authenticate(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) {
            val authenticated = CommandRepositories.authentication.contains(groupId(event), userId(event))
            reply(plugin, event, if (authenticated) "你已认证" else "你未认证")
            return
        }

        if (!requireAdmin(plugin, event)) return
        val targetOpenId = params.trim().substringAfterLast(' ')
        CommandRepositories.authentication.authenticate(groupId(event), targetOpenId)
        reply(plugin, event, "认证状态已更新")
    }

    @Commands("解除认证")
    fun removeAuthentication(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            event.sendMessage("请指定要解除认证的OpenId")
            return
        }

        CommandRepositories.authentication.revoke(groupId(event), params.trim())
        reply(plugin, event, "认证已解除")
    }
}
