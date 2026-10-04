package cn.huohuas001.bot.web

import com.alibaba.fastjson.JSON

/**
 * WebUI 配置表单的字段定义。
 *
 * @property path    配置键的 dotted path（如 "bot.app-id"）
 * @property label   前端展示的中文标签
 * @property type    控件类型：text / password / number / boolean / select / list / textarea / command-map / object-list
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

/**
 * WebUI 配置表单的分节定义。
 *
 * @property description 分节介绍，手写在这里；不要自动拼接字段说明
 */
data class SectionSpec(
    val key: String,
    val title: String,
    val description: String = "",
    val fields: List<FieldSpec>
)

/** 生成前端所需的完整配置表单 schema。 */
object WebUiSchema {

    private val BOT_SECTION = SectionSpec(
        key = "bot",
        title = "QQ 机器人",
        description = "机器人的身份凭据与工作范围。首次部署可先留空，启动后用控制台或 WebUI 扫码自动填入 AppID 与 Secret。" +
            "群列表留空表示不限制；开启自动收录后，陌生群里的消息会把该群 OpenID 记进来。",
        fields = listOf(
            FieldSpec("bot.app-id", "AppID", "text", "QQ 开放平台机器人 AppID", placeholder = "102123456"),
            FieldSpec("bot.secret", "Secret", "password", "QQ 开放平台机器人密钥"),
            FieldSpec("bot.name", "机器人名称", "text", "机器人显示的昵称"),
            FieldSpec("bot.groups", "群列表", "list", "允许机器人工作的群 OpenID（每行一个）"),
            FieldSpec("bot.suppress-console-output", "屏蔽 SDK 控制台输出", "boolean", "关闭 QQ Bot SDK 直接写入 System.out 的调试输出"),
            FieldSpec("bot.auto-add-groups", "自动收录陌生群", "boolean", "收到未配置群的 QQ 消息时，自动把群 OpenID 写入群列表")
        )
    )

    private val UPDATE_CHECK_SECTION = SectionSpec(
        key = "update-check",
        title = "版本更新",
        description = "启动时后台检查一次新版，/版本 命令会再检查一次。只认 GitHub 正式 Release，Pre-Release 不计入。",
        fields = listOf(
            FieldSpec("update-check.enabled", "检查新版本", "boolean", "启动时后台检查一次，/版本 命令会再检查一次；只认 GitHub 正式 Release"),
            FieldSpec("update-check.url", "自定义数据源", "text", "逗号分隔的 URL，可返回纯文本版本号或 JSON；留空使用内置数据源")
        )
    )

    private val PLACEHOLDER_API_SECTION = SectionSpec(
        key = "placeholder-api",
        title = "PlaceholderAPI",
        description = "装了就生效，没装也不影响。开启后配置里的 %占位符% 交给 PlaceholderAPI 解析，未安装时原样保留。",
        fields = listOf(
            FieldSpec("placeholder-api.enabled", "启用占位符解析", "boolean", "安装 PlaceholderAPI 后，配置中的 %占位符% 交由它解析；未安装时原样保留")
        )
    )

    private val SERVER_SECTION = SectionSpec(
        key = "server",
        title = "服务器",
        description = "与本服务器身份相关的设置。服务器名称会出现在进服、退服、死亡等播报里；WebUI 端口与管理密码只在本机生效，修改后需重启。",
        fields = listOf(
            FieldSpec("serverName", "服务器名称", "text", "用于玩家事件消息中的 {server} 占位符"),
            FieldSpec("webui-port", "WebUI 端口", "number", "WebUI 管理界面端口，修改后需重启生效"),
            FieldSpec("command-sender", "命令执行器", "select", "Hybrid=混合控制台；其他=模拟控制台", options = listOf("Hybrid", "Console"))
        )
    )

