package cn.huohuas001.huhobotPenguin.spigot.manager

import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import com.alibaba.fastjson2.JSON
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
 * QQ Bot 扫码登录管理器。
 * 首次启动时若 appid/secret 为空，自动在控制台打印二维码，扫码后自动写入 config.yml。
 */
object QrLoginManager {

    private const val CREATE_URL = "https://q.qq.com/lite/create_bind_task"
    private const val POLL_URL = "https://q.qq.com/lite/poll_bind_result"
    private const val CONNECT_URL = "https://q.qq.com/qqbot/openclaw/connect.html"
    private const val POLL_INTERVAL_MS = 2000L
    private const val HTTP_TIMEOUT_MS = 10000L

    private val secureRandom = SecureRandom()

    data class QrCredentials(
        val appId: String,
        val appSecret: String,
        val userOpenid: String?
    )

    /**
     * 执行扫码登录流程，阻塞直到成功或失败。
     * @return 扫码成功返回 QrCredentials，失败返回 null
     */
    fun doQrLogin(logger: HuHoBotSpigot): QrCredentials? {
        logger.log_info("========== QQ Bot 扫码登录 ==========")
        logger.log_info("首次启动未检测到 bot.app-id / bot.secret")
        logger.log_info("请使用手机 QQ 扫描以下二维码完成机器人绑定")
        logger.log_info("")

        while (true) {
            // 1. 生成 key 并创建绑定任务
            val key = generateBindKey()
            val taskId = try {
                createBindTask(key)
            } catch (e: Exception) {
                logger.log_error("创建绑定任务失败: ${e.message}")
                return null
            }

            // 2. 生成二维码 URL 并在终端打印二维码图案 + 链接
            val qrUrl = buildConnectUrl(taskId)
            printQrToConsole(qrUrl, logger)
            logger.log_info("扫码链接: $qrUrl")
            logger.log_info("（扫码后自动继续，过期会自动刷新）")
            logger.log_info("")

            // 3. 轮询扫码结果
            val result = pollUntilResult(taskId, key, logger)
            if (result != null) {
                logger.log_info("")
                logger.log_info("========== 扫码成功 ==========")
                logger.log_info("AppID: ${result.appId}")
                logger.log_info("Secret: ${result.appSecret.take(6)}****")
                return result
            }
            // result == null 表示过期，继续下一轮循环刷新二维码
            logger.log_warning("二维码已过期，正在刷新...")
            logger.log_info("")
        }
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

    // ── 内部实现 ──────────────────────────────────────────

    private fun printQrToConsole(url: String, logger: HuHoBotSpigot) {
        try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 4,
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            )
            val matrix = com.google.zxing.MultiFormatWriter()
                .encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
            for (y in 0 until matrix.height) {
                val line = StringBuilder()
                for (x in 0 until matrix.width) {
                    line.append(if (matrix.get(x, y)) "██" else "  ")
                }
                println(line)
            }
        } catch (e: Exception) {
            logger.log_warning("无法渲染二维码，请扫描以下链接:")
            logger.log_info(url)
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

    private fun pollUntilResult(taskId: String, key: String, logger: HuHoBotSpigot): QrCredentials? {
        while (true) {
            Thread.sleep(POLL_INTERVAL_MS)
            try {
                val body = JSON.toJSONString(mapOf("task_id" to taskId))
                val response = httpPost(POLL_URL, body)
                val json = JSON.parseObject(response)
                val retcode = json.getIntValue("retcode")
                if (retcode != 0) {
                    logger.log_warning("轮询失败: ${json.getString("msg")}，继续重试...")
                    continue
                }
                val data = json.getJSONObject("data") ?: continue
                val status = data.getIntValue("status")
                when (status) {
                    2 -> {
                        // COMPLETED
                        val appId = data.get("bot_appid")?.toString() ?: ""
                        val encryptedSecret = data.getString("bot_encrypt_secret") ?: ""
                        val userOpenid = data.getString("user_openid")
                        val appSecret = decryptSecret(encryptedSecret, key)
                        return QrCredentials(appId, appSecret, userOpenid)
                    }
                    3 -> {
                        // EXPIRED
                        return null
                    }
                    // 0=NONE, 1=PENDING → 继续轮询
                }
            } catch (e: Exception) {
                logger.log_warning("轮询异常: ${e.message}，继续重试...")
            }
        }
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
