package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动同步指令面板到 QQ 群。
 * 启动时调用 syncGroupPanels()，会先删除旧面板再创建新面板。
 */
object MenuManager {
    private const val API_BASE = "https://api.sgroup.qq.com"

    fun syncGroupPanels(
        starter: Starter,
        groupOpenIds: List<String>,
        builtInCommands: Collection<RegisteredCommand>,
        customCommands: Collection<RegisteredCommand> = emptyList()
    ) {
        if (groupOpenIds.isEmpty()) return
        val plugin = BotShared.getPlugin()
        try {
            val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
            val token = start0.accessToken ?: return
            val authHeader = "QQBot $token"

            // 查询现有 group 面板
            val existingPanels = listPanels(authHeader, "group")
            for (panel in existingPanels) {
                val panelId = panel.getString("panel_id") ?: continue
                deletePanel(authHeader, panelId)
            }

            // 构建面板项：内置命令 + 自定义命令
            val allItems = mutableListOf<JSONObject>()
            val seen = mutableSetOf<String>()

            for (cmd in builtInCommands) {
                if (cmd.command in seen) continue
                seen.add(cmd.command)
                allItems.add(JSONObject().apply {
                    put("type", "command")
                    put("name", cmd.command)
                    put("desc", cmd.describe)
                })
            }
            for (cmd in customCommands) {
                if (cmd.command in seen) continue
                seen.add(cmd.command)
                allItems.add(JSONObject().apply {
                    put("type", "command")
                    put("name", cmd.command)
                    put("desc", cmd.describe)
                })
            }

            if (allItems.isEmpty()) {
                plugin?.log_warning("没有已注册的 QQ 命令，跳过指令面板同步")
                return
            }

            // QQ 面板最多支持 20 个命令，超出部分仅在 /帮助 中显示
            val limitedItems = if (allItems.size > 20) {
                plugin?.log_warning("已注册命令数 ${allItems.size} 超过面板上限 20，超出部分仅在帮助中显示")
                allItems.subList(0, 20)
            } else allItems

            // 创建新面板
            val panelBody = JSONObject().apply {
                put("scope", "group")
                put("target_type", "specific")
                put("group_openids", JSONArray().apply {
                    groupOpenIds.forEach { add(it) }
                })
                put("panel", JSONObject().apply {
                    put("remark", "HuHoBot Penguin 指令面板")
                    put("items", JSONArray().apply {
                        limitedItems.forEach { add(it) }
                    })
                })
            }
            val panelId = createPanel(authHeader, panelBody)
            if (panelId != null) {
                plugin?.log_info("指令面板已同步 (panel_id=$panelId, commands=${allItems.size})")
            }
        } catch (e: Exception) {
            BotShared.getPlugin()?.log_error("指令面板同步失败: ${e.message}")
        }
    }

    private fun listPanels(authHeader: String, scope: String): List<JSONObject> {
        val conn = URL("$API_BASE/v2/panels?scope=$scope&limit=50").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", authHeader)
        conn.setRequestProperty("Accept", "application/json")
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: "{}"
        conn.disconnect()
        val body = JSON.parseObject(text)
        return body?.getJSONArray("records")?.toList()?.filterIsInstance<JSONObject>() ?: emptyList()
    }

    private fun deletePanel(authHeader: String, panelId: String) {
        val conn = URL("$API_BASE/v2/panels/$panelId").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Authorization", authHeader)
        conn.inputStream?.close()
        conn.disconnect()
    }

    private fun createPanel(authHeader: String, body: JSONObject): String? {
        val conn = URL("$API_BASE/v2/panels").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", authHeader)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toJSONString()) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        BotShared.getPlugin()?.log_info("面板API响应 [${conn.responseCode}]: $text")
        val resp = JSON.parseObject(text)
        return resp?.getString("panel_id")
    }
}
