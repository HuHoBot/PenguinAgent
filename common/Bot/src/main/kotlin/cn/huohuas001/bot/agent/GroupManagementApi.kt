package cn.huohuas001.bot.agent

import cn.huohuas001.bot.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * QQ 群管理 API 封装。
 * 所有方法通过 HTTP 直接调用 QQ 开放平台 v2 接口。
 */
object GroupManagementApi {
    private const val API_BASE = "https://api.sgroup.qq.com"

    private fun getAuthHeader(starter: Starter): String {
        val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
        val token = start0.accessToken ?: throw IllegalStateException("无法获取 access_token")
        return "QQBot $token"
    }

    private fun getAppId(starter: Starter): String {
        val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
        return start0.headers["X-Union-Appid"] ?: ""
    }

    private fun request(starter: Starter, method: String, path: String, body: JSONObject? = null): JSONObject {
        val url = URL("$API_BASE$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        // 统一使用 SDK 的 getHeaders()，确保 Authorization 和 X-Union-Appid 都正确
        val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
        val headers = start0.getHeaders()
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = body != null
        body?.let {
            val bodyStr = it.toJSONString()
            println("[GroupApi] $method $path body=$bodyStr")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w -> w.write(bodyStr) }
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: "{}"
        conn.disconnect()
        println("[GroupApi] <- ${conn.responseCode} $text")
        val resp = JSON.parseObject(text)
        if (resp == null || resp.containsKey("code")) {
            val msg = resp?.getString("message") ?: "未知错误"
            throw RuntimeException("API 错误 [${conn.responseCode}]: $msg")
        }
        return resp
    }

    // ── 群信息 ──

    fun getGroupInfo(starter: Starter, groupOpenId: String): JSONObject {
        return request(starter, "GET", "/v2/groups/$groupOpenId/info")
    }

    fun getBotState(starter: Starter, groupOpenId: String): JSONObject {
        return request(starter, "GET", "/v2/groups/$groupOpenId/bot_state")
    }

    // ── 入群申请 ──

    fun getJoinRequests(starter: Starter, groupOpenId: String, cursor: String = "", limit: Int = 20): JSONObject {
        val params = mutableListOf<String>()
        if (cursor.isNotEmpty()) params.add("cursor=$cursor")
        if (limit != 20) params.add("limit=$limit")
        val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return request(starter, "GET", "/v2/groups/$groupOpenId/join_request_list$query")
    }

    fun approveJoinRequest(
        starter: Starter,
        groupOpenId: String,
        memberOpenId: String,
        joinRequestId: String,
        approve: Boolean,
        rejectReason: String = "",
        blacklist: Boolean = false
    ): JSONObject {
        val body = JSONObject().apply {
            put("op", if (approve) "approve" else "decline")
            put("join_request_id", joinRequestId)
            if (!approve && rejectReason.isNotEmpty()) put("reject_reason", rejectReason)
            if (!approve) put("add_to_member_blacklist", blacklist)
        }
        return request(starter, "POST", "/v2/groups/$groupOpenId/approval_join_request/$memberOpenId", body)
    }

    // ── 禁言 ──

    fun getMuteStatus(starter: Starter, groupOpenId: String): JSONObject {
        return request(starter, "GET", "/v2/groups/$groupOpenId/restrict_chat_setting")
    }

    fun setMemberMute(
        starter: Starter,
        groupOpenId: String,
        memberOpenId: String,
        op: String,
        minutes: Int = 1
    ): JSONObject {
        val body = JSONObject().apply {
            put("members", com.alibaba.fastjson.JSONArray().apply {
                add(JSONObject().apply {
                    put("op", op)
                    put("member_openid", memberOpenId)
                    if (op == "add") {
                        // 服务端计算到期时间，避免 AI 生成错误的过去时间
                        val cal = java.util.Calendar.getInstance()
                        cal.add(java.util.Calendar.MINUTE, minutes.coerceIn(1, 43200))
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
                        put("mute_expire_at", sdf.format(cal.time))
                    }
                })
            })
        }
        return request(starter, "POST", "/v2/groups/$groupOpenId/restrict_chat_setting", body)
    }

    // ── 自动审批策略 ──

    fun listAutoApprovePolicies(starter: Starter, cursor: String = "", limit: Int = 20): JSONObject {
        val params = mutableListOf<String>()
        if (cursor.isNotEmpty()) params.add("cursor=$cursor")
        if (limit != 20) params.add("limit=$limit")
        val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return request(starter, "GET", "/v2/groups/join_approval_strategy$query")
    }

    fun createAutoApprovePolicy(
        starter: Starter,
        groupOpenIds: List<String> = emptyList(),
        groupIds: List<String> = emptyList(),
        enable: Boolean = true,
        remark: String = ""
    ): JSONObject {
        val body = JSONObject().apply {
            if (groupOpenIds.isNotEmpty()) {
                put("group_openids", com.alibaba.fastjson.JSONArray().apply {
                    groupOpenIds.forEach { add(it) }
                })
            }
            if (groupIds.isNotEmpty()) {
                put("group_ids", com.alibaba.fastjson.JSONArray().apply {
                    groupIds.forEach { add(it) }
                })
            }
            put("is_enable", if (enable) "on" else "off")
            if (remark.isNotEmpty()) put("remark", remark)
        }
        return request(starter, "POST", "/v2/groups/join_approval_strategy", body)
    }

    fun updateAutoApprovePolicy(
        starter: Starter,
        strategyId: String,
        enable: Boolean? = null,
        remark: String? = null,
        groupAction: JSONObject? = null
    ): JSONObject {
        val body = JSONObject().apply {
            if (enable != null) put("is_enable", if (enable) "on" else "off")
            if (remark != null) put("remark", remark)
            if (groupAction != null) put("group_action", groupAction)
        }
        return request(starter, "PATCH", "/v2/groups/join_approval_strategy/$strategyId", body)
    }

    fun deleteAutoApprovePolicy(starter: Starter, strategyId: String): JSONObject {
        return request(starter, "DELETE", "/v2/groups/join_approval_strategy/$strategyId")
    }

    fun executeAutoApprovePolicy(starter: Starter, strategyId: String): JSONObject {
        return request(starter, "POST", "/v2/groups/join_approval_strategy/$strategyId/execute")
    }

    fun updateWhitelistUsers(
        starter: Starter,
        strategyId: String,
        op: String,
        users: List<String>
    ): JSONObject {
        val body = JSONObject().apply {
            put("op", op)
            put("whitelist_users", com.alibaba.fastjson.JSONArray().apply {
                users.forEach { add(it) }
            })
        }
        return request(starter, "POST", "/v2/groups/join_approval_strategy/$strategyId/whitelist_users", body)
    }
}
