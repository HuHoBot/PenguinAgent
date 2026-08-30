package cn.huohuas001.bot.web

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HuHoBot WebUI 管理服务器。
 *
 * 在 5678 端口提供：
 *  - 暗色简约风格的图形化配置界面
 *  - 登录鉴权（令牌），密码由 [WebUiPassword] 管理
 *  - 配置读取 / 保存 / 重载 / 修改 WebUI 密码
 *
 * 启动流程见 [start]，关闭见 [stop]。
 */
object WebUiServer {

    private const val DEFAULT_PORT = 5678
    private const val TOKEN_TTL_MILLIS = 24 * 60 * 60 * 1000L

    private var httpServer: HttpServer? = null
    private val running = AtomicBoolean(false)

    /** token -> 过期时间戳 */
    private val tokens = ConcurrentHashMap<String, Long>()

    /** 启动 WebUI 服务器；已在运行时直接返回。 */
    @Synchronized
    fun start() {
        if (running.get()) return
        try {
            val plugin = BotShared.getPlugin()
            val port = plugin.getWebUiPort()
            val server = HttpServer.create(InetSocketAddress(port), 0)
            server.executor = Executors.newFixedThreadPool(2)
            server.createContext("/", WebUiServer::handle)
            server.start()
            httpServer = server
            running.set(true)
            plugin.log_info("网页配置UI已在127.0.0.1:${port}启动")
            val generated = WebUiPassword.ensureGenerated()
            if (generated.isNotEmpty()) {
                plugin.log_info("WebUI 管理密码（仅首次启动自动生成，可用 /hb password 修改）: $generated")
            } else {
                plugin.log_info("WebUI 管理密码: 已设置（可用 /hb password 修改）")
            }
        } catch (error: Exception) {
            BotShared.getPlugin().log_warning("WebUI 启动失败（端口可能被占用）: ${error.message}")
        }
    }

    /** 停止 WebUI 服务器。 */
    @Synchronized
    fun stop() {
        if (!running.get()) return
        try {
            httpServer?.stop(0)
        } catch (_: Exception) {
        }
        httpServer = null
        running.set(false)
        tokens.clear()
    }

    /** 游戏内 /hb password 修改密码后，清除已登录的旧令牌。 */
    fun invalidateAllTokens() {
        tokens.clear()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod

            when {
                path == "/" || path == "/index.html" -> serveResource(exchange, "/webui/index.html", "text/html; charset=utf-8")
                path == "/app.js" -> serveResource(exchange, "/webui/app.js", "application/javascript; charset=utf-8")
                path == "/style.css" -> serveResource(exchange, "/webui/style.css", "text/css; charset=utf-8")
                path == "/api/login" && method == "POST" -> handleLogin(exchange)
                path == "/api/config" && method == "GET" -> handleGetConfig(exchange)
                path == "/api/config" && method == "POST" -> handleSaveConfig(exchange)
                path == "/api/status" && method == "GET" -> handleStatus(exchange)
                path == "/api/password" && method == "POST" -> handlePassword(exchange)
                else -> respond(exchange, 404, """{"error":"Not Found"}""")
            }
        } catch (error: Throwable) {
            try {
                respond(exchange, 500, JSON.toJSONString(mapOf("error" to (error.message ?: "internal error"))))
            } catch (_: Throwable) {
            }
        } finally {
            exchange.close()
        }
    }

    // ---------------------------------------------------------------- 静态资源

    private fun serveResource(exchange: HttpExchange, resourcePath: String, contentType: String) {
        val stream = WebUiServer::class.java.getResourceAsStream(resourcePath)
        if (stream == null) {
            respond(exchange, 404, "Not Found")
            return
        }
        val bytes = stream.use { it.readBytes() }
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // ---------------------------------------------------------------- API

    private fun handleLogin(exchange: HttpExchange) {
        val body = parseBody(exchange) ?: return respond(exchange, 400, """{"error":"bad request"}""")
        val password = body.getString("password").orEmpty()
        if (!WebUiPassword.verify(password)) {
            respond(exchange, 401, """{"error":"密码错误"}""")
            return
        }
        val token = generateToken()
        tokens[token] = System.currentTimeMillis() + TOKEN_TTL_MILLIS
        respond(exchange, 200, JSON.toJSONString(mapOf("token" to token)))
    }

    private fun handleGetConfig(exchange: HttpExchange) {
        if (!authorize(exchange)) return
        val plugin = BotShared.getPlugin()
        val values = plugin.getWebUiConfigValues()
        val payload = JSONObject()
        payload["schema"] = JSON.parseArray(WebUiSchema.toJson())
        payload["values"] = JSON.toJSON(values)
        payload["platform"] = plugin.getPlatform()
        respond(exchange, 200, payload.toJSONString())
    }

    private fun handleSaveConfig(exchange: HttpExchange) {
        if (!authorize(exchange)) return
        val body = parseBody(exchange) ?: return respond(exchange, 400, """{"error":"bad request"}""")
        val changes = body.getJSONObject("changes") ?: return respond(exchange, 400, """{"error":"changes required"}""")
        val applied = BotShared.getPlugin().applyWebUiConfigChanges(changes)
        if (applied) {
            respond(exchange, 200, """{"ok":true}""")
        } else {
            respond(exchange, 500, """{"error":"配置保存失败"}""")
        }
    }

    private fun handleStatus(exchange: HttpExchange) {
        if (!authorize(exchange)) return
        val plugin = BotShared.getPlugin()
        val payload = JSONObject()
        payload["platform"] = plugin.getPlatform()
        payload["version"] = plugin.getPluginVersion()
        payload["serverName"] = plugin.getServerName()
        payload["botName"] = plugin.getBotName()
        payload["appId"] = plugin.getBotAppId()
        payload["qqConnected"] = QClient.getStarter() != null
        payload["online"] = plugin.getOnlineList()
        payload["groups"] = plugin.getGroupOpenIdList()
        payload["agentEnabled"] = plugin.getAgentEnabled()
        respond(exchange, 200, payload.toJSONString())
    }

    private fun handlePassword(exchange: HttpExchange) {
        if (!authorize(exchange)) return
        val body = parseBody(exchange) ?: return respond(exchange, 400, """{"error":"bad request"}""")
        val newPassword = body.getString("newPassword").orEmpty()
        if (WebUiPassword.changePassword(newPassword)) {
            invalidateAllTokens()
            respond(exchange, 200, """{"ok":true}""")
        } else {
            respond(exchange, 400, """{"error":"密码需至少 6 位"}""")
        }
    }

    // ---------------------------------------------------------------- 工具方法

    private fun parseBody(exchange: HttpExchange): JSONObject? {
        val bytes = exchange.requestBody.use { it.readBytes() }
        if (bytes.isEmpty()) return null
        return try {
            JSON.parseObject(String(bytes, Charsets.UTF_8)) ?: JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun authorize(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        val token = header.removePrefix("Bearer ").trim()
        if (token.isNotEmpty() && tokens[token]?.let { it > System.currentTimeMillis() } == true) {
            return true
        }
        respond(exchange, 401, """{"error":"未授权"}""")
        return false
    }

    private fun generateToken(): String {
        val sb = StringBuilder(64)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        repeat(64) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}