package cn.huohuas001.bot.onlinelist

/**
 * 本地在线列表图片渲染的平台桥接。
 *
 * 只有能够在本地读取玩家状态并渲染图片的平台（目前是 Spigot/Paper 适配器）
 * 会在启动时注册 [handler]；未注册的平台（Nukkit、Allay、代理端）
 * 调用 [handle] 一律返回 false，命令自动回退到 MOTD 外部 API 图片。
 */
object OnlineListBridge {
    @Volatile
    var handler: OnlineListHandler? = null

    /**
     * 请求平台渲染一页在线列表图片并回复。
     *
     * @return true 表示请求已被平台受理（含冷却/页码拒绝等情况，结果通过 [reply] 回调）；
     *         false 表示平台不支持本地渲染，调用方应回退到旧逻辑。
     */
    fun handle(groupOpenId: String, requestedPage: Int, reply: OnlineListImageReply): Boolean {
        val active = handler ?: return false
        return try {
            active.handle(groupOpenId, requestedPage, reply)
        } catch (_: Throwable) {
            false
        }
    }
}

interface OnlineListHandler {
    fun handle(groupOpenId: String, requestedPage: Int, reply: OnlineListImageReply): Boolean
}

/** 平台生成在线列表图片后的回调。实现方负责发送消息。 */
interface OnlineListImageReply {
    /** 成功渲染 PNG 图片，bytes 为 PNG 数据。 */
    fun image(png: ByteArray)

    /** 请求被冷却或并发限制拒绝。 */
    fun busy()

    /** 页码超出范围。 */
    fun outOfRange(page: Int, totalPages: Int)

    /** 渲染或发送失败。 */
    fun failed(message: String)
}
