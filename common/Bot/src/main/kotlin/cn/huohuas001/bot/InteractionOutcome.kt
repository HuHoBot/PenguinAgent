package cn.huohuas001.bot

/**
 * QQ 消息按钮互动的回应结果。
 *
 * 官方要求：收到 `INTERACTION_CREATE` 后必须回应，否则客户端会一直 loading 到超时。
 * 但回 `0`（成功）时客户端会弹出「操作成功」——被拒绝的点击如果也回 `0`，
 * 用户就会看到「操作成功」同时群里说没有权限，非常莫名其妙。
 * 所以只有真正处理成功才回 `0`，其余情况回非零让客户端静默。
 */
enum class InteractionOutcome(
    /** 回给 QQ 的 code */
    val code: Int
) {
    /** 已处理完成，客户端提示「操作成功」是恰当的 */
    HANDLED(0),

    /** 被拒绝（无权限、群不匹配、已过期等）或不属于本插件，客户端不要弹提示 */
    SILENT(1);

    companion object {
        /** 便于 handler 内部按语义取 code。 */
        fun codeOf(handled: Boolean): Int = if (handled) HANDLED.code else SILENT.code
    }
}