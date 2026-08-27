package cn.huohuas001.bot.web

import com.alibaba.fastjson.JSON

/**
 * WebUI 配置表单的字段定义。
 *
 * @property path    配置键的 dotted path（如 "bot.app-id"）
 * @property label   前端展示的中文标签
 * @property type    控件类型：text / password / number / boolean / select / list / textarea / boolean-map / object-list
 * @property description 字段说明（前端灰字提示）
 * @property options select 类型的可选项
 * @property placeholder 输入框占位提示
 * @property fields  object-list 类型的子字段定义
 */
data class FieldSpec(
    val path: String,
    val label: String,
    val type: String,
    val description: String = "",
    val options: List<String> = emptyList(),
    val placeholder: String = "",
    val fields: List<FieldSpec> = emptyList()
)

/** WebUI 配置表单的分节定义。 */
data class SectionSpec(
    val key: String,
    val title: String,
    val fields: List<FieldSpec>
)

/** 生成前端所需的完整配置表单 schema。 */
object WebUiSchema {

    private val BOT_SECTION = SectionSpec(
        key = "bot",
        title = "QQ 机器人",
        fields = listOf(
            FieldSpec("bot.app-id", "AppID", "text", "QQ 开放平台机器人 AppID", placeholder = "102123456"),
            FieldSpec("bot.secret", "Secret", "password", "QQ 开放平台机器人密钥"),
            FieldSpec("bot.name", "机器人名称", "text", "机器人显示的昵称"),
            FieldSpec("bot.groups", "群列表", "list", "允许机器人工作的群 OpenID（每行一个）"),
            FieldSpec("bot.suppress-console-output", "屏蔽 SDK 控制台输出", "boolean", "关闭 QQ Bot SDK 直接写入 System.out 的调试输出")
        )
    )

    private val SERVER_SECTION = SectionSpec(
        key = "server",
        title = "服务器",
        fields = listOf(
            FieldSpec("serverName", "服务器名称", "text", "用于玩家事件消息中的 {server} 占位符"),
            FieldSpec("command-sender", "命令执行器", "select", "Hybrid=混合控制台；其他=模拟控制台", options = listOf("Hybrid", "Console"))
        )
    )

    private val CHAT_FORMAT_SECTION = SectionSpec(
        key = "chat-format",
        title = "聊天格式",
        fields = listOf(
            FieldSpec("chat-format.from-game", "游戏→群 格式", "text", "占位符：{name} {message}", placeholder = "[游戏] {message}"),
            FieldSpec("chat-format.from-group", "群→游戏 格式", "text", "占位符：{name} {message}", placeholder = "[QQ] {name}: {message}"),
            FieldSpec("chat-format.post-chat", "转发游戏聊天", "boolean", "是否把游戏聊天转发到群"),
            FieldSpec("chat-format.start-with", "触发前缀", "text", "游戏消息必须以此前缀开头才转发；留空转发全部")
        )
    )

    private val PLAYER_EVENTS_SECTION = SectionSpec(
        key = "player-events",
        title = "玩家事件",
        fields = listOf(
            FieldSpec("player-events.join.enabled", "进服通知", "boolean", "玩家进服时在群内发送通知"),
            FieldSpec("player-events.join.format", "进服格式", "text", "占位符：{name}", placeholder = "[游戏] {name} 加入了服务器"),
            FieldSpec("player-events.quit.enabled", "退服通知", "boolean", "玩家退服时在群内发送通知"),
            FieldSpec("player-events.quit.format", "退服格式", "text", "占位符：{name}", placeholder = "[游戏] {name} 离开了服务器")
        )
    )

    private val MARKDOWN_SECTION = SectionSpec(
        key = "markdown",
        title = "Markdown",
        fields = listOf(
            FieldSpec("markdown.queryOnline", "在线查询模板", "text", "Markdown 目录下的模板文件名", placeholder = "online.md")
        )
    )

    private val MOTD_SECTION = SectionSpec(
        key = "motd",
        title = "MOTD",
        fields = listOf(
            FieldSpec("motd.server-ip", "服务器 IP", "text", "用于 MOTD 查询显示的服务器地址", placeholder = "127.0.0.1"),
            FieldSpec("motd.server-port", "服务器端口", "number", "用于 MOTD 查询的端口"),
            FieldSpec("motd.api", "MOTD API 地址", "text", "可选：自定义 MOTD 查询接口"),
            FieldSpec("motd.text", "MOTD 文本", "textarea", "默认展示的 MOTD 内容"),
            FieldSpec("motd.post-img", "发送图片", "boolean", "MOTD 结果附带图片"),
            FieldSpec("motd.use-markdown", "使用 Markdown", "boolean", "MOTD 结果使用 Markdown 卡片")
        )
    )

