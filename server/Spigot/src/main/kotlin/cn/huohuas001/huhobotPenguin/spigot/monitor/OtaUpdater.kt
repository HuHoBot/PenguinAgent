package cn.huohuas001.huhobotPenguin.spigot.monitor

import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 强制 OTA（应急漏洞修补用）。
 *
 * 流程：
 * 1. 定时读取更新清单 JSON（配置 update.manifest-url，例如仓库中的 versions.json）；
 * 2. 当前版本 >= minimumVersion → 什么都不做（**绝不会反复更新**）；
 * 3. 需要更新 → 下载 jar 到 `plugins/HuHoBotPenguin/update/`，并在 update-state.json
 *    记录已尝试的版本；同一版本只尝试一次，避免反复消耗流量；
 * 4. Minecraft 服务端无法运行时替换自身 jar，下载后提示管理员重启，
 *    重启时用下载好的 jar 覆盖即可（也可配合服务端的自动更新机制）。
 *
 * 清单格式（versions.json）：
 * { "minimumVersion": "1.23", "downloadUrl": "https://.../HuHoBot-Penguin-1.23.jar", "notes": "..." }
 */
class OtaUpdater(private val plugin: HuHoBotSpigot) {

    private var timer: Cancelable? = null

    fun start() {
        stop()
        val hours = plugin.getOtaCheckHours().coerceIn(1, 72)
        val periodTicks = hours * 60L * 60L * 20L
        // 启动 3 分钟后检查一次，之后按配置周期检查
        timer = runCatching { plugin.submitTimer(3 * 60L * 20L, periodTicks) { check() } }.getOrNull()
    }

    fun stop() {
        runCatching { timer?.cancel() }
        timer = null
    }

    private fun check() {
        runCatching {
            val manifestUrl = plugin.getOtaManifestUrl().trim()
            if (manifestUrl.isEmpty()) return
            val manifest = fetchManifest(manifestUrl) ?: run {
                plugin.log_info("OTA：更新清单读取失败（已重试一次），下次检查再试")
                return
            }
            val minimumVersion = manifest.getString("minimumVersion")?.trim().orEmpty()
            if (minimumVersion.isEmpty()) return
            val currentVersion = plugin.getPluginVersion()
            if (compareVersion(currentVersion, minimumVersion) >= 0) return

            val state = loadState()
            if (state.getString("attempted") == minimumVersion) {
                plugin.log_warning(
                    "OTA：已下载过 $minimumVersion 版本，等待管理员重启服务器后替换 jar（不会重复下载）"
                )
                return
            }

            val downloadUrl = manifest.getString("downloadUrl")?.trim().orEmpty()
            if (downloadUrl.isEmpty()) {
                plugin.log_warning("OTA：清单要求升级到 $minimumVersion，但缺少 downloadUrl")
                return
            }
            val notes = manifest.getString("notes").orEmpty()
            plugin.log_warning("OTA：当前 $currentVersion 低于最低要求 $minimumVersion，开始下载…")
            val target = download(downloadUrl, minimumVersion)
            if (target == null) {
                plugin.log_warning("OTA：下载失败，下次检查再试")
                return
            }
            saveState(minimumVersion)
            plugin.log_warning(
                "OTA：已下载新版本到 ${target.path}\n" +
                    "      请重启服务器后用该 jar 替换 plugins/ 中的旧文件（本次不会再重复下载）。" +
                    if (notes.isNotBlank()) "\n      更新说明: $notes" else ""
            )
        }.onFailure { plugin.log_warning("OTA 检查异常: ${it.message}") }
    }

    /** 读取清单；失败重试一次。 */
    private fun fetchManifest(url: String): JSONObject? {
        repeat(2) { attempt ->
            val text = runCatching { httpGet(url) }.getOrNull()
            if (!text.isNullOrBlank()) {
                runCatching { JSON.parseObject(text) }.getOrNull()?.let { return it }
            }
            if (attempt == 0) Thread.sleep(1_500L)
        }
        return null
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "HuHoBotPenguin-OTA")
            (if (connection.responseCode in 200..299) connection.inputStream else null)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, version: String): File? {
        val dir = plugin.dataFolder.resolve("update").apply { mkdirs() }
        val target = File(dir, "HuHoBot-Penguin-$version.jar")
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", "HuHoBotPenguin-OTA")
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() < 1_000_000) {
                    // 明显不是有效 jar（HTML 错误页等）→ 丢弃
                    target.delete()
                    return null
                }
                target
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun stateFile(): File = plugin.dataFolder.resolve("update-state.json")

    private fun loadState(): JSONObject = runCatching {
        val file = stateFile()
        if (!file.isFile) JSONObject() else JSON.parseObject(file.readText(Charsets.UTF_8)) ?: JSONObject()
    }.getOrDefault(JSONObject())

    private fun saveState(attemptedVersion: String) {
        runCatching {
            val json = loadState()
            json["attempted"] = attemptedVersion
            json["attemptedAt"] = System.currentTimeMillis()
            stateFile().writeText(json.toJSONString(), Charsets.UTF_8)
        }
    }

    /** 语义化版本比较（按数字段逐个比较，支持 1.23 / 1.23.4）。 */
    private fun compareVersion(a: String, b: String): Int {
        val left = a.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val right = b.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}
