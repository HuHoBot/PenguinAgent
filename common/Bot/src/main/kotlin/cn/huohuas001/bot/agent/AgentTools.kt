package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

/** AI 可通过 function calling 使用的服务器工具。 */
object AgentTools {

    /** 获取服务器插件列表 */
    const val TOOL_GET_PLUGIN_LIST = "get_server_plugin_list"

    /** 获取服务器命令帮助（可指定插件或具体命令） */
    const val TOOL_GET_COMMAND_HELP = "get_command_help"

    /** 执行服务器命令（手动模式下需管理员审批） */
    const val TOOL_RUN_COMMAND = "run_command"

    /** 读取服务端最新日志 */
    const val TOOL_READ_SERVER_LOGS = "read_server_logs"

    /** 加载命令语法 SKILL（按需加载，减少 token 消耗）。 */
    const val TOOL_LOAD_SKILL = "load_skill"

    /** 构建 function calling 工具清单。 */
    fun buildTools(): JSONArray = JSONArray().apply {
        add(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_GET_PLUGIN_LIST)
                put("description", "获取服务器上当前已安装的插件列表。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        })
        add(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_GET_COMMAND_HELP)
                put(
                    "description",
                    "获取服务器命令的帮助信息（包括插件命令与原版命令，如 give、gamemode、time）。" +
                        "不传任何参数时返回所有命令概览；指定 plugin 时返回该插件注册的命令及帮助；" +
                        "指定 command 时返回该具体命令的详细帮助。"
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("plugin", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选。插件名称，例如 Essentials。")
                        })
                        put("command", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选。具体命令名称，例如 kick。")
                        })
                    })
                })
            })
        })
        add(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_RUN_COMMAND)
                put(
                    "description",
                    "在服务器控制台执行一条命令，例如 kick Player 使用外挂、whitelist add Player。" +
                        "执行属于敏感操作，手动审批模式下会先请求管理员确认。"
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "string")
                            put("description", "要执行的完整命令，例如 kick Player 使用外挂。")
                        })
                    })
                    put("required", JSONArray().apply { add("command") })
                })
            })
        })
        add(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_READ_SERVER_LOGS)
                put(
                    "description",
                    "读取服务端最新日志（logs/latest.log），用于分析插件报错、警告等信息。" +
                        "可指定读取末尾行数，或用关键词过滤只返回相关日志行及其上下文。"
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("lines", JSONObject().apply {
                            put("type", "integer")
                            put("description", "可选。读取日志末尾的行数，默认 50，范围 1-500。")
                        })
                        put("keyword", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选。只返回包含该关键词（如 Exception、ERROR、插件名）的日志行及其上下文。")
                        })
                    })
                })
            })
        })
        add(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_LOAD_SKILL)
                put(
                    "description",
                    "加载 Minecraft 命令语法 SKILL 文档。在生成任何涉及物品数据组件的命令前，" +
                        "必须先加载对应的 SKILL 获取正确的语法格式。可用的 skill：\n" +
                        "- components：所有数据组件的格式和用法参考（最常用，给任何命令生成物品前都应先读）\n" +
                        "- give：/give 命令语法和示例\n" +
                        "- item：/item 命令（替换/修改容器物品）\n" +
                        "- summon：/summon 命令（实体 NBT，物品字段格式）\n" +
                        "- data：/data 命令（读写 NBT 数据）\n" +
                        "- loot：/loot 命令（战利品表）\n" +
                        "- clear：/clear 和 /execute if items 命令（物品检测）"
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("skill", JSONObject().apply {
                            put("type", "string")
                            put(
                                "description",
                                "SKILL 名称。可选值：components、give、item、summon、data、loot、clear"
                            )
                        })
                    })
                    put("required", JSONArray().apply { add("skill") })
                })
            })
        })
    }

    /** 工具执行结果。 */
    class ToolResult(
        /** 返回给 AI 作为工具执行结果的文本。 */
        val aiText: String,
        /** 需要在 QQ 群以 Markdown 卡片展示的获取/执行说明；为空表示不展示。 */
        val displayTitle: String = "",
        /** 需要在 QQ 群展示的正文（配合 [displayTitle]）。 */
        val displayContent: String = ""
    )

    /** 解析工具调用的 JSON 参数为 JSONObject；解析失败返回空对象。 */
    fun parseArguments(arguments: String?): JSONObject = try {
        JSON.parseObject(arguments ?: "{}") ?: JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    /** 获取服务器插件列表（获取类操作，无需审批）。 */
    fun getPluginList(plugin: HuHoBot): ToolResult {
        val list = try {
            plugin.getServerPluginList()
        } catch (error: Throwable) {
            plugin.log_error("Agent 获取插件列表失败: ${error.message}")
            emptyList()
        }
        if (list.isEmpty()) {
            return ToolResult("服务器插件列表为空或当前平台不支持查询。", "服务器插件列表", "当前平台无法获取插件列表。")
        }
        val display = list.joinToString("\n") { "- $it" }
        return ToolResult(
            aiText = "服务器插件列表（共 ${list.size} 个）：\n${list.joinToString("\n")}",
            displayTitle = "服务器插件列表",
            displayContent = display
        )
    }

    /** 获取服务器命令帮助（获取类操作，无需审批）。 */
    fun getCommandHelp(plugin: HuHoBot, query: JSONObject): ToolResult {
        val pluginName = query.getString("plugin")?.trim()?.takeIf(String::isNotEmpty)
        val commandName = query.getString("command")?.trim()?.takeIf(String::isNotEmpty)
        val help = try {
            plugin.getServerCommandHelp(pluginName, commandName)
        } catch (error: Throwable) {
            plugin.log_error("Agent 获取命令帮助失败: ${error.message}")
            "获取命令帮助失败：${error.message}"
        }

        val title = when {
            commandName != null -> "命令 /$commandName 的帮助"
            pluginName != null -> "插件 $pluginName 的命令帮助"
            else -> "服务器命令帮助"
        }
        return ToolResult(
            aiText = help,
            displayTitle = title,
            displayContent = help
        )
    }

    /** 读取服务端最新日志（获取类操作，无需审批）。 */
    fun getServerLogs(plugin: HuHoBot, query: JSONObject): ToolResult {
        val lines = query.getInteger("lines")
        val keyword = query.getString("keyword")?.trim()?.takeIf(String::isNotEmpty)
        val content = try {
            plugin.getServerLogs(lines, keyword)
        } catch (error: Throwable) {
            plugin.log_error("Agent 读取服务端日志失败: ${error.message}")
            "读取服务端日志失败：${error.message}"
        }
        return ToolResult(
            aiText = content,
            displayTitle = "服务端日志",
            displayContent = content
        )
    }

    /** 加载命令语法 SKILL 文档（获取类操作，无需审批）。 */
    fun loadSkill(query: JSONObject): ToolResult {
        val skillName = query.getString("skill")?.trim()?.lowercase()
        if (skillName.isNullOrBlank()) {
            return ToolResult("请指定 skill 名称。可用：components、give、item、summon、data、loot、clear")
        }
        val validSkills = setOf("components", "give", "item", "summon", "data", "loot", "clear")
        if (skillName !in validSkills) {
            return ToolResult("未知的 skill: $skillName。可用：${validSkills.joinToString("、")}")
        }
        val resourcePath = "skill-$skillName.md"
        return try {
            val stream = AgentTools::class.java.classLoader.getResourceAsStream(resourcePath)
            if (stream == null) {
                ToolResult("SKILL 文件不存在: $resourcePath")
            } else {
                val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                ToolResult(aiText = content)
            }
        } catch (error: Throwable) {
            ToolResult("加载 SKILL 失败: ${error.message}")
        }
    }
}
