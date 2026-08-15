package cn.huohuas001.huhobotPenguin.proxy.redis

import cn.huohuas001.huhobotPenguin.proxy.HuHoBotProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub
import java.time.Duration

/** Proxy 与子服务器之间的 Redis 命令发布及回调订阅器。 */
class RedisManager(private val plugin: HuHoBotProxy) {
    @Volatile
    private var jedisPool: JedisPool? = null

    private val commandChannel: String
        get() = plugin.redisChannel()

    val callbackChannel: String
        get() = "${commandChannel}_callback"

    private val subscriberScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var subscriberJob: Job? = null

    @Volatile
    private var pubSub: JedisPubSub? = null

    var commandCallback: CommandCallback? = null
        private set

    fun connect(host: String, port: Int, password: String?) {
        try {
            val poolConfig = JedisPoolConfig().apply {
                maxTotal = 10
                maxIdle = 5
                minIdle = 1
                testOnBorrow = true
                testOnReturn = true
                testWhileIdle = true
                setMaxWait(Duration.ofSeconds(3))
            }

            jedisPool = if (password.isNullOrEmpty()) {
                JedisPool(poolConfig, host, port)
            } else {
                JedisPool(poolConfig, host, port, 2_000, password)
            }

            jedisPool?.resource?.use { jedis -> jedis.ping() }
            commandCallback = CommandCallback(plugin)
            startCallbackListener(host, port, password)
            plugin.log_info("Redis 连接成功: $host:$port，命令频道: $commandChannel")
        } catch (error: Exception) {
            plugin.log_error("Redis 连接失败: ${error.message}")
            jedisPool?.close()
            jedisPool = null
            commandCallback = null
        }
    }

    private fun startCallbackListener(host: String, port: Int, password: String?) {
        subscriberJob = subscriberScope.launch {
            try {
                Jedis(host, port).use { jedis ->
                    if (!password.isNullOrEmpty()) jedis.auth(password)
                    pubSub = object : JedisPubSub() {
                        override fun onMessage(channel: String, message: String) {
                            commandCallback?.handleCallback(message)
                        }
                    }
                    jedis.subscribe(pubSub, callbackChannel)
                }
            } catch (_: CancellationException) {
                // 插件关闭或重连时的正常取消。
            } catch (error: Exception) {
                plugin.log_error("Redis 回调监听器异常: ${error.message}")
            }
        }
    }

    fun disconnect() {
        try {
            pubSub?.unsubscribe()
        } catch (_: Exception) {
            // 连接已经关闭时无需再次处理。
        }
        pubSub = null
        subscriberJob?.cancel()
        subscriberJob = null
        subscriberScope.cancel()
        commandCallback?.cancelAll()
        commandCallback = null
        jedisPool?.close()
        jedisPool = null
        plugin.log_info("Redis 连接已断开")
    }

    fun isConnected(): Boolean = try {
        jedisPool?.resource?.use { it.ping() == "PONG" } ?: false
    } catch (_: Exception) {
        false
    }

    /** 发布 `serverName|command`。`ALL` 表示所有订阅该频道的子服务器。 */
    fun sendCommand(serverName: String, command: String): Boolean =
        publishRaw("$serverName|$command", "发送命令")

    /** 发布 `serverName|taskId|command`，由子服务器向回调频道回传执行输出。 */
    fun sendCommandWithCallback(serverName: String, taskId: String, command: String): Boolean =
        publishRaw("$serverName|$taskId|$command", "发送带回调命令")

    /** 使用子服务器约定的 `broadcast` 命令广播游戏消息。 */
    fun broadcast(message: String): Boolean = sendCommand("ALL", "broadcast $message")

    /** 发布自定义 `type|data` 消息。 */
    fun publish(type: String, data: String): Boolean = publishRaw("$type|$data", "发布 Redis 消息")

    fun getJedis(): Jedis? = try {
        jedisPool?.resource
    } catch (_: Exception) {
        null
    }

    private fun publishRaw(message: String, operation: String): Boolean = try {
        jedisPool?.resource?.use { jedis ->
            jedis.publish(commandChannel, message)
            true
        } ?: run {
            plugin.log_warning("Redis 未连接，无法$operation")
            false
        }
    } catch (error: Exception) {
        plugin.log_error("${operation}失败: ${error.message}")
        false
    }
}
