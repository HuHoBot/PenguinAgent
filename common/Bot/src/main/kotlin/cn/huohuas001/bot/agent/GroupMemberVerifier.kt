package cn.huohuas001.bot.agent

import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.state.CommandRepositories
import com.alibaba.fastjson.JSONObject

/**
 * 群成员校验 —— 退群自动解绑。
 *
 * QQ 官方 Bot API **不推送普通成员退群事件**（只有机器人被移出群 GROUP_DEL_ROBOT），
 * 因此无法通过事件即时解绑。这里用轮询白名单接口 `GET /v2/groups/{gid}/members`
 * 定期拉取各群成员列表：
 * - 绑定者 openid 仍出现在任一已配置群 → 保留绑定；
 * - 所有群都查不到 → 视为已退群/被移出，调用 [CommandRepositories.bindings] 解绑并记录日志；
 * - 接口返回 11253（非白名单能力）→ 永久停用并提示管理员，不影响其他功能。
 *
 * 配置：binding.member-check.{enabled,interval-minutes,notify-group}
 */
object GroupMemberVerifier {

    private const val FORBIDDEN = "11253"

    @Volatile
    private var started = false

    @Volatile
    private var platformDisabled = false

    fun start() {
        val plugin = BotShared.getPluginOrNull() ?: return
        if (!plugin.isMemberCheckEnabled() || started || platformDisabled) return
        val interval = plugin.getMemberCheckIntervalMinutes().coerceIn(5, 24 * 60).toLong() * 60_000L / 50L
        started = true
        plugin.submitTimer(interval, interval) {
            if (!plugin.isMemberCheckEnabled() || platformDisabled) return@submitTimer
            runCatching { verifyOnce() }.onFailure {
                plugin.log_warning("群成员校验失败: ${it.message}")
            }
        }
        plugin.log_info("退群自动解绑已启用（每 ${plugin.getMemberCheckIntervalMinutes()} 分钟校验一次）")
    }

    fun stop() {
        started = false
    }

    /**
     * 执行一次校验：拉取所有已配置群的成员 openid，解绑已经不在任何群里的用户。
     * 返回解绑人数（-1 表示接口不可用）。
     */
    fun verifyOnce(): Int {
        val plugin = BotShared.getPluginOrNull() ?: return -1
        val starter = QClient.getStarter() ?: return -1
        val groups = plugin.getGroupOpenIdList()
        if (groups.isEmpty()) return 0

        val present = HashSet<String>()
        for (groupOpenId in groups) {
            var cursor = ""
            var page = 0
            while (page < 20) {
                val response = runCatching {
                    val query = if (cursor.isBlank()) "?limit=100" else "?limit=100&cursor=$cursor"
                    GroupManagementApi.request(starter, "GET", "/v2/groups/$groupOpenId/members$query")
                }.getOrNull() ?: break
                val code = response.getInteger("code")
                if (code != null && code.toString() == FORBIDDEN) {
                    platformDisabled = true
                    plugin.log_warning(
                        "群成员列表是 QQ 白名单能力（11253），退群自动解绑已停用；" +
                            "申请到该能力后重启服务器即可自动生效"
                    )
                    return -1
                }
                val members = response.getJSONArray("members") ?: break
                for (index in 0 until members.size) {
                    val member = members.getJSONObject(index) ?: continue
                    (member.getString("member_openid") ?: member.getString("openid"))?.let { present.add(it) }
                }
                cursor = response.getString("next_cursor").orEmpty()
                if (cursor.isBlank()) break
                page++
            }
        }
        if (present.isEmpty()) return 0

        var unbound = 0
        val bindings = CommandRepositories.bindings
        for ((openId, info) in bindings.snapshot().entries.toList()) {
            if (openId in present) continue
            if (bindings.removeBinding(openId)) {
                unbound++
                plugin.log_info("退群解绑: ${info.qqUsername.ifBlank { openId }} 已不在任何群，绑定已解除")
                if (plugin.isMemberCheckNotifyGroup()) {
                    QClient.sendTextToGroups(
                        "**退群自动解绑**\n> 用户：${info.qqUsername.ifBlank { openId }}\n" +
                            "> 已不在任何已配置群，绑定已解除",
                        "退群解绑通知"
                    )
                }
            }
        }
        if (unbound > 0) plugin.log_info("本次退群校验共解绑 $unbound 个用户")
        return unbound
    }
}
