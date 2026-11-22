package agent.ai

import agent.HuHoBot
import agent.provider.BotShared
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Starter

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

    // ── 服务器只读工具（新增） ──

    /** 实时状态：TPS/内存/在线人数/运行时长 */
    const val TOOL_GET_SERVER_STATUS = "get_server_status"

    /** 在线玩家列表 */
    const val TOOL_GET_ONLINE_LIST = "get_online_list"

    /** 玩家统计数值（击杀/死亡/游戏时长/等级/坐标） */
    const val TOOL_GET_PLAYER_INFO = "get_player_info"

    /** 玩家背包（文本形式） */
    const val TOOL_GET_PLAYER_INVENTORY = "get_player_inventory"

    /** 定时任务管理 */
    const val TOOL_CREATE_TIMED_TASK = "create_timed_task"
    const val TOOL_DELETE_TIMED_TASK = "delete_timed_task"

    /** 联网搜索（配置了搜索 provider 时可用） */
    const val TOOL_WEB_SEARCH = "web_search"

    // ── QQ 群管理工具 ──

    /** 获取群基本信息 */
    const val TOOL_GET_GROUP_INFO = "get_group_info"

    /** 获取机器人群内状态 */
    const val TOOL_GET_BOT_STATE = "get_bot_state"

    /** 拉取入群申请列表 */
    const val TOOL_GET_JOIN_REQUESTS = "get_join_requests"

    /** 审批入群申请 */
    const val TOOL_APPROVE_JOIN_REQUEST = "approve_join_request"

    /** 查询群禁言状态 */
    const val TOOL_GET_MUTE_STATUS = "get_mute_status"

    /** 设置群成员禁言 */
    const val TOOL_SET_MEMBER_MUTE = "set_member_mute"

    /** 查询入群自动审批策略列表 */
    const val TOOL_LIST_AUTO_APPROVE_POLICIES = "list_auto_approve_policies"

    /** 创建入群自动审批策略 */
    const val TOOL_CREATE_AUTO_APPROVE_POLICY = "create_auto_approve_policy"

    /** 修改入群自动审批策略 */
    const val TOOL_UPDATE_AUTO_APPROVE_POLICY = "update_auto_approve_policy"

    /** 删除入群自动审批策略 */
    const val TOOL_DELETE_AUTO_APPROVE_POLICY = "delete_auto_approve_policy"

    /** 执行入群自动审批策略 */
    const val TOOL_EXECUTE_AUTO_APPROVE_POLICY = "execute_auto_approve_policy"

    /** 修改入群自动审批策略的白名单号码 */
    const val TOOL_UPDATE_WHITELIST_USERS = "update_whitelist_users"

    const val TOOL_CALCULATE = "calculate"
    const val TOOL_RANDOM_CHOICE = "random_choice"
    const val TOOL_WAIT = "sleep"

    private fun utilityDefinitions(): List<JSONObject> = listOf(
        typedTool(TOOL_CALCULATE, "安全计算算术表达式；支持 + - * / % ^ 与括号，不执行程序代码。", mapOf(
            "expression" to JSONObject().apply { put("type", "string"); put("maxLength", 256) }
        ), listOf("expression")),
        typedTool(TOOL_RANDOM_CHOICE, "从给定 options 数组随机选一项，或从 min 到 max（均含）随机选一个整数；二者选其一。", mapOf(
            "options" to JSONObject().apply {
                put("type", "array"); put("minItems", 1); put("maxItems", 1000)
                put("items", JSONObject().apply { put("type", "string"); put("maxLength", 4096) })
            },
            "min" to JSONObject().apply { put("type", "integer"); put("minimum", -1000000000); put("maximum", 1000000000) },
            "max" to JSONObject().apply { put("type", "integer"); put("minimum", -1000000000); put("maximum", 1000000000) }
        )),
        typedTool(TOOL_WAIT, "异步等待后继续当前任务；不阻塞服务器 tick 或占用工作线程。duration 必须为正数，unit 为 seconds/minutes/hours。超过配置 agent.max-wait-seconds 时按该上限等待；重启不恢复，持久定时任务请用 create_timed_task。", mapOf(
            "duration" to JSONObject().apply { put("type", "number"); put("exclusiveMinimum", 0) },
            "unit" to JSONObject().apply {
                put("type", "string"); put("enum", listOf("seconds", "minutes", "hours"))
            }
        ), listOf("duration", "unit"))
    )

    fun calculate(query: JSONObject): ToolResult = try {
        val value = SafeArithmetic.evaluate(query.getString("expression").orEmpty())
        ToolResult(java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
    } catch (error: IllegalArgumentException) {
        ToolResult("计算失败：${error.message}")
    }

    fun randomChoice(query: JSONObject): ToolResult = try {
        val options = query.getJSONArray("options")
        val value = if (options != null) {
            require(!query.containsKey("min") && !query.containsKey("max")) { "options 与 min/max 不能同时提供" }
            require(options.size in 1..1000 && options.all { it is String && it.length <= 4096 } && options.toJSONString().length <= 65536) { "options 必须为 1..1000 个短文本" }
            options[java.util.concurrent.ThreadLocalRandom.current().nextInt(options.size)].toString()
        } else {
            fun bound(key: String): Long {
                val raw = query[key]?.toString()?.toLongOrNull() ?: throw IllegalArgumentException("缺少整数 $key")
                require(raw in -1000000000L..1000000000L) { "$key 超出支持范围" }
                return raw
            }
            val min = bound("min")
            val max = bound("max")
            require(min <= max) { "min 必须小于等于 max" }
            java.util.concurrent.ThreadLocalRandom.current().nextLong(min, max + 1).toString()
        }
        ToolResult(value)
    } catch (error: Exception) {
        ToolResult("随机取值失败：${error.message}")
    }

    fun waitSeconds(query: JSONObject, maximum: Int): Int {
        val duration = query["duration"]?.toString()?.toDoubleOrNull()
            ?: throw IllegalArgumentException("duration 必须为正数")
        require(duration.isFinite() && duration > 0.0) { "duration 必须为有限正数" }
        val multiplier = when (query.getString("unit")?.lowercase()) {
            "seconds" -> 1.0
            "minutes" -> 60.0
            "hours" -> 3600.0
            else -> throw IllegalArgumentException("unit 只能是 seconds/minutes/hours")
        }
        return kotlin.math.ceil((duration * multiplier).coerceAtMost(maximum.coerceIn(1, 86400).toDouble())).toInt().coerceAtLeast(1)
    }

    private fun typedTool(name: String, description: String, properties: Map<String, JSONObject>, required: List<String> = emptyList()): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name); put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object"); put("properties", properties); put("additionalProperties", false)
                if (required.isNotEmpty()) put("required", required)
            })
        })
    }

    /** 构建 function calling 工具清单。 */
    fun buildTools(): JSONArray = JSONArray().apply {
        addAll(utilityDefinitions())
        // TTS 工具：启用且有任一模型 ID 时才对 AI 可见
        BotShared.getPluginOrNull()?.takeIf { it.isTtsEnabled() }?.let { _ ->
            TtsManager.toolDescription()?.let { desc -> add(TtsManager.speakTool(desc)) }
        }
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
        // ── 服务器只读工具 ──
        add(toolDef(TOOL_GET_SERVER_STATUS, "获取服务器实时状态：TPS、内存占用、在线人数、运行时长。判断服务器是否卡顿时使用。", mapOf()))
        add(toolDef(TOOL_GET_ONLINE_LIST, "获取当前在线玩家列表。", mapOf()))
        add(toolDef(TOOL_GET_PLAYER_INFO, "获取玩家的统计数值：击杀数、死亡数、游戏时长、等级、所在位置。", mapOf(
            "player" to "玩家名"
        )))
        add(toolDef(TOOL_GET_PLAYER_INVENTORY, "以文本形式查看玩家背包内容（主背包+盔甲+副手）。", mapOf(
            "player" to "玩家名（在线玩家；离线玩家返回快照）"
        )))
        add(toolDef(TOOL_CREATE_TIMED_TASK, "创建定时任务（持久化到 timed-tasks.yml，立即生效）。动作可同时给 message 与 command。", mapOf(
            "name" to "任务名（唯一）",
            "cron" to "cron 表达式（分 时 日 月 周，如 \"0 9 * * *\"）；与 interval-seconds 二选一",
            "interval_seconds" to "间隔秒数（与 cron 二选一）",
            "group" to "目标群 OpenID（可选，空=全部群）",
            "message" to "发送到群的消息（可选）",
            "command" to "要执行的服务器命令（可选，会过黑名单）"
        )))
        add(toolDef(TOOL_DELETE_TIMED_TASK, "删除指定名称的定时任务。", mapOf(
            "name" to "任务名"
        )))
        add(toolDef(TOOL_WEB_SEARCH, "联网搜索（需要在配置中启用搜索 provider）。", mapOf(
            "query" to "搜索关键词"
        )))

        // ── QQ 群管理工具 ──
        add(toolDef(TOOL_GET_GROUP_INFO, "获取当前 QQ 群基本信息（群名、人数、标签等）。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）"
        )))
        add(toolDef(TOOL_GET_BOT_STATE, "获取机器人在当前群中的状态（角色、入群时间等）。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）"
        )))
        add(toolDef(TOOL_GET_JOIN_REQUESTS, "拉取入群申请列表。机器人需为群管理员。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）",
            "cursor" to "分页游标（可选）",
            "limit" to "每页数量（可选，默认20）"
        )))
        add(toolDef(TOOL_APPROVE_JOIN_REQUEST, "审批入群申请。approve=通过，decline=拒绝。机器人需为群管理员。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）",
            "member_openid" to "申请人 OpenID",
            "join_request_id" to "申请 ID",
            "approve" to "true=通过，false=拒绝",
            "reject_reason" to "拒绝理由（仅 decline 时，可选）",
            "blacklist" to "是否同时拉黑（仅 decline 时，可选）"
        )))
        add(toolDef(TOOL_GET_MUTE_STATUS, "查询当前群禁言状态（全员禁言配置、被禁言成员列表）。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）"
        )))
        add(toolDef(TOOL_SET_MEMBER_MUTE, "设置群成员禁言。add=禁言，del=解除禁言。最大禁言30天。到期时间由服务端计算，禁言时长用 minutes 参数指定。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）",
            "member_openid" to "成员 OpenID",
            "op" to "操作：add=禁言，del=解除",
            "minutes" to "禁言时长（分钟，1-43200，仅 add，默认1）"
        )))
        add(toolDef(TOOL_LIST_AUTO_APPROVE_POLICIES, "查询入群自动审批策略列表。", mapOf(
            "cursor" to "分页游标（可选）",
            "limit" to "每页数量（可选，默认20）"
        )))
        add(toolDef(TOOL_CREATE_AUTO_APPROVE_POLICY, "创建入群自动审批策略。最多20个策略。", mapOf(
            "group_openids" to "关联群 OpenID 列表（与 group_ids 二选一）",
            "group_ids" to "关联 QQ 群号列表（与 group_openids 二选一）",
            "enable" to "是否启用（默认 true）",
            "remark" to "策略备注（可选）"
        )))
        add(toolDef(TOOL_UPDATE_AUTO_APPROVE_POLICY, "修改入群自动审批策略（启用/停用/增删关联群）。", mapOf(
            "strategy_id" to "策略 ID",
            "enable" to "是否启用（可选）",
            "remark" to "备注（可选）",
            "group_action" to "关联群操作 JSON（可选）：{op:'add'/'del', group_openids:[...]} 或 {op:'add'/'del', group_ids:[...]}"
        )))
        add(toolDef(TOOL_DELETE_AUTO_APPROVE_POLICY, "删除入群自动审批策略。", mapOf(
            "strategy_id" to "策略 ID"
        )))
        add(toolDef(TOOL_EXECUTE_AUTO_APPROVE_POLICY, "执行入群自动审批策略，对关联群命中白名单的申请自动通过（异步约10分钟）。", mapOf(
            "strategy_id" to "策略 ID"
        )))
        add(toolDef(TOOL_UPDATE_WHITELIST_USERS, "修改入群自动审批策略的白名单 QQ 号码。单次最多10000个，上限10万。", mapOf(
            "strategy_id" to "策略 ID",
            "op" to "操作：add=新增，del=删除",
            "users" to "QQ 号码列表（字符串数组）"
        )))
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

    // ── 共享辅助（聊天 AI / Agent 通用） ──

    /** web_search 工具定义（ChatAiManager 复用）。 */
    fun webSearchTool(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_WEB_SEARCH)
            put("description", "联网搜索。需要在配置中启用搜索 provider。")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string"); put("description", "搜索关键词")
                    })
                })
                put("required", JSONArray().apply { add("query") })
            })
        })
    }

    /** get_server_status 工具定义（聊天 AI 复用，只读）。 */
    fun serverStatusTool(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_GET_SERVER_STATUS)
            put("description", "查询服务器实时状态（性能诊断、在线人数）。只读，不执行任何操作。")
            put("parameters", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
    }

    /** get_online_players 工具定义（聊天 AI 复用，只读）。 */
    fun onlineListTool(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_GET_ONLINE_LIST)
            put("description", "查询当前在线玩家列表。只读。")
            put("parameters", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
    }

    /** 服务器状态文本（get_server_status 输出）。 */
    fun getServerStatusText(plugin: HuHoBot): String {
        val diagnostics = plugin.getServerDiagnostics()
        val online = plugin.getOnlineList()
        return buildString {
            appendLine(diagnostics ?: "诊断信息不可用（当前平台未实现）")
            appendLine("在线玩家(${online.size}): ${online.joinToString("、").ifBlank { "无" }}")
        }.trim()
    }

    /** 定时任务工具执行（返回文本）。 */
    fun executeTimedTaskTool(name: String, args: JSONObject): String = when (name) {
        TOOL_CREATE_TIMED_TASK -> createTimedTask(args)
        TOOL_DELETE_TIMED_TASK -> {
            val ok = agent.tasks.TimedTaskManager.remove(args.getString("name").orEmpty().trim())
            if (ok) "定时任务已删除" else "未找到该名称的定时任务"
        }
        else -> "未知定时任务操作"
    }

    private fun createTimedTask(args: JSONObject): String {
        val taskName = args.getString("name")?.trim().orEmpty()
        if (taskName.isEmpty()) return "任务名不能为空"
        val cron = args.getString("cron")?.trim()?.takeIf(String::isNotEmpty)
        val interval = args.getLong("interval_seconds") ?: 0L
        if (cron == null && interval <= 0) return "必须提供 cron 或 interval_seconds 之一"
        val message = args.getString("message").orEmpty()
        val command = args.getString("command").orEmpty()
        if (message.isBlank() && command.isBlank()) return "message 与 command 至少提供一个"
        val ok = agent.tasks.TimedTaskManager.upsert(
            agent.tasks.TimedTaskManager.Task(
                name = taskName, cron = cron, intervalSeconds = interval,
                group = args.getString("group").orEmpty().trim(),
                message = message, command = command
            )
        )
        return if (ok) {
            "定时任务「$taskName」已创建并生效。${if (command.isNotBlank()) "注意：命令执行会过黑名单。" else ""}"
        } else "创建失败（无法写入 timed-tasks.yml）"
    }

    // ── QQ 群管理工具执行方法 ──

    fun getGroupInfo(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = query.getString("group_openid")?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultGroupOpenId
        if (groupOpenId.isEmpty()) return ToolResult("请指定 group_openid。")
        return try {
            val info = GroupManagementApi.getGroupInfo(starter, groupOpenId)
            val text = "群名: ${info.getString("group_name")}\n" +
                "成员数: ${info.getIntValue("group_member_num")}\n" +
                "分类: ${info.getString("group_class_text")}\n" +
                "简介: ${info.getString("group_finger_memo")}\n" +
                "标签: ${info.getJSONArray("group_tags")?.joinToString(", ") { it.toString() }}"
            ToolResult(aiText = text, displayTitle = "群信息", displayContent = text)
        } catch (e: Exception) {
            ToolResult("获取群信息失败: ${e.message}")
        }
    }

    fun getBotState(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = query.getString("group_openid")?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultGroupOpenId
        if (groupOpenId.isEmpty()) return ToolResult("请指定 group_openid。")
        return try {
            val info = GroupManagementApi.getBotState(starter, groupOpenId)
            val text = "角色: ${info.getString("member_role")}\n" +
                "入群时间: ${info.getString("joined_at")}\n" +
                "接收消息设置: ${info.getString("recv_msg_setting")}\n" +
                "主动推送: ${info.getBooleanValue("allow_proactive_msg")}"
            ToolResult(aiText = text, displayTitle = "机器人状态", displayContent = text)
        } catch (e: Exception) {
            ToolResult("获取机器人状态失败: ${e.message}")
        }
    }

    fun getJoinRequests(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = query.getString("group_openid")?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultGroupOpenId
        if (groupOpenId.isEmpty()) return ToolResult("请指定 group_openid。")
        val cursor = query.getString("cursor")?.trim().orEmpty()
        val limit = query.getInteger("limit") ?: 20
        return try {
            val resp = GroupManagementApi.getJoinRequests(starter, groupOpenId, cursor, limit)
            val list = resp.getJSONArray("list")
            if (list == null || list.isEmpty()) {
                return ToolResult("当前没有入群申请。")
            }
            val sb = StringBuilder("入群申请列表（${list.size} 条）：\n")
            for (i in list.indices) {
                val r = list.getJSONObject(i)
                sb.appendLine("${i + 1}. ${r.getString("username")} (${r.getString("member_openid")}) " +
                    "来源: ${r.getString("apply_source")} 时间: ${r.getString("apply_at")}")
            }
            val nextCursor = resp.getString("next_cursor")
            if (nextCursor.isNotEmpty()) sb.appendLine("下一页游标: $nextCursor")
            ToolResult(aiText = sb.toString(), displayTitle = "入群申请", displayContent = sb.toString())
        } catch (e: Exception) {
            ToolResult("获取入群申请列表失败: ${e.message}")
        }
    }

    fun approveJoinRequest(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = query.getString("group_openid")?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultGroupOpenId
        val memberOpenId = query.getString("member_openid")?.trim().orEmpty()
        val joinRequestId = query.getString("join_request_id")?.trim().orEmpty()
        val approve = query.getBoolean("approve") ?: true
        val rejectReason = query.getString("reject_reason")?.trim().orEmpty()
        val blacklist = query.getBoolean("blacklist") ?: false
        if (groupOpenId.isEmpty() || memberOpenId.isEmpty()) return ToolResult("请指定 group_openid 和 member_openid。")
        return try {
            GroupManagementApi.approveJoinRequest(starter, groupOpenId, memberOpenId, joinRequestId, approve, rejectReason, blacklist)
            val action = if (approve) "已通过" else "已拒绝"
            ToolResult(aiText = "入群申请 $action: $memberOpenId", displayTitle = "入群审批", displayContent = "$action $memberOpenId")
        } catch (e: Exception) {
            ToolResult("审批入群申请失败: ${e.message}")
        }
    }

    fun getMuteStatus(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = query.getString("group_openid")?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultGroupOpenId
        if (groupOpenId.isEmpty()) return ToolResult("请指定 group_openid。")
        return try {
            val info = GroupManagementApi.getMuteStatus(starter, groupOpenId)
            val globalRule = info.getJSONObject("global_rule")
            val mode = globalRule?.getString("mode") ?: "none"
            val members = info.getJSONArray("members")
            val sb = StringBuilder("全员禁言模式: $mode\n")
            if (members != null && members.isNotEmpty()) {
                sb.appendLine("被禁言成员（${members.size} 人）：")
                for (i in members.indices) {
                    val m = members.getJSONObject(i)
                    sb.appendLine("- ${m.getString("username")} (${m.getString("member_openid")}) 到期: ${m.getString("mute_expire_at")}")
                }
            } else {
                sb.appendLine("当前无成员被禁言。")
            }
            ToolResult(aiText = sb.toString(), displayTitle = "禁言状态", displayContent = sb.toString())
        } catch (e: Exception) {
            ToolResult("获取禁言状态失败: ${e.message}")
        }
    }

    fun setMemberMute(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = query.getString("group_openid")?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultGroupOpenId
        val memberOpenId = query.getString("member_openid")?.trim().orEmpty()
        val op = query.getString("op")?.trim().orEmpty()
        val minutes = query.getInteger("minutes") ?: 1
        if (groupOpenId.isEmpty() || memberOpenId.isEmpty()) return ToolResult("请指定 group_openid 和 member_openid。")
        if (op !in listOf("add", "del")) return ToolResult("op 必须为 add 或 del。")
        return try {
            GroupManagementApi.setMemberMute(starter, groupOpenId, memberOpenId, op, minutes)
            val action = if (op == "add") "已禁言 ${minutes} 分钟" else "已解除禁言"
            ToolResult(aiText = "$action: $memberOpenId", displayTitle = "禁言操作", displayContent = "$action $memberOpenId")
        } catch (e: Exception) {
            ToolResult("设置禁言失败: ${e.message}")
        }
    }

    fun listAutoApprovePolicies(starter: Starter, query: JSONObject): ToolResult {
        return try {
            val resp = GroupManagementApi.listAutoApprovePolicies(starter)
            val strategies = resp.getJSONArray("strategies")
            if (strategies == null || strategies.isEmpty()) {
                return ToolResult("当前没有自动审批策略。")
            }
            val sb = StringBuilder("自动审批策略列表（${strategies.size} 条）：\n")
            for (i in strategies.indices) {
                val s = strategies.getJSONObject(i)
                sb.appendLine("${i + 1}. ID: ${s.getString("strategy_id")} 状态: ${s.getString("is_enable")} " +
                    "白名单数: ${s.getIntValue("whitelist_user_count")} 备注: ${s.getString("remark")}")
            }
            ToolResult(aiText = sb.toString(), displayTitle = "自动审批策略", displayContent = sb.toString())
        } catch (e: Exception) {
            ToolResult("获取策略列表失败: ${e.message}")
        }
    }

    fun createAutoApprovePolicy(starter: Starter, query: JSONObject): ToolResult {
        val groupOpenIds = query.getJSONArray("group_openids")?.map { it.toString() } ?: emptyList()
        val groupIds = query.getJSONArray("group_ids")?.map { it.toString() } ?: emptyList()
        val enable = query.getBoolean("enable") ?: true
        val remark = query.getString("remark")?.trim().orEmpty()
        if (groupOpenIds.isEmpty() && groupIds.isEmpty()) return ToolResult("请指定 group_openids 或 group_ids。")
        return try {
            val resp = GroupManagementApi.createAutoApprovePolicy(starter, groupOpenIds, groupIds, enable, remark)
            val id = resp.getString("strategy_id")
            ToolResult(aiText = "策略已创建: $id", displayTitle = "创建策略", displayContent = "策略 ID: $id")
        } catch (e: Exception) {
            ToolResult("创建策略失败: ${e.message}")
        }
    }

    fun updateAutoApprovePolicy(starter: Starter, query: JSONObject): ToolResult {
        val strategyId = query.getString("strategy_id")?.trim().orEmpty()
        if (strategyId.isEmpty()) return ToolResult("请指定 strategy_id。")
        val enable = if (query.containsKey("enable")) query.getBooleanValue("enable") else null
        val remark = if (query.containsKey("remark")) query.getString("remark")?.trim() else null
        val groupAction = if (query.containsKey("group_action")) query.getJSONObject("group_action") else null
        return try {
            GroupManagementApi.updateAutoApprovePolicy(starter, strategyId, enable, remark, groupAction)
            ToolResult(aiText = "策略 $strategyId 已更新", displayTitle = "更新策略", displayContent = "策略 $strategyId 已更新")
        } catch (e: Exception) {
            ToolResult("更新策略失败: ${e.message}")
        }
    }

    fun deleteAutoApprovePolicy(starter: Starter, query: JSONObject): ToolResult {
        val strategyId = query.getString("strategy_id")?.trim().orEmpty()
        if (strategyId.isEmpty()) return ToolResult("请指定 strategy_id。")
        return try {
            GroupManagementApi.deleteAutoApprovePolicy(starter, strategyId)
            ToolResult(aiText = "策略 $strategyId 已删除", displayTitle = "删除策略", displayContent = "策略 $strategyId 已删除")
        } catch (e: Exception) {
            ToolResult("删除策略失败: ${e.message}")
        }
    }

    fun executeAutoApprovePolicy(starter: Starter, query: JSONObject): ToolResult {
        val strategyId = query.getString("strategy_id")?.trim().orEmpty()
        if (strategyId.isEmpty()) return ToolResult("请指定 strategy_id。")
        return try {
            GroupManagementApi.executeAutoApprovePolicy(starter, strategyId)
            ToolResult(aiText = "策略 $strategyId 已开始执行（约10分钟完成）",
                displayTitle = "执行策略", displayContent = "策略 $strategyId 已开始执行，约10分钟完成。")
        } catch (e: Exception) {
            ToolResult("执行策略失败: ${e.message}")
        }
    }

    fun updateWhitelistUsers(starter: Starter, query: JSONObject): ToolResult {
        val strategyId = query.getString("strategy_id")?.trim().orEmpty()
        val op = query.getString("op")?.trim().orEmpty()
        val users = query.getJSONArray("users")?.map { it.toString() } ?: emptyList()
        if (strategyId.isEmpty()) return ToolResult("请指定 strategy_id。")
        if (op !in listOf("add", "del")) return ToolResult("op 必须为 add 或 del。")
        if (users.isEmpty()) return ToolResult("请指定 users 列表。")
        return try {
            val resp = GroupManagementApi.updateWhitelistUsers(starter, strategyId, op, users)
            val count = resp.getIntValue("whitelist_user_count")
            ToolResult(aiText = "白名单已更新，当前 ${count} 个号码",
                displayTitle = "白名单更新", displayContent = "当前白名单号码数: $count")
        } catch (e: Exception) {
            ToolResult("更新白名单失败: ${e.message}")
        }
    }

    private fun toolDef(name: String, desc: String, params: Map<String, String>): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        params.forEach { (k, v) ->
                            put(k, JSONObject().apply {
                                put("type", "string")
                                put("description", v)
                            })
                        }
                    })
                })
            })
        }
    }
}
