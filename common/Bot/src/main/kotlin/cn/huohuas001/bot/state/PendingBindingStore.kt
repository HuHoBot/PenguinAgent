package cn.huohuas001.bot.state

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 存储待验证的绑定请求。
 *
 * 两种来源：
 * - [BindMode.GAME_VERIFY]：QQ 用户发 `/绑定 <游戏ID>` 后生成验证码，
 *   玩家进游戏执行 `/qqbind <code>` 完成绑定（游戏内验证流程）。
 * - [BindMode.FORCE_KICK]：开启强制绑定后，玩家进游戏被踢出时生成验证码，
 *   玩家在 QQ 群执行 `/绑定 <code>` 直接完成绑定，无需再进游戏。
 */
object PendingBindingStore {

    /** 验证码 → PendingBinding */
    private val pending = ConcurrentHashMap<String, PendingBinding>()

    private const val CODE_LENGTH = 5
    private const val EXPIRE_MILLIS = 5 * 60 * 1000L // 5 分钟过期

    private val secureRandom = SecureRandom()

    enum class BindMode {
        /** 等待玩家进游戏执行 /qqbind */
        GAME_VERIFY,

        /** 玩家已被踢出，等待在 QQ 群执行 /绑定 <code> */
        FORCE_KICK
    }

    data class PendingBinding(
        val groupId: String,
        val openId: String,
        val playerName: String,
        val qqUsername: String,
        val createdAt: Long = System.currentTimeMillis(),
        val mode: BindMode = BindMode.GAME_VERIFY
    )

    /**
     * 创建一个新的待验证绑定请求，返回生成的验证码。
     * 绑定以 openid 为准，所以同一 openid 在任何群发起的旧请求都会被撤销。
     */
    fun create(groupId: String, openId: String, playerName: String, qqUsername: String): String {
        // 移除该用户旧的待验证请求
        pending.entries.removeIf { it.value.openId == openId && it.value.mode == BindMode.GAME_VERIFY }

        val code = generateUniqueCode()
        pending[code] = PendingBinding(groupId, openId, playerName, qqUsername)
        return code
    }

    /**
     * 强制绑定：玩家进游戏被踢出时签发验证码。
     *
     * 重新进入服务器会刷新验证码，因此同一玩家的旧验证码立即作废。
     *
     * @return 5 位验证码
     */
    fun issueForceCode(playerName: String): String {
        pending.entries.removeIf {
            it.value.mode == BindMode.FORCE_KICK && it.value.playerName.equals(playerName, ignoreCase = true)
        }
        val code = generateUniqueCode()
        pending[code] = PendingBinding(
            groupId = "",
            openId = "",
            playerName = playerName,
            qqUsername = "",
            mode = BindMode.FORCE_KICK
        )
        return code
    }

    /**
     * 取出并移除强制绑定的验证码。
     *
     * @return 验证码无效或已过期时返回 null
     */
    fun consumeForceCode(code: String): PendingBinding? {
        val binding = pending[code] ?: return null
        if (binding.mode != BindMode.FORCE_KICK) return null
        if (!pending.remove(code, binding)) return null
        return if (System.currentTimeMillis() - binding.createdAt > EXPIRE_MILLIS) null else binding
    }

    /**
     * 根据验证码取出并移除待验证绑定（游戏内 /qqbind 使用）。
     * 返回 null 表示验证码无效、已过期或不属于游戏内验证流程。
     */
    fun consume(code: String): PendingBinding? {
        val binding = pending[code] ?: return null
        if (binding.mode != BindMode.GAME_VERIFY) return null
        if (!pending.remove(code, binding)) return null
        return if (System.currentTimeMillis() - binding.createdAt > EXPIRE_MILLIS) null else binding
    }

    /** 清理所有过期的待验证请求。 */
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.createdAt > EXPIRE_MILLIS }
    }

    /** 该玩家是否已有等待 QQ 侧确认的强制绑定验证码（未过期）。 */
    fun hasForceCodeFor(playerName: String): Boolean {
        val now = System.currentTimeMillis()
        return pending.values.any {
            it.mode == BindMode.FORCE_KICK &&
                it.playerName.equals(playerName, ignoreCase = true) &&
                now - it.createdAt <= EXPIRE_MILLIS
        }
    }

    /** 验证码可能撞号（5 位数字空间有限），这里重试直到不与现有待验证项冲突。 */
    private fun generateUniqueCode(): String {
        repeat(20) {
            val code = generateCode()
            if (!pending.containsKey(code)) return code
        }
        return generateCode()
    }

    private fun generateCode(): String {
        val digits = "0123456789"
        return buildString {
            repeat(CODE_LENGTH) {
                append(digits[secureRandom.nextInt(digits.length)])
            }
        }
    }
}