    private val CHAT_FORMAT_SECTION = SectionSpec(
        key = "chat-format",
        title = "聊天格式",
        description = "控制游戏与 QQ 群之间的聊天互通。触发前缀是常用手段：填 # 后只有以 # 开头的游戏消息才会被转发，可避免刷屏。",
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
        description = "玩家进服、退服、死亡时自动发到 QQ 群的通知。死亡播报里的 {message} 是自动生成的中文死亡描述，" +
            "例如「Steve 被苦力怕杀死了」「Steve 从高处摔落而死」，不是游戏原版的英文。",
        fields = listOf(
            FieldSpec("player-events.join.enabled", "进服通知", "boolean", "玩家进服时在群内发送通知"),
            FieldSpec("player-events.join.format", "进服格式", "text", "占位符：{name}", placeholder = "[游戏] {name} 加入了服务器"),
            FieldSpec("player-events.quit.enabled", "退服通知", "boolean", "玩家退服时在群内发送通知"),
            FieldSpec("player-events.quit.format", "退服格式", "text", "占位符：{name}", placeholder = "[游戏] {name} 离开了服务器"),
            FieldSpec("player-events.death.enabled", "死亡播报", "boolean", "玩家死亡时在群内播报死亡描述"),
            FieldSpec("player-events.death.format", "死亡播报格式", "text", "占位符：{name}、{message}（中文死亡描述）", placeholder = "[游戏] {message}")
        )
    )

    private val MARKDOWN_SECTION = SectionSpec(
        key = "markdown",
        title = "Markdown",
        description = "查在线等命令使用的卡片模板，放在插件目录的 Markdown 文件夹里，可自行编辑排版与图片。",
        fields = listOf(
            FieldSpec("markdown.queryOnline", "在线查询模板", "text", "Markdown 目录下的模板文件名", placeholder = "online.md")
        )
    )

    private val MOTD_SECTION = SectionSpec(
        key = "motd",
        title = "MOTD",
        description = "群里查本服务器信息时返回的内容。IP 与端口用于实际探测在线状态，可留空只展示文本。",
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
        description = "绑定成功与解绑时自动执行的服务器命令。绑定后自动加白、解绑后自动移出；开启强制绑定时这两条不再执行。",
        fields = listOf(
            FieldSpec("whitelist.add-command", "添加命令", "text", "占位符：{name}", placeholder = "whitelist add {name}"),
            FieldSpec("whitelist.del-command", "删除命令", "text", "占位符：{name}", placeholder = "whitelist remove {name}")
        )
    )

    private val FILTER_SECTION = SectionSpec(
        key = "filter-regex",
        title = "正则过滤",
        description = "命中正则的片段会被替换成 *，适合屏蔽脏话、广告或不想同步的内容（每行一条正则）。",
        fields = listOf(
            FieldSpec("filter-regex", "过滤正则列表", "list", "命中这些正则的消息会被替换为 *（每行一条）")
        )
    )

    private val ADMIN_SECTION = SectionSpec(
        key = "admin",
        title = "管理员",
        description = "谁能用加管理、执行命令等敏感指令。群主与群管理天然有权限，也可以在这里手动追加管理员 OpenID。",
        fields = listOf(
            FieldSpec("admin.mode", "管理员模式", "select", "qq=仅群主/群管理；config=仅配置文件指定；both=两者皆可", options = listOf("both", "qq", "config")),
            FieldSpec("admin.openids", "管理员 OpenID", "list", "配置文件指定的管理员 OpenID（每行一个）")
        )
    )

    private val FEATURES_SECTION = SectionSpec(
        key = "features",
        title = "功能",
        description = "整体功能开关。全量转发默认开启，关闭后只处理与本机器人直接相关的消息。",
        fields = listOf(
            FieldSpec("features.full-amount", "全量转发", "boolean", "默认全量处理所有消息"),
            FieldSpec("features.enable-auth", "头像认证", "boolean", "是否启用 QQ 头像认证功能")
        )
    )

    private val AUDIT_SECTION = SectionSpec(
        key = "audit",
        title = "内容审核",
        description = "转发前的内容审核。填入 OpenAI 兼容接口后会先做本地敏感词检测、再送审；留空则只做本地检测。",
        fields = listOf(
            FieldSpec("audit.base-url", "审核接口地址", "text", "OpenAI 兼容审核服务地址；留空仅本地敏感词首检"),
            FieldSpec("audit.api-key", "审核 API Key", "password", "审核服务密钥"),
            FieldSpec("audit.model", "审核模型", "text", "审核使用的模型名", placeholder = "gpt-4o-mini")
        )
    )

