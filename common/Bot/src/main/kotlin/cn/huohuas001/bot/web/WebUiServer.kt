package cn.huohuas001.bot.web

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.addon.AddonCenterClient
import cn.huohuas001.bot.addon.InstalledAddonStore
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.state.GroupDirectory
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
                path == "/api/addons" && method == "GET" -> handleAddonList(exchange)
                path == "/api/addons/install" && method == "POST" -> handleAddonInstall(exchange)
                path == "/api/addons/remove" && method == "POST" -> handleAddonRemove(exchange)
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
        scheduleGroupNameRefresh(plugin)
        payload["groupNames"] = JSON.toJSON(GroupDirectory.snapshot())
        respond(exchange, 200, payload.toJSONString())
    }

    /** 群名称走网络查询，后台刷新，本次先用缓存名称返回。 */
    private fun scheduleGroupNameRefresh(plugin: HuHoBot) {
        val stale = plugin.getGroupOpenIdList().filter { GroupDirectory.isStale(it) }
        if (stale.isEmpty()) return
        plugin.submitAsync { QClient.refreshGroupNames(stale) }
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
        val groupOpenIds = plugin.getGroupOpenIdList()
        scheduleGroupNameRefresh(plugin)
        payload["groups"] = JSON.toJSON(
            groupOpenIds.map { openId ->
                mapOf(
                    "openId" to openId,
                    "name" to (GroupDirectory.nameOf(openId) ?: ""),
                    "suffix" to openId.takeLast(6)
                )
            }
        )
        payload["agentEnabled"] = plugin.getAgentEnabled()
        respond(exchange, 200, payload.toJSONString())
    }

    /** 附属插件中心列表（仅 Spigot / Paper）。 */
    private fun handleAddonList(exchange: HttpExchange) {
        if (!authorize(exchange)) return
        val query = parseQuery(exchange)
        val search = query["search"]
        val addons = AddonCenterClient.listAddons(search)
        val loaded = BotShared.getPlugin().getServerPluginList().toMutableList()
        val installedFiles = InstalledAddonStore.files().toMutableList()
        val records = InstalledAddonStore.all()
        records.forEach { record ->
            if (record.file.isNotBlank() && record.file !in installedFiles) installedFiles.add(record.file)
            if (record.name.isNotBlank() && record.name !in loaded) loaded.add(record.name)
        }
        val payload = JSONObject()
        payload["center"] = AddonCenterClient.CENTER_URL
        payload["total"] = addons.size
        payload["installed"] = JSON.toJSON(loaded)
        payload["installedRecords"] = JSON.toJSON(
            records.map { record ->
                mapOf(
                    "id" to record.id,
                    "name" to record.name,
                    "version" to record.version,
                    "file" to record.file
                )
            }
        )
        payload["plugins"] = JSON.toJSON(
            addons.map { entry ->
                mapOf(
                    "id" to entry.id,
                    "name" to entry.name,
                    "version" to entry.version,
                    "author" to entry.author,
                    "description" to entry.description,
                    "tags" to entry.tags,
                    "serverType" to entry.serverType,
                    "fileName" to entry.jarName,
                    "fileSize" to entry.jarSize,
                    "downloads" to entry.downloads,
                    "readme" to entry.readme.take(4000)
                )
            }
        )
        respond(exchange, 200, payload.toJSONString())
    }

    /** 下载插件到服务端插件目录；不热加载，需重启生效。 */
    private fun handleAddonInstall(exchange: HttpExchange) {
        if (!authorize(exchange)) return
        val body = parseBody(exchange) ?: return respond(exchange, 400, """{"error":"bad request"}""")
        val id = body.getString("id").orEmpty().trim()
        if (id.isEmpty()) return respond(exchange, 400, """{"error":"缺少插件 ID"}""")

        val plugin = BotShared.getPlugin()
        val detail = AddonCenterClient.detail(id)
        val downloaded = AddonCenterClient.download(id)
            ?: return respond(exchange, 502, """{"error":"下载失败，请稍后重试"}""")
        val target = safeFileName(downloaded.fileName, id)
        if (!plugin.installAddon(target, downloaded.bytes)) {
            return respond(exchange, 500, """{"error":"写入插件目录失败"}""")
        }
        InstalledAddonStore.record(
            id = id,
            name = detail?.name.orEmpty(),
            version = detail?.version.orEmpty(),
            file = target
        )
        plugin.log_info("已从附属插件中心下载 $target，重启服务器后生效")
        respond(
            exchange, 200,
            JSON.toJSONString(mapOf("ok" to true, "file" to target, "restart" to true))
        )
    }

    /** 删除已下载的附属插件文件；同样需要重启服务器。 */
    private fun handleAddonRemove(exchange: HttpExchange) {
        if (!authorize(exchange)) return
        val body = parseBody(exchange) ?: return respond(exchange, 400, """{"error":"bad request"}""")
        val file = body.getString("file").orEmpty().trim()
        if (file.isEmpty()) return respond(exchange, 400, """{"error":"缺少文件名"}""")
        val record = InstalledAddonStore.removeByFile(file)
            ?: return respond(exchange, 404, """{"error":"未找到该插件的安装记录"}""")
        val deleted = BotShared.getPlugin().removeAddon(record.file)
        if (!deleted) {
            InstalledAddonStore.record(record.id, record.name, record.version, record.file)
            return respond(exchange, 500, """{"error":"删除文件失败"}""")
        }
        BotShared.getPlugin().log_info("已删除附属插件 ${record.file}，重启服务器后生效")
        respond(exchange, 200, JSON.toJSONString(mapOf("ok" to true, "file" to record.file, "restart" to true)))
    }

    /** 只保留文件名，避免远端返回的路径影响写入位置。 */
    private fun safeFileName(rawName: String, id: String): String {
        val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
        if (cleaned.isBlank() || cleaned.contains("..")) return "addon-$id.jar"
        return if (cleaned.endsWith(".jar", true) || cleaned.endsWith(".zip", true)) cleaned
        else "$cleaned.jar"
    }

    private fun parseQuery(exchange: HttpExchange): Map<String, String> {
        val raw = exchange.requestURI.rawQuery ?: return emptyMap()
        return raw.split("&")
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                java.net.URLDecoder.decode(key, "UTF-8") to
                    java.net.URLDecoder.decode(value, "UTF-8")
            }
            .toMap()
    }

    private fun handlePassword(exchange: HttpExchange) {        if (!authorize(exchange)) return
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