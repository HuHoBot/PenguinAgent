package cn.huohuas001.bot.state

import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 存储待验证的绑定请求。
 *
 * QQ 用户发送 `/绑定 <游戏ID>` 后生成 5 位随机验证码，
 * 等待玩家在游戏内执行 `/qqbind <code>` 完成绑定。
 */
object PendingBindingStore {

    /** 验证码 → PendingBinding */
    private val pending = ConcurrentHashMap<String, PendingBinding>()

    private const val CODE_LENGTH = 5
    private const val EXPIRE_MILLIS = 5 * 60 * 1000L // 5 分钟过期

    data class PendingBinding(
        val groupId: String,
        val openId: String,
        val playerName: String,
        val qqUsername: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * 创建一个新的待验证绑定请求，返回生成的验证码。
     * 如果该用户已有待验证请求则先移除旧的。
     */
    fun create(groupId: String, openId: String, playerName: String, qqUsername: String): String {
        // 移除该用户旧的待验证请求
        pending.entries.removeIf { it.value.groupId == groupId && it.value.openId == openId }

        val code = generateCode()
        pending[code] = PendingBinding(groupId, openId, playerName, qqUsername)
        return code
    }

    /**
     * 根据验证码取出并移除待验证绑定。
     * 返回 null 表示验证码无效或已过期。
     */
    fun consume(code: String): PendingBinding? {
        val binding = pending.remove(code) ?: return null
        if (System.currentTimeMillis() - binding.createdAt > EXPIRE_MILLIS) {
            return null // 已过期
        }
        return binding
    }

    /** 清理所有过期的待验证请求。 */
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.createdAt > EXPIRE_MILLIS }
    }

    private fun generateCode(): String {
        val digits = "0123456789"
        return buildString {
            repeat(CODE_LENGTH) {
                append(digits[Random.nextInt(digits.length)])
            }
        }
    }
}