    private val AGENT_SECTION = SectionSpec(
        key = "agent",
        title = "AI Agent",
        description = "让群友用自然语言指挥服务器（/agent）。可查插件、执行命令等；命令执行建议保持手动审批，避免误操作。",
        fields = listOf(
            FieldSpec("agent.enabled", "启用 Agent", "boolean", "关闭后 /agent 命令不可用"),
            FieldSpec("agent.base-url", "接口地址", "text", "OpenAI 兼容接口地址"),
            FieldSpec("agent.api-key", "接口密钥", "password", "AI 接口密钥"),
            FieldSpec("agent.model", "模型名", "text", "使用的模型", placeholder = "gpt-4o-mini"),
            FieldSpec("agent.command-mode", "命令执行模式", "select", "manual=手动审批；auto=自动执行", options = listOf("manual", "auto")),
            FieldSpec("agent.hide-fetch-results", "不在群聊输出获取类结果", "boolean", "开启后获取插件列表、命令帮助、服务器日志等结果只交给 AI，不在群里发卡片")
        )
    )

    private val COMMANDS_SECTION = SectionSpec(
        key = "commands",
        title = "命令与 QQ 面板",
        description = "逐条控制命令是否可用、是否推送到 QQ 指令面板。面板最多 20 个位置，优先级数字越小越靠前。",
        fields = listOf(
            FieldSpec("command-panel.show-admin-commands", "管理员命令对所有人显示", "boolean", "只影响 QQ 面板可见性；执行管理员命令时仍严格验证权限"),
            FieldSpec("commands", "命令设置", "command-map", "启用控制能否执行；面板控制是否推送；优先级越小越先占用 20 个面板名额")
        )
    )

    private val CUSTOM_COMMANDS_SECTION = SectionSpec(
        key = "custom-commands",
        title = "自定义命令",
        description = "自己给机器人加指令：群友发送触发词，机器人代为执行对应的服务器命令。权限等级填 0 表示所有人可用。",
        fields = listOf(
            FieldSpec("custom-commands", "自定义命令列表", "object-list", "配置机器人自定的命令（key=触发词，command=执行的服务器命令，permission=所需权限等级）",
                fields = listOf(
                    FieldSpec("key", "触发词", "text"),
                    FieldSpec("command", "执行命令", "text"),
                    FieldSpec("permission", "权限等级", "number"),
                    FieldSpec("pushMenu", "推送面板", "boolean", "是否同步到 QQ 命令面板")
                )
            )
        )
    )

    private val BINDING_SECTION = SectionSpec(
        key = "binding",
        title = "绑定",
        description = "把 QQ 账号和 MC 玩家名关联起来，用于查背包与末影箱。绑定以 openid 为准，在一个群绑定后本服务器所有群都通用。" +
            "开启强制绑定后，未绑定玩家进服务器会被踢出并拿到验证码，须在 QQ 群执行 /绑定 <验证码> 才能进入；" +
            "启用强制绑定时不要再叠加白名单插件或 Minecraft 自带白名单。",
        fields = listOf(
            FieldSpec("binding.require-game-verification", "游戏内验证", "boolean", "绑定时是否需要游戏内 /qqbind 验证；关闭时直接绑定无需游戏内操作。强制绑定下被踢出的玩家用验证码直接绑定，但免验证玩家主动绑定时仍由本项决定"),
            FieldSpec("binding.force-bind", "强制绑定", "boolean", "未绑定的玩家进服务器会被踢出并拿到 5 位验证码，必须先在 QQ 群执行 /绑定 <验证码> 才能进入。若要开启，请勿再叠加白名单插件或 Minecraft 自带白名单"),
            FieldSpec("binding.force-bind-groups", "强制绑定 QQ 群号", "list", "强制绑定提示里展示的 QQ 群号，可填多个；留空则不提示具体群号"),
            FieldSpec("binding.verify-exempt", "免验证名单", "list", "这些玩家无需 QQ 绑定即可进入服务器，适用于受限于设备或环境无法使用 QQ 的人员，由管理员人工审查后添加（也可在 QQ 群用 /添加免验证 <玩家名>）")
        )
    )

    private val COMMAND_BLACKLIST_SECTION = SectionSpec(
        key = "command-blacklist",
        title = "命令黑名单",
        description = "禁止通过 /执行 运行的服务器命令，名单内的命令群里任何人都触发不了（每行一个，如 op、deop）。",
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
        COMMAND_BLACKLIST_SECTION,
        UPDATE_CHECK_SECTION,
        PLACEHOLDER_API_SECTION
    )

    /** 序列化为前端可用的 JSON（sections 数组）。 */
    fun toJson(): String = JSON.toJSONString(SECTIONS)
}
