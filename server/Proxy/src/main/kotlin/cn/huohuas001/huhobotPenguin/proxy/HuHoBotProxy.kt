package cn.huohuas001.huhobotPenguin.proxy

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.huhobotPenguin.proxy.redis.RedisManager

/** Proxy 平台共享的 Redis 能力。 */
interface HuHoBotProxy : HuHoBot {
    var redisManager: RedisManager?

    fun redisEnabled(): Boolean
    fun redisHost(): String
    fun redisPort(): Int
    fun redisPassword(): String?
    fun redisChannel(): String

    /** 按当前配置销毁旧连接并重新初始化 Redis。 */
    fun reconnectRedis(): Boolean
}
