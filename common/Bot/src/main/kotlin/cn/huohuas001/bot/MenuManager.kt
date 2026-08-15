package cn.huohuas001.bot

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

    private val PANEL_ITEMS = listOf(
        PanelItem("帮助", "查看所有命令", "帮助"),
        PanelItem("查信息", "查询 OpenId", "查信息 "),
        PanelItem("查管理", "查询管理员状态", "查管理 "),
        PanelItem("加管理", "添加管理员", "加管理 "),
        PanelItem("删管理", "删除管理员", "删管理 "),
        PanelItem("管理方式", "设置管理员判定方式", "管理方式 "),
        PanelItem("添加白名单", "添加玩家白名单", "添加白名单 "),
        PanelItem("删除白名单", "删除玩家白名单", "删除白名单 "),
        PanelItem("查白名单", "查看白名单列表", "查白名单"),
        PanelItem("查在线", "查询在线玩家", "查在线"),
        PanelItem("在线服务器", "查看已连接服务器", "在线服务器"),
        PanelItem("发信息", "发送消息到游戏", "发信息 "),
        PanelItem("执行命令", "执行服务器命令", "执行命令 "),
        PanelItem("执行", "执行自定义命令", "执行 "),
        PanelItem("管理员执行", "管理员执行自定义命令", "管理员执行 "),
        PanelItem("全量", "切换全量聊天转发", "全量"),
        PanelItem("motd", "查询服务器状态", "motd "),
        PanelItem("agent", "AI 执行管理任务", "agent "),
        PanelItem("stop", "紧急停止 AI 任务", "stop"),
        PanelItem("newsession", "清除 AI 会话上下文", "newsession"),
    )

    private data class PanelItem(val name: String, val desc: String, val command: String)

    fun syncGroupPanels(starter: Starter, groupOpenIds: List<String>) {
        if (groupOpenIds.isEmpty()) return
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

            // 创建新面板（对指定群生效）
            val panelBody = JSONObject().apply {
                put("scope", "group")
                put("target_type", "specific")
                put("group_openids", JSONArray().apply {
                    groupOpenIds.forEach { add(it) }
                })
                put("panel", JSONObject().apply {
                    put("remark", "HuHoBot Penguin 指令面板")
                    put("items", JSONArray().apply {
                        PANEL_ITEMS.forEach { item ->
                            add(JSONObject().apply {
                                put("type", "command")
                                put("name", item.name)
                                put("desc", item.desc)
                            })
                        }
                    })
                })
            }
            val panelId = createPanel(authHeader, panelBody)
            if (panelId != null) {
                BotShared.getPlugin()?.log_info("指令面板已同步 (panel_id=$panelId)")
            }
        } catch (e: Exception) {
            BotShared.getPlugin()?.log_error("指令面板同步失败: ${e.message}")
        }
    }

    /** 获取指定命令的触发文本（供点击面板时使用） */
    fun getCommandTrigger(name: String): String? {
        return PANEL_ITEMS.find { it.name == name }?.command
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
