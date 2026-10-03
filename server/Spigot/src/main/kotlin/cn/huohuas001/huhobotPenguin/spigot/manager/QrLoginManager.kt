package cn.huohuas001.huhobotPenguin.spigot.manager

import cn.huohuas001.bot.web.QrAuthState
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import com.alibaba.fastjson2.JSON
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * QQ Bot 扫码登录管理器（会话制）。
 *
 * 一次 [start] 创建一个后台授权会话：轮询扫码结果、过期自动刷新、
 * 成功后写入 config.yml 并启动 QQ 客户端。控制台启动流程与 WebUI
 * 扫码入口共用同一个会话——两边看到的是同一张二维码、同一个任务，
 * 任一端扫码成功或取消，另一端状态同步。
 *
 * 旧实现（[doQrLogin]）会在 Bukkit 主线程上 `while(true)` 阻塞，
 * 导致未配置凭据时服务器卡在启动阶段、WebUI 根本启动不了；现已废弃。
 */
object QrLoginManager {

    private const val CREATE_URL = "https://q.qq.com/lite/create_bind_task"
    private const val POLL_URL = "https://q.qq.com/lite/poll_bind_result"
    private const val CONNECT_URL = "https://q.qq.com/qqbot/openclaw/connect.html"
    private const val POLL_INTERVAL_MS = 2000L
    private const val HTTP_TIMEOUT_MS = 10000L

    /** 二维码自动刷新上限；超出后停在 failed，等 WebUI「重试」或重启。 */
    private const val MAX_REFRESH_COUNT = 6

    private val secureRandom = SecureRandom()

    data class QrCredentials(
        val appId: String,
        val appSecret: String,
        val userOpenid: String?
    )

    enum class State { IDLE, WAITING, EXPIRED, SUCCESS, FAILED, CANCELLED }

    private class AuthSession(val plugin: HuHoBotSpigot) {
        @Volatile var state: State = State.WAITING
        @Volatile var qrUrl: String = ""
        @Volatile var qrImageBase64: String = ""
        @Volatile var message: String = "正在创建授权任务..."
        @Volatile var refreshCount: Int = 0
        @Volatile var appId: String = ""
        @Volatile var cancelled: Boolean = false
    }

    @Volatile
    private var session: AuthSession? = null

    /** 当前（或最近一次）授权任务的状态快照，供 WebUI 透传给前端。 */
    fun state(): QrAuthState {
        val active = session
            ?: return QrAuthState(
                supported = true,
                state = State.IDLE.name.lowercase(),
                qrUrl = "",
                qrImageBase64 = "",
                message = "尚未开始扫码授权",
                refreshCount = 0,
                appId = ""
            )
        return QrAuthState(
            supported = true,
            state = active.state.name.lowercase(),
            qrUrl = active.qrUrl,
            qrImageBase64 = active.qrImageBase64,
            message = active.message,
            refreshCount = active.refreshCount,
            appId = active.appId
        )
    }

