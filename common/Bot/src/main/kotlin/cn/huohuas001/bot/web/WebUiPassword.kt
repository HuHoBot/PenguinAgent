package cn.huohuas001.bot.web

import cn.huohuas001.bot.provider.BotShared
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * WebUI 登录密码管理。
 *
 * 密码以 SHA-256 摘要的形式保存在配置目录下的 webui-password 文件中：
 *   <salt>:<hash>
 *
 * 首次启动时会生成一个随机高强度密码并写入日志；
 * 可通过游戏内 /hb password <新密码> 命令修改。
 */
object WebUiPassword {

    private const val FILE_NAME = "webui-password"
    private const val SALT_LENGTH = 16
    private const val GENERATED_LENGTH = 24

    private val random = SecureRandom()

    /** 密码文件位置：与插件配置文件同一目录。 */
    private fun passwordFile(): File {
        val configDir = BotShared.getPlugin().getConfigFile()?.parentFile
        return if (configDir != null) File(configDir, FILE_NAME) else File(FILE_NAME)
    }

    /** 密码是否已配置（文件存在且非空）。 */
    fun isConfigured(): Boolean {
        return try {
            passwordFile().takeIf { it.isFile }?.readText()?.isNotBlank() == true
        } catch (_: Exception) {
            false
        }
    }

    /** 启动时调用：确保密码文件存在，若不存在则生成随机高强度密码并写入日志。
     * 返回明文密码（供日志打印）。
     */
    fun ensureGenerated(): String {
        val file = passwordFile()
        if (file.exists() && file.isFile && file.readText().isNotBlank()) {
            return ""
        }
        val plain = generateStrongPassword()
        file.parentFile?.mkdirs()
        file.writeText(hashPassword(plain))
        return plain
    }

    /** 生成随机高强度密码（大小写字母 + 数字 + 特殊符号）。 */
    private fun generateStrongPassword(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*-_=+"
        val sb = StringBuilder(GENERATED_LENGTH)
        repeat(GENERATED_LENGTH) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    /** 校验明文密码是否与已保存的密码一致。 */
    fun verify(plain: String): Boolean {
        val stored = try {
            passwordFile().takeIf { it.isFile }?.readText()?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (stored.isEmpty()) return false
        val parts = stored.split(':')
        if (parts.size != 2) return false
        val salt = parts[0]
        val expected = parts[1]
        return sha256("$salt:$plain") == expected
    }

    /** 将密码修改为指定值，并返回是否成功。 */
    fun changePassword(newPlain: String): Boolean {
        if (newPlain.isBlank() || newPlain.length < 6) return false
        return try {
            val file = passwordFile()
            file.parentFile?.mkdirs()
            file.writeText(hashPassword(newPlain))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 使用随机盐计算 SHA-256 摘要，格式 <salt>:<hash>。 */
    private fun hashPassword(plain: String): String {
        val saltBytes = ByteArray(SALT_LENGTH)
        random.nextBytes(saltBytes)
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        return "$salt:${sha256("$salt:$plain")}"
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}