    private val WHITELIST_SECTION = SectionSpec(
        key = "whitelist",
        title = "白名单命令",
        fields = listOf(
            FieldSpec("whitelist.add-command", "添加命令", "text", "占位符：{name}", placeholder = "whitelist add {name}"),
            FieldSpec("whitelist.del-command", "删除命令", "text", "占位符：{name}", placeholder = "whitelist remove {name}")
        )
    )

    private val FILTER_SECTION = SectionSpec(
        key = "filter-regex",
        title = "正则过滤",
        fields = listOf(
            FieldSpec("filter-regex", "过滤正则列表", "list", "命中这些正则的消息会被替换为 *（每行一条）")
        )
    )

    private val ADMIN_SECTION = SectionSpec(
        key = "admin",
        title = "管理员",
        fields = listOf(
            FieldSpec("admin.mode", "管理员模式", "select", "qq=仅群主/群管理；config=仅配置文件指定；both=两者皆可", options = listOf("both", "qq", "config")),
            FieldSpec("admin.openids", "管理员 OpenID", "list", "配置文件指定的管理员 OpenID（每行一个）")
        )
    )

    private val FEATURES_SECTION = SectionSpec(
        key = "features",
        title = "功能",
        fields = listOf(
            FieldSpec("features.full-amount", "全量转发", "boolean", "默认全量处理所有消息"),
            FieldSpec("features.enable-auth", "头像认证", "boolean", "是否启用 QQ 头像认证功能")
        )
    )

    private val AUDIT_SECTION = SectionSpec(
        key = "audit",
        title = "内容审核",
        fields = listOf(
            FieldSpec("audit.base-url", "审核接口地址", "text", "OpenAI 兼容审核服务地址；留空仅本地敏感词首检"),
            FieldSpec("audit.api-key", "审核 API Key", "password", "审核服务密钥"),
            FieldSpec("audit.model", "审核模型", "text", "审核使用的模型名", placeholder = "gpt-4o-mini")
        )
    )

    private val AGENT_SECTION = SectionSpec(
        key = "agent",
        title = "AI Agent",
        fields = listOf(
            FieldSpec("agent.enabled", "启用 Agent", "boolean", "关闭后 /agent 命令不可用"),
            FieldSpec("agent.base-url", "接口地址", "text", "OpenAI 兼容接口地址"),
            FieldSpec("agent.api-key", "接口密钥", "password", "AI 接口密钥"),
            FieldSpec("agent.model", "模型名", "text", "使用的模型", placeholder = "gpt-4o-mini"),
            FieldSpec("agent.command-mode", "命令执行模式", "select", "manual=手动审批；auto=自动执行", options = listOf("manual", "auto"))
        )
    )

    private val COMMANDS_SECTION = SectionSpec(
        key = "commands",
        title = "命令开关",
        fields = listOf(
            FieldSpec("commands", "命令开关", "boolean-map", "逐个开关机器人在群内的可用命令")
        )
    )

    private val CUSTOM_COMMANDS_SECTION = SectionSpec(
        key = "custom-commands",
        title = "自定义命令",
        fields = listOf(
            FieldSpec("custom-commands", "自定义命令列表", "object-list", "配置机器人自定的命令（key=触发词，command=执行的服务器命令，permission=所需权限等级）",
                fields = listOf(
                    FieldSpec("key", "触发词", "text"),
                    FieldSpec("command", "执行命令", "text"),
                    FieldSpec("permission", "权限等级", "number")
                )
            )
        )
    )

    private val BINDING_SECTION = SectionSpec(
        key = "binding",
        title = "绑定",
        fields = listOf(
            FieldSpec("binding.require-game-verification", "游戏内验证", "boolean", "绑定时是否需要游戏内 /qqbind 验证；关闭时直接绑定无需游戏内操作")
        )
    )

    private val COMMAND_BLACKLIST_SECTION = SectionSpec(
        key = "command-blacklist",
        title = "命令黑名单",
        fields = listOf(
            FieldSpec("command-blacklist", "禁止执行的命令", "list", "禁止通过 /执行 运行的服务器命令（不区分大小写，每行一个，如 op、deop）")
        )
    )

    /** 全部配置分节，按此顺序渲染。 */
    val SECTIONS: List<SectionSpec> = listOf(
        BOT_SECTION,
        SERVER_SECTION,
        CHAT_FORMAT_SECTION,
        PLAYER_EVENTS_SECTION,
        MARKDOWN_SECTION,
        MOTD_SECTION,
        WHITELIST_SECTION,
        FILTER_SECTION,
        ADMIN_SECTION,
        FEATURES_SECTION,
        AUDIT_SECTION,
        AGENT_SECTION,
        COMMANDS_SECTION,
        CUSTOM_COMMANDS_SECTION,
        BINDING_SECTION,
        COMMAND_BLACKLIST_SECTION
    )

    /** 序列化为前端可用的 JSON（sections 数组）。 */
    fun toJson(): String = JSON.toJSONString(SECTIONS)
}