package cn.huohuas001.bot.web

/**
 * 扫码授权状态快照。
 *
 * 由各平台的扫码授权实现生成，[WebUiServer] 原样透传给前端；
 * 平台不支持扫码授权时使用 [UNSUPPORTED]。
 *
 * @property supported 当前平台是否支持扫码授权
 * @property state idle / waiting / expired / success / failed / cancelled / unsupported
 * @property qrUrl 二维码指向的授权链接
 * @property qrImageBase64 二维码 PNG 的 Base64（不含 data: 前缀）；尚无二维码时为空
 * @property message 供前端展示的状态描述
 * @property refreshCount 本次授权任务内二维码已自动刷新的次数
 * @property appId 授权成功后写入配置的 AppID；其余情况为空串
 */
data class QrAuthState(
    val supported: Boolean,
    val state: String,
    val qrUrl: String,
    val qrImageBase64: String,
    val message: String,
    val refreshCount: Int,
    val appId: String
) {
    companion object {
        /** 平台未实现扫码授权桥接时的占位状态。 */
        val UNSUPPORTED = QrAuthState(
            supported = false,
            state = "unsupported",
            qrUrl = "",
            qrImageBase64 = "",
            message = "当前平台不支持扫码授权，请手动配置 bot.app-id 与 bot.secret",
            refreshCount = 0,
            appId = ""
        )
    }
}
