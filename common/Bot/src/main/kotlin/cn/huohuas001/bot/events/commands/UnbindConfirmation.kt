package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.InteractionOutcome
import cn.huohuas001.bot.NicknameManager
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * `/解除绑定` 的二次确认。
 *
 * 解除绑定会连带把玩家移出白名单，属于不可逆操作，所以先发一个确认键盘：
 * 30 秒内点「确定」才真正解绑，点「取消」或超时都不解绑。无论哪种结果都会
 * 撤回那条确认消息，避免群里留下过期按钮。
 */
object UnbindConfirmation {

    /** 按钮动作前缀，与 Agent 审批回调区分开。 */
    private const val ACTION_PREFIX = "huhobot:unbind:"

    /** 确认有效期；与键盘消息的自动撤回时间一致。 */
    private const val TIMEOUT_SECONDS = 30L

    private data class PendingUnbind(
        val groupOpenId: String,
        val userOpenId: String,
        val playerName: String,
        val messageId: String?
    )

    private val pending = ConcurrentHashMap<String, PendingUnbind>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Penguin-Unbind-Confirm").apply { isDaemon = true }
    }

    private fun logInfo(message: String) {
        try {
            BotShared.getPlugin().log_info("解绑确认: $message")
        } catch (_: Exception) {
        }
    }

    private fun logDebug(message: String) {
        try {
            BotShared.getPlugin().log_debug("解绑确认: $message")
        } catch (_: Exception) {
        }
    }

    /** 发起确认；同一用户重复发起时覆盖旧的待确认项。 */
    fun request(plugin: HuHoBot, groupOpenId: String, userOpenId: String, playerName: String) {
        val confirmId = UUID.randomUUID().toString()
        val keyboard = buildKeyboard(confirmId)

        val messageId = QClient.sendMarkdownToGroup(
            groupOpenId,
            "确认解除绑定？\n绑定角色：${QClient.escapeMarkdown(playerName)}\n解除后会同步移出白名单，30 秒内未选择视为取消。",
            keyboard
        )

        pending.entries.removeIf { it.value.groupOpenId == groupOpenId && it.value.userOpenId == userOpenId }
        pending[confirmId] = PendingUnbind(groupOpenId, userOpenId, playerName, messageId)
        logInfo("已发送确认键盘 confirmId=$confirmId, 角色=$playerName")

        scheduler.schedule({
            val expired = pending.remove(confirmId) ?: return@schedule
            QClient.recallMessage(expired.groupOpenId, expired.messageId.orEmpty())
            QClient.sendTextToGroup(expired.groupOpenId, "选择超时，已取消解绑")
        }, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * 处理按钮点击。
     *
     * 回应时机由本方法决定：[respond] 在判断出结果后立刻调用，早于撤回消息等耗时操作。
     * 被他人点击时回非零，避免点击者客户端弹出误导性的「操作成功」。
     *
     * @return 是否接管了这次互动（不属于本流程时返回 null）
     */
    fun onInteraction(
        groupOpenId: String,
        userOpenId: String,
        buttonData: String,
        respond: (Int) -> Unit
    ): InteractionOutcome? {
        if (!buttonData.startsWith(ACTION_PREFIX)) return null

        // buttonData 形如 huhobot:unbind:<confirmId>:yes|:no，confirmId 本身不含冒号
        val payload = buttonData.removePrefix(ACTION_PREFIX)
        val confirmed = payload.endsWith(":yes")
        val confirmId = payload.removeSuffix(":yes").removeSuffix(":no")
        if (confirmId.isBlank()) {
            respond(InteractionOutcome.SILENT.code)
            return InteractionOutcome.SILENT
        }

        // 先取不删：身份不符时不能把待确认项消费掉，否则任何群成员都能点掉别人的确认卡片
        val request = pending[confirmId]
        if (request == null) {
            // 已处理过或已超时撤回，属正常情况
            logDebug("解绑确认 $confirmId 不存在或已过期")
            respond(InteractionOutcome.SILENT.code)
            return InteractionOutcome.SILENT
        }

        // 只有发起者本人能决定。SDK 偶尔不带 memberOpenid，这种拿不到点击者身份的情况一律拒绝：
        // 解绑会连带移出白名单、强制绑定下还会踢掉在线会话，绝不能因为身份缺失就放行。
        val sameGroup = request.groupOpenId == groupOpenId
        val sameUser = userOpenId.isNotBlank() && request.userOpenId == userOpenId
        if (!sameGroup || !sameUser) {
            logInfo("解绑确认被他人点击：发起者=${request.userOpenId}，点击者=$userOpenId")
            respond(InteractionOutcome.SILENT.code)
            // 不撤回卡片、不消费待确认项，发起者本人仍可在有效期内点确定。
            // 消息开头 @ 点击者本人：markdown 里必须写成 <@openid> 才会触发 QQ 的 @ 通知；
            // 拿不到点击者 openid（SDK 偶尔不带）或不在同一个群时无从 @，退回纯文本提示。
            val mention = if (sameGroup && userOpenId.isNotBlank()) "<@$userOpenId> " else ""
            QClient.sendTextToGroup(
                request.groupOpenId,
                "${mention}这不是你的绑定信息，请让发起者本人操作"
            )
            return InteractionOutcome.SILENT
        }

        // 校验通过后再原子消费，避免同一张卡片被连点两次
        if (!pending.remove(confirmId, request)) {
            respond(InteractionOutcome.SILENT.code)
            return InteractionOutcome.SILENT
        }

        respond(InteractionOutcome.HANDLED.code)
        QClient.recallMessage(request.groupOpenId, request.messageId.orEmpty())

        if (!confirmed) {
            QClient.sendTextToGroup(request.groupOpenId, "已取消解绑")
            return InteractionOutcome.HANDLED
        }

        // 二次校验：期间可能已经自行解绑或换绑
        val current = CommandRepositories.bindings.getBinding(request.userOpenId)
        if (current == null) {
            QClient.sendTextToGroup(request.groupOpenId, "你已经没有任何绑定，无需解绑")
            return InteractionOutcome.HANDLED
        }
        if (!current.playerName.equals(request.playerName, ignoreCase = true)) {
            QClient.sendTextToGroup(
                request.groupOpenId,
                "绑定关系已变更，请重新执行 /解除绑定"
            )
            return InteractionOutcome.HANDLED
        }

        CommandRepositories.bindings.removeBinding(request.userOpenId)
        QClient.sendTextToGroup(
            request.groupOpenId,
            "已解除角色绑定：${QClient.escapeMarkdown(current.playerName)}"
        )

        val plugin = try {
            BotShared.getPlugin()
        } catch (_: Exception) {
            null
        } ?: return InteractionOutcome.HANDLED

        // 强制绑定下必须立刻断开在线会话，否则玩家能解绑后继续留在服务器里
        if (plugin.isForceBindEnabled()) {
            plugin.kickUnboundPlayer(current.playerName)
            return InteractionOutcome.HANDLED
        }

        // 白名单同步：与用户自行解绑保持一致
        val whitelist = plugin.getWhiteList()
        if (whitelist.delCommand.isNotBlank()) {
            val command = whitelist.delCommand.replace("{name}", current.playerName)
            plugin.sendCommand(command).whenComplete { _, error ->
                if (error != null) plugin.log_error("解绑后移除白名单失败: ${error.message}")
            }
        }
        return InteractionOutcome.HANDLED
    }

    /**
     * 确认键盘。
     *
     * 按钮权限用 2（所有人可操作）：解绑确认是面向普通用户的自助操作，
     * 用 1（仅管理者可操作）会让非管理员根本点不动自己的解绑按钮。
     * 身份边界不依赖客户端按钮权限，而是由 [onInteraction] 里对发起者的严格校验保证：
     * 非发起者点击只会收到"这不是你的绑定信息"（并 @ 点击者），拿不到身份的点击一律拒绝。
     */
    private fun buildKeyboard(confirmId: String): Keyboard {
        val builder = Keyboard.KeyboardBuilder.create()
        val row = builder.addRow()

        row.addButton()
            .setLabel("确定")
            .setVisitedLabel("已确定")
            .setStyle(1)
            .setActionType(1)
            .setActionData("$ACTION_PREFIX$confirmId:yes")
            .setPermissionType(2)
            .setUnSupportTips("请升级QQ客户端后再操作")
            .build()

        row.addButton()
            .setLabel("取消")
            .setVisitedLabel("已取消")
            .setStyle(0)
            .setActionType(1)
            .setActionData("$ACTION_PREFIX$confirmId:no")
            .setPermissionType(2)
            .setUnSupportTips("请升级QQ客户端后再操作")
            .build()

        // 必须把这一行提交回 builder，否则 content.rows 为空数组，QQ 不会渲染按钮
        row.build()
        return builder.build()
    }
}

/**
 * 从命令参数里解析目标标识候选，按优先级从高到低排列。
 *
 * 支持 `@某人`、`<@openid>`（群消息里的渲染态）、裸 openid、QQ 昵称与 MC 玩家名。
 * QQ 昵称允许包含空格，所以先整体匹配；匹配不上再退回首个空白分隔的词，
 * 以容忍 `@某人 顺便看下` 这类带说明的写法。
 */
fun resolveTargetCandidates(params: String): List<String> {
    val raw = params.trim()
    if (raw.isEmpty()) return emptyList()

    // <@openid> 或 @openid：直接就是标识，无需再猜
    Regex("<@([0-9A-Fa-f]{20,})>").find(raw)?.let { return listOf(it.groupValues[1]) }
    Regex("@([0-9A-Fa-f]{20,})").find(raw)?.let { return listOf(it.groupValues[1]) }

    val full = raw.removePrefix("@").trim()
    if (full.isEmpty()) return emptyList()

    val candidates = mutableListOf(full)
    val firstWord = full.split(Regex("\\s+")).first().orEmpty()
    if (firstWord.isNotEmpty() && firstWord != full) candidates += firstWord

    // @昵称 → openid 放在最后兜底，避免昵称恰好等于某个 openid 时误判
    return candidates + listOfNotNull(NicknameManager.getOpenId(full)).distinct()
}
