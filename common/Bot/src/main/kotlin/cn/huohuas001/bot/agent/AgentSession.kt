package cn.huohuas001.bot.agent

import com.alibaba.fastjson.JSONObject

/**
 * 一次 /agent 任务的会话状态。
 *
 * 会话负责保存与 AI 的对话历史、审批挂起状态等，一次 /agent 请求对应一个会话。
 */
class AgentSession(
    val sessionId: String,
    val groupOpenId: String,
    val requestUserId: String,
    val task: String
) {
    /** 与 AI 的多轮对话历史（role/content/tool_calls/tool_call_id）。 */
    val messages: MutableList<JSONObject> = mutableListOf()

    /** 会话是否已结束（AI 已给出最终答复）。 */
    var finished: Boolean = false

    /** 紧急停止标记：设置后立即终止所有输出和 AI 处理。 */
    @Volatile var stopped: Boolean = false

    /** 正在等待管理员审批的执行请求；null 表示没有待审批项。 */
    var awaitingApproval: PendingApproval? = null

    /** 创建时间戳，用于超时清理。 */
    val createdAt: Long = System.currentTimeMillis()

    /** 群成员映射：member_openid → 用户名。给 AI 喂 openid，但 QQ 显示用户名。 */
    val memberNames: MutableMap<String, String> = mutableMapOf()

    /** 根据 member_openid 返回用户名；未知时原样返回 openid。 */
    fun displayName(openId: String): String = memberNames[openId] ?: openId

    /** 一次待管理员审批的执行请求。支持服务器命令和 QQ 群管理工具。 */
    class PendingApproval(
        val approvalId: String,
        val toolCallId: String,
        val command: String,
        val toolName: String = "",
        val query: JSONObject? = null
    ) {
        /** 是否为 QQ 群管理工具调用（而非服务器命令）。 */
        val isTool: Boolean get() = toolName.isNotEmpty()
    }
}