    /**
     * 开启一次扫码授权任务；已有进行中的任务时直接复用（控制台与 WebUI 共用）。
     *
     * 在后台守护线程运行，不阻塞调用方（因此也不再阻塞 Bukkit 主线程）。
     */
    @Synchronized
    fun start(plugin: HuHoBotSpigot): Boolean {
        val current = session
        val running = current != null &&
            !current.cancelled &&
            (current.state == State.WAITING || current.state == State.EXPIRED)
        if (running) return true

        val created = AuthSession(plugin)
        session = created
        Thread({ runSession(created) }, "HuHoBot-QrAuth").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** 取消进行中的授权任务；没有进行中的任务时返回 false。 */
    @Synchronized
    fun cancel(): Boolean {
        val active = session ?: return false
        if (active.state != State.WAITING && active.state != State.EXPIRED) return false
        active.cancelled = true
        active.state = State.CANCELLED
        active.message = "已取消扫码授权"
        active.plugin.log_info("已取消扫码授权")
        return true
    }

    /**
     * 将扫码获得的凭据写入 config.yml。
     */
    fun writeCredentials(plugin: HuHoBotSpigot, credentials: QrCredentials) {
        plugin.config.set("bot.app-id", credentials.appId)
        plugin.config.set("bot.secret", credentials.appSecret)
        plugin.saveConfig()
        plugin.reloadConfig()
        plugin.log_info("已将 AppID 和 Secret 写入 config.yml")
    }

    // ── 会话主体 ──────────────────────────────────────────

    private fun runSession(active: AuthSession) {
        val plugin = active.plugin
        plugin.log_info("========== QQ Bot 扫码登录 ==========")
        plugin.log_info("请使用手机 QQ 扫描以下二维码完成机器人绑定")
        plugin.log_info("也可在 WebUI「QQ 机器人」页面扫码，无需调整终端窗口")
        plugin.log_info("")

        try {
            while (!active.cancelled) {
                // 1. 生成 key 并创建绑定任务
                val key = generateBindKey()
                val taskId = createBindTask(key)

                // 2. 生成二维码：同时给控制台打印和 WebUI 展示
                val qrUrl = buildConnectUrl(taskId)
                active.qrUrl = qrUrl
                active.qrImageBase64 = renderPngBase64(qrUrl)
                active.state = State.WAITING
                active.message = "等待扫码授权（二维码过期会自动刷新）"
                printQrToConsole(qrUrl)
                plugin.log_info("扫码链接: $qrUrl")
                plugin.log_info("（扫码后自动继续，过期会自动刷新）")
                plugin.log_info("")

                // 3. 轮询扫码结果
                val credentials = pollUntilResult(active, taskId, key)
                if (credentials != null) {
                    writeCredentials(plugin, credentials)
                    active.appId = credentials.appId
                    active.state = State.SUCCESS
                    active.message = "授权成功，正在连接机器人..."
                    plugin.log_info("")
                    plugin.log_info("========== 扫码成功 ==========")
                    plugin.log_info("AppID: ${credentials.appId}")
                    plugin.log_info("Secret: ${credentials.appSecret.take(6)}****")
                    // 凭据已写入，走正常启动路径（异步拉起客户端）
                    plugin.launchQqClient()
                    return
                }
                if (active.cancelled) break

                // 过期：自动刷新二维码
                active.refreshCount = active.refreshCount + 1
                if (active.refreshCount > MAX_REFRESH_COUNT) {
                    active.state = State.FAILED
                    active.message = "二维码多次过期，请点击「重新获取二维码」重试"
                    plugin.log_warning(active.message)
                    return
                }
                active.state = State.EXPIRED
                active.message = "二维码已过期，正在刷新..."
                plugin.log_warning("二维码已过期，正在刷新...")
                plugin.log_info("")
            }
            active.state = State.CANCELLED
            active.message = "已取消扫码授权"
        } catch (error: Exception) {
            active.state = State.FAILED
            active.message = "扫码授权失败: ${error.message}"
            plugin.log_error(active.message)
        }
    }

    // ── 内部实现 ──────────────────────────────────────────

    private fun printQrToConsole(url: String) {
        try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 4,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
            val matrix = MultiFormatWriter()
                .encode(url, BarcodeFormat.QR_CODE, 0, 0, hints)
            for (y in 0 until matrix.height) {
                val line = StringBuilder()
                for (x in 0 until matrix.width) {
                    line.append(if (matrix.get(x, y)) "██" else "  ")
                }
                println(line)
            }
        } catch (e: Exception) {
            println("无法渲染二维码，请扫描以下链接:")
            println(url)
        }
    }

