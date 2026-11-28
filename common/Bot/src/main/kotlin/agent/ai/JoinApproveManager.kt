package agent.ai

import agent.QClient
import agent.provider.BotShared
import com.alibaba.fastjson.JSONObject

/**
 * 入群自动审批。
 *
 * QQ 官方机器人对群聊没有"成员变动"事件，只能轮询 `getJoinRequests`（白名单能力，
 * 普通 bot 返回 11253 —— 一旦确认不可用就永久停用轮询，与 [AgentManager] 的降级策略一致）。
 *
 * 策略在 config.yml 的 join-approve.strategies 中配置：
 * ```yaml
 * join-approve:
 *   enabled: true
 *   strategies:
 *     - name: 全部通过
 *       mode: allow          # allow=通过；deny=拒绝（blacklist=false）
 *       openids: []          # 空=对所有申请生效；填 openid 则只对这些申请人生效
 *       reject-reason: ""    # deny 时的拒绝理由
 * ```
 * 策略按顺序匹配，第一条命中的生效；都不命中则不处理（留给人工）。
 */
object JoinApproveManager {

    private const val DISABLE_REASON_11253 = "11253"

    @Volatile
    private var platformDisabled = false

    @Volatile
    private var timerStarted = false
    @Volatile
    private var timerHandle: agent.tools.Cancelable? = null

    fun start() {
        val plugin = BotShared.getPluginOrNull() ?: return
        if (!plugin.isJoinApproveEnabled() || timerStarted || platformDisabled) return
        val interval = plugin.getJoinApproveIntervalSeconds().toLong().coerceIn(10, 600) * 1000
        timerStarted = true
        timerHandle = plugin.submitTimer(interval, interval) {
            if (!plugin.isJoinApproveEnabled()) return@submitTimer
            if (platformDisabled) return@submitTimer
            pollOnce()
        }
        plugin.log_info("入群自动审批已启用（每 ${interval / 1000} 秒轮询一次）")
    }

    fun stop() {
        timerHandle?.cancel()
        timerHandle = null
        timerStarted = false
    }

    /** 轮询所有已配置群的待审申请，按策略审批。 */
    fun pollOnce() {
        val plugin = BotShared.getPluginOrNull() ?: return
        val starter = QClient.getStarter() ?: return
        val strategies = plugin.getJoinApproveStrategies()
        if (strategies.isEmpty()) return
        for (groupOpenId in plugin.getGroupOpenIdList()) {
            val resp = try {
                GroupManagementApi.getJoinRequests(starter, groupOpenId)
            } catch (error: Throwable) {
                if ((error.message ?: "").contains(DISABLE_REASON_11253)) {
                    platformDisabled = true
                    plugin.log_info("入群审批接口不可用（QQ 白名单能力），自动审批已停用")
                }
                continue
            }
            val data = resp.getJSONObject("data") ?: resp
            val requests = data.getJSONArray("join_requests")
                ?: data.getJSONArray("requests")
                ?: data.getJSONArray("items")
                ?: continue
            for (i in 0 until requests.size) {
                val request = requests.getJSONObject(i) ?: continue
                handleRequest(starter, groupOpenId, request, strategies, plugin)
            }
        }
    }

    private fun handleRequest(
        starter: io.github.kloping.qqbot.Starter,
        groupOpenId: String,
        request: JSONObject,
        strategies: List<JSONObject>,
        plugin: agent.HuHoBot
    ) {
        val applicant = request.getString("member_openid")
            ?: request.getString("openid")
            ?: return
        val requestId = request.getString("join_request_id")
            ?: request.getString("request_id")
            ?: return
        // 已处理过的跳过（QQ 侧 status：已通过/已拒绝的不再重复审批）
        val status = request.getInteger("status") ?: request.getInteger("request_status")
        if (status != null && status != 0 && status != 1) return

        for (strategy in strategies) {
            val mode = strategy.getString("mode")?.trim()?.lowercase() ?: continue
            if (mode != "allow" && mode != "deny") continue
            val openids = (strategy.getJSONArray("openids")?.mapNotNull { it?.toString() } ?: emptyList())
                .filter { it.isNotBlank() }
            if (openids.isNotEmpty() && applicant !in openids) continue
            val approve = mode == "allow"
            try {
                GroupManagementApi.approveJoinRequest(
                    starter, groupOpenId, applicant, requestId, approve,
                    rejectReason = strategy.getString("reject-reason").orEmpty(),
                    blacklist = false
                )
                plugin.log_info(
                    "入群申请已自动${if (approve) "通过" else "拒绝"}: $applicant（策略 ${strategy.getString("name")}）"
                )
            } catch (error: Throwable) {
                plugin.log_warning("入群自动审批失败($applicant): ${error.message}")
            }
            return // 第一条命中的策略生效
        }
    }

    /**
     * AI 审批：交给工作 Agent 的模型判断是否放行。
     * 返回 (是否通过, 理由)；AI 未配置/输出无法解析时返回 null（该申请留给人工处理）。
     */
    private fun aiReview(
        plugin: agent.HuHoBot,
        request: JSONObject,
        strategy: JSONObject
    ): Pair<Boolean, String>? {
        val baseUrl = plugin.getAgentBaseUrl()?.trim()
        val apiKey = plugin.getAgentApiKey()?.trim()
        val model = plugin.getAgentModel()?.trim()
        if (baseUrl.isNullOrBlank() || apiKey.isNullOrBlank() || model.isNullOrBlank()) {
            plugin.log_warning("AI 入群审批已跳过：工作 Agent 的 API 未配置")
            return null
        }
        val rules = strategy.getString("prompt")?.takeIf(String::isNotBlank)
            ?: "放行正常玩家；拒绝广告引流、营销号、可疑批量小号、明显恶意或与服务器无关的申请。"
        val prompt = """
            你是 QQ 群入群审批助手。请根据申请信息判断是否放行。
            申请信息：${request.toJSONString()}
            审批规则：$rules
            只输出一行 JSON，格式：{"approve": true, "reason": "不超过 30 字的理由"}
            不要输出任何其他内容。
        """.trimIndent()
        return runCatching {
            val client = AgentApiClient(baseUrl, apiKey, model)
            val messages = mutableListOf(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
            val result = client.chat(messages, null)
            val content = result.content ?: return null
            val json = extractJsonObject(content) ?: return null
            val approve = json.getBooleanValue("approve")
            val reason = json.getString("reason")?.take(60) ?: if (approve) "AI 审核通过" else "AI 审核拒绝"
            approve to reason
        }.getOrElse {
            plugin.log_warning("AI 入群审批调用失败: ${it.message}")
            null
        }
    }

    /** 从模型输出里抠出第一个 JSON 对象（模型常带 ```json 包裹）。 */
    private fun extractJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        return runCatching { com.alibaba.fastjson.JSON.parseObject(text.substring(start, end + 1)) }.getOrNull()
    }

}
