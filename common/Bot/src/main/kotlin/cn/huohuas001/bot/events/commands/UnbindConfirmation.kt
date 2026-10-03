package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
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

    /** 处理按钮点击；返回 true 表示该回调属于本流程。 */
    fun onInteraction(groupOpenId: String, userOpenId: String, buttonData: String): Boolean {
        if (!buttonData.startsWith(ACTION_PREFIX)) return false

        // buttonData 形如 huhobot:unbind:<confirmId>:yes|:no，confirmId 本身不含冒号
        val payload = buttonData.removePrefix(ACTION_PREFIX)
        val confirmed = payload.endsWith(":yes")
        val confirmId = payload.removeSuffix(":yes").removeSuffix(":no")
        if (confirmId.isBlank()) return true

        val request = pending.remove(confirmId)
        if (request == null) {
            // 已处理过或已超时撤回，属正常情况
            logDebug("解绑确认 $confirmId 不存在或已过期")
            return true
        }

        // 只有发起者本人能决定；SDK 偶尔不带 memberOpenid，此时不校验身份
        val sameGroup = request.groupOpenId == groupOpenId
        val sameUser = userOpenId.isBlank() || request.userOpenId == userOpenId
        if (!sameGroup || !sameUser) {
            logInfo("解绑确认被他人点击：发起者=${request.userOpenId}，点击者=$userOpenId")
            QClient.recallMessage(request.groupOpenId, request.messageId.orEmpty())
            QClient.sendTextToGroup(request.groupOpenId, "这不是你的确认消息，请让发起者本人操作")
            return true
        }

        QClient.recallMessage(request.groupOpenId, request.messageId.orEmpty())

        if (!confirmed) {
            QClient.sendTextToGroup(request.groupOpenId, "已取消解绑")
            return true
        }

        // 二次校验：期间可能已经自行解绑或换绑
        val current = CommandRepositories.bindings.getBinding(request.userOpenId)
        if (current == null) {
            QClient.sendTextToGroup(request.groupOpenId, "你已经没有任何绑定，无需解绑")
            return true
        }
        if (!current.playerName.equals(request.playerName, ignoreCase = true)) {
            QClient.sendTextToGroup(
                request.groupOpenId,
                "绑定关系已变更，请重新执行 /解除绑定"
            )
            return true
        }

        CommandRepositories.bindings.removeBinding(request.userOpenId)
        QClient.sendTextToGroup(
            request.groupOpenId,
            "已解除角色绑定：${QClient.escapeMarkdown(current.playerName)}"
        )

        // 白名单同步：与用户自行解绑保持一致
        val plugin = try {
            BotShared.getPlugin()
        } catch (_: Exception) {
            null
        }
        val whitelist = plugin?.getWhiteList() ?: return true
        if (whitelist.delCommand.isNotBlank()) {
            val command = whitelist.delCommand.replace("{name}", current.playerName)
            plugin.sendCommand(command).whenComplete { _, error ->
                if (error != null) plugin.log_error("解绑后移除白名单失败: ${error.message}")
            }
        }
        return true
    }

    private fun buildKeyboard(confirmId: String): Keyboard {
        val builder = Keyboard.KeyboardBuilder.create()
        val row = builder.addRow()

        row.addButton()
            .setLabel("确定")
            .setVisitedLabel("已确定")
            .setStyle(1)
            .setActionType(1)
            .setActionData("$ACTION_PREFIX$confirmId:yes")
            .setPermissionType(1)
            .setUnSupportTips("请升级QQ客户端后再操作")
            .build()

        row.addButton()
            .setLabel("取消")
            .setVisitedLabel("已取消")
            .setStyle(0)
            .setActionType(1)
            .setActionData("$ACTION_PREFIX$confirmId:no")
            .setPermissionType(1)
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