    /** 渲染二维码 PNG 的 Base64，供 WebUI <img> 直接展示；失败返回空串。 */
    private fun renderPngBase64(url: String): String {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
            val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320, hints)
            val out = ByteArrayOutputStream()
            MatrixToImageWriter.writeToStream(matrix, "PNG", out)
            Base64.getEncoder().encodeToString(out.toByteArray())
        } catch (_: Exception) {
            ""
        }
    }

    private fun generateBindKey(): String {
        val bytes = ByteArray(32).also(secureRandom::nextBytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun createBindTask(key: String): String {
        val body = JSON.toJSONString(mapOf("key" to key))
        val response = httpPost(CREATE_URL, body)
        val json = JSON.parseObject(response)
        val retcode = json.getIntValue("retcode")
        if (retcode != 0) {
            throw RuntimeException("create_bind_task failed: ${json.getString("msg")}")
        }
        val taskId = json.getJSONObject("data")?.getString("task_id")
        if (taskId.isNullOrBlank()) {
            throw RuntimeException("create_bind_task: missing task_id")
        }
        return taskId
    }

    private fun buildConnectUrl(taskId: String): String {
        val encodedTaskId = URLEncoder.encode(taskId, "UTF-8")
            .replace("+", "%20")
        val encodedSource = URLEncoder.encode("openclaw", "UTF-8")
            .replace("+", "%20")
        return "$CONNECT_URL?task_id=$encodedTaskId&source=$encodedSource&_wv=2"
    }

    /**
     * 轮询单次授权任务。
     *
     * @return 扫码成功返回凭据；二维码过期或会话被取消返回 null（用 [AuthSession.cancelled] 区分）
     */
    private fun pollUntilResult(active: AuthSession, taskId: String, key: String): QrCredentials? {
        while (!active.cancelled) {
            Thread.sleep(POLL_INTERVAL_MS)
            if (active.cancelled) return null
            try {
                val body = JSON.toJSONString(mapOf("task_id" to taskId))
                val response = httpPost(POLL_URL, body)
                val json = JSON.parseObject(response)
                val retcode = json.getIntValue("retcode")
                if (retcode != 0) {
                    active.plugin.log_warning("轮询失败: ${json.getString("msg")}，继续重试...")
                    continue
                }
                val data = json.getJSONObject("data") ?: continue
                when (data.getIntValue("status")) {
                    2 -> {
                        // COMPLETED
                        val appId = data.get("bot_appid")?.toString() ?: ""
                        val encryptedSecret = data.getString("bot_encrypt_secret") ?: ""
                        val userOpenid = data.getString("user_openid")
                        val appSecret = decryptSecret(encryptedSecret, key)
                        return QrCredentials(appId, appSecret, userOpenid)
                    }
                    3 -> return null // EXPIRED
                    // 0=NONE, 1=PENDING → 继续轮询
                }
            } catch (e: Exception) {
                active.plugin.log_warning("轮询异常: ${e.message}，继续重试...")
            }
        }
        return null
    }

    /**
     * AES-256-GCM 解密 AppSecret。
     * 密文布局：IV(12) || ciphertext || AuthTag(16)
     */
    private fun decryptSecret(encryptedBase64: String, keyBase64: String): String {
        val key = Base64.getDecoder().decode(keyBase64)
        val blob = Base64.getDecoder().decode(encryptedBase64)

        require(key.size == 32) { "key must be 32 bytes (AES-256), got ${key.size}" }
        require(blob.size > 12 + 16) { "ciphertext too short: ${blob.size}" }

        val iv = blob.copyOfRange(0, 12)
        val tag = blob.copyOfRange(blob.size - 16, blob.size)
        val cipherText = blob.copyOfRange(12, blob.size - 16)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        val plain = cipher.doFinal(cipherText + tag)
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun httpPost(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = HTTP_TIMEOUT_MS.toInt()
        conn.readTimeout = HTTP_TIMEOUT_MS.toInt()
        conn.doOutput = true

        conn.outputStream.use { os ->
            os.write(body.toByteArray(StandardCharsets.UTF_8))
        }

        val statusCode = conn.responseCode
        val responseBody = if (statusCode in 200..299) {
            val bytes = conn.inputStream.use { it.readBytes() }
            String(bytes, StandardCharsets.UTF_8)
        } else {
            val error = conn.errorStream?.use { String(it.readBytes(), StandardCharsets.UTF_8) }
            throw RuntimeException("HTTP $statusCode: $error")
        }

        return responseBody
    }
}
