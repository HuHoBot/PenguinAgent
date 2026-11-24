# HuHoBot Penguin 1.24 配置与使用完整手册

本手册面向从零开始搭建与维护服务器的管理员，涵盖 config.yml 的每一个配置项、每个模块的作用、默认值、取值范围、常见问题与排错方法。
本文件由插件首次启动生成；如你修改了它，插件会保留你的版本，并把新版本写为 Q&A.md.new 放在旁边，不会覆盖你的编辑。

---

## 目录

1. 从零开服速查
2. 配置文件结构与路径
3. config.yml 逐项说明
4. QQ 群聊命令面板说明
5. QQ 平台限制与被动回复额度
6. 图片生成与渲染说明
7. 排错速查与常见问题
8. 扩展（addons）编写与使用
9. 重载、升级与备份
10. 版本验证与生产环境注意事项

---

## 1. 从零开服速查

1. 准备 Java 17 或更高版本的服务器核心（Paper、Purpur、Leaf、Folia 等均可）。
2. 把 HuHoBot-Penguin-1.24.jar 放入服务器 plugins/ 目录，启动一次服务器。
3. 确认生成 plugins/HuHoBotPenguin/ 目录，其中包含 config.yml、data/ 等。
4. 在 QQ 开放平台创建机器人，取得 AppID 与 Secret，填入 config.yml 的 bot 段；也可留空，启动后在控制台或 WebUI 扫码授权。
5. 把机器人拉进要管理的 QQ 群，群 openid 可留空（首次收消息会自动收录），或手动填入 bot.groups。
6. 执行 /huhobot reload 应用配置；在群里发 /查在线 验证图片是否正常生成。
7. 如需 AI 功能，再填写 agent.* 或 chat-ai.* 的接口地址、密钥与模型名。

---

## 2. 配置文件结构与路径

| 路径（相对 plugins/HuHoBotPenguin/） | 用途 | 能否删除 |
|---|---|---|
| config.yml | 主配置文件，所有功能开关与参数 | 不能；可备份后重建 |
| data/Q&A.md | 本手册 | 可删，重启再生成 |
| data/Q&A.md.new | 插件检测到新手册版本时写入的新版本 | 可删，或手动合并 |
| data/guide/ | 权限组/定时任务/扩展示例，不会自动启用 | 可删 |
| data/inventory/backgrounds/ | 背包/末影箱自定义背景图片存放目录 | 可删 |
| data/inventory/snapshots/ | 离线背包只读快照 | 不要手动修改 |
| data/logs/ | QQ SDK 与内容日志 | 可归档，不要外发 |
| data/sensitive-words/*.txt | 本地违禁词词表 | 可自行编辑 |
| addons/ | 小型扩展目录，一目录一扩展 | 无扩展时可删 |

重要：配置文件与运行数据分开。不要在 data/ 里找 config.yml，也不要手工把 data/ 里的数据库文件复制给他人（包含 openid 与绑定关系等隐私）。

---

## 3. config.yml 逐项说明

下面按文件中的顶级顺序，逐一说明每个配置段及其子项。

### 3.1 config-version / serverName

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| config-version | 整数 | 19 | 配置结构版本号。插件会据此自动补齐缺失的默认项、更新已弃用键。不要手动改小此值，否则可能导致新增配置项无法补齐。 |
| serverName | 字符串 | HuHoBot | 服务器名称，显示在查在线图片标题、入服提示和服务器通知中。支持 MiniMessage（如 <gradient:#22ccee:#aa66ee>名称</gradient>）、§ 与 & 颜色码。以 &、#、: 开头的值必须加单引号，例如 serverName: '&a我的服务器'，否则 YAML 会将 & 误认为锚点。 |

### 3.2 bot：QQ 机器人凭据与群列表

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| bot.app-id | 字符串 |  | QQ 开放平台机器人 AppID。与 secret 配套使用。留空时 QQ 客户端不会启动，但 WebUI 仍可打开。 |
| bot.secret | 字符串 |  | QQ 机器人 Secret，与 AppID 配套使用。不要将原始 secret 发布到公开渠道。 |
| bot.name | 字符串 | HuHoBot | 机器人显示名称，用于部分播报与 WebUI 显示。 |
| bot.groups | 字符串列表 | [] | 机器人允许工作的群 OpenID 列表。留空表示不限制群（首次收到消息会自动收录该群）。填写时每项单独一行。 |
| bot.suppress-console-output | 布尔 | true | 是否过滤 QQ SDK 的控制台噪声日志。排障时可临时关闭，但不要把带 token 的原始日志发到群里。 |
| bot.auto-add-groups | 布尔 | true | 新群是否自动加入 bot.groups。开启后，机器人首次在未配置的群收到消息时会自动收录该群 openid。 |

### 3.3 chat-format：游戏与 QQ 聊天格式

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| chat-format.from-game | 字符串 | [游戏] {name}: {message} | 游戏消息转发到 QQ 群的格式模板，支持 {name}、{message} 等占位符。 |
| chat-format.from-group | 字符串 | [QQ] {name}: {message} | QQ 群消息转发到游戏的格式模板，支持 {name}、{message} 等占位符。 |
| chat-format.post-chat | 布尔 | true | 是否启用聊天互通（游戏<->QQ 群）。false 后不再转发聊天消息。 |
| chat-format.start-with | 字符串 | # | 游戏内消息触发前缀，按此用途填写，未使用时保持默认值。 |
| chat-format.strip-colour-codes | 布尔 | true | QQ Markdown 不支持颜色码；开启后转发到 QQ 群前去掉 §/& 代码，避免群里出现原始标记。 |

### 3.4 player-events：入群/离开/死亡播报

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| player-events.join.enabled | 布尔 | true | 是否启用玩家加入服务器的群播报。 |
| player-events.join.format | 字符串 | [游戏] {name} 加入了服务器 | 加入播报的消息格式模板，支持 {name}。 |
| player-events.quit.enabled | 布尔 | true | 是否启用玩家离开服务器的群播报。 |
| player-events.quit.format | 字符串 | [游戏] {name} 离开了服务器 | 离开播报的消息格式模板，支持 {name}。 |
| player-events.death.enabled | 布尔 | true | 是否启用玩家死亡的群播报。 |
| player-events.death.format | 字符串 | [游戏] {name} {message} | 死亡播报的消息格式模板，支持 {name} 与 {message}。 |
| player-events.death.use-plugin-message | 布尔 | false | 开启后，采用其它死亡信息插件写入事件的文本；关闭时使用插件内置的中文死亡原因描述。 |

### 3.5 features：额外功能开关

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| features.full-amount | 布尔 | false | 是否启用全量发送策略，影响部分批量操作的数据量上限。 |

### 3.6 message：消息投递策略

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| message.send-policy | 字符串 | auto | 发送策略：auto 优先被动回复，额度用尽才主动发送；active 始终主动发送（会消耗每日主动额度）。 |
| message.passive-reply.enhanced-groups | 字符串列表 | [] | 仅填已确认符合「机器人开发者在本群为群主或管理员」条件的群 OpenID。这些群每条入站消息最多回复 50 次；未列出的群为 5 次。QQ 平台最终核验资格，配置不能绕过平台限制。 |

### 3.7 moderation：黑名单与内容审核

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| moderation.blacklist | 字符串列表 | [] | QQ 成员黑名单（OpenID 列表），不是违禁词。列表项按 YAML 列表逐行填写；空列表表示不配置。 |
| moderation.block-messages | 布尔 | true | 是否直接忽略黑名单用户的消息。 |
| moderation.sensitive-words.ai-review | 布尔 | false | 是否调用审核 AI 做二次筛查；失败时回退本地词表，不会单独因网络失败拦截。 |
| moderation.sensitive-words.auto-recall | 布尔 | false | 违规 QQ 入站消息命中后自动尝试撤回；需要机器人具备群管理权限，且平台限制两分钟窗口。 |
| moderation.sensitive-words.allow-none | 布尔 | false | 默认关闭。打开后停用内置基础词，只使用 data/sensitive-words/*.txt 中的自定义词。风险由服主承担。 |

### 3.8 online-list：查在线图片与皮肤

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| online-list.title | 字符串 | <gradient:#62d6df:#ad9cff>在线列表</gradient> | 查在线图片顶部标题；支持 MiniMessage、§ 与 & 颜色码。 |
| online-list.enabled | 布尔 | true | 是否启用查在线图片功能。关闭后查在线将回退到 MOTD 外部 API 图片。 |
| online-list.page-size | 整数 | 27 | 每页显示的玩家数量（1~300）。 |
| online-list.max-render-players | 整数 | 27 | 单张图片最多绘制的玩家数；超出名单以文字补充（1~200）。 |
| online-list.display.prefix | 布尔 | true | 是否显示玩家称号前缀。 |
| online-list.display.suffix | 布尔 | true | 是否显示玩家后缀。 |
| online-list.display.admin-groups | 字符串列表 | [] | LuckPerms 管理员组名；组内玩家按管理员样式（金边卡片）显示。 |
| online-list.display.core-name | 字符串 |  | 查在线图片中显示的服务端/核心别名，支持颜色码；留空时自动检测。 |
| online-list.display.prefix-format | 字符串 |  | 前缀模板。内置变量：{name} {display} {world} {ping} {server} {online} {max}；也支持 PlaceholderAPI %占位符%。 |
| online-list.display.suffix-format | 字符串 |  | 后缀模板，内置变量与前缀相同，也支持 PlaceholderAPI %占位符%。 |
| online-list.admin-permission | 字符串 |  | 查在线图片的管理员权限节点。 |
| online-list.cooldown-seconds | 整数 | 3 | 同一群连续查询的最短间隔秒数（0~60），避免刷图。 |
| online-list.skin.enabled | 布尔 | true | 是否加载网络皮肤；关闭后使用内置默认头像。 |
| online-list.skin.offline-default-skins | 布尔 | true | 离线模式（online-mode=false）下默认只用内置皮肤，不向皮肤站请求贴图；已安装皮肤插件时仍会优先显示其皮肤。设为 false 恢复旧行为。 |
| online-list.skin.timeout-ms | 整数 | 5000 | 单个皮肤请求超时毫秒数；失败会回退，不应设得过大（500~15000）。 |
| online-list.skin.cache-size | 整数 | 200 | 头像缓存容量（最小 8）。 |
| online-list.skin.download-threads | 整数 | 4 | 皮肤下载并发数（1~12）；调低可减少低配服务器瞬时网络/CPU 负载。 |
| online-list.skin.sources | 列表 | 见默认配置 | 皮肤/头像来源按列表顺序尝试。每个来源包含 type、url、root（csl 专用）、allow-private。 |
| online-list.image-max-bytes | 整数 | 20971520 | 查在线图片的最大 PNG 字节数（默认 20MB）。 |
| online-list.columns | 整数 | 3 | 查在线图片的玩家列数（1~3）。 |
| online-list.address | 字符串 |  | 图片中展示的服务器地址；留空时不展示，避免意外公开地址。 |
| online-list.custom-lines | 字符串列表 | [] | 查在线图片的自定义信息行；空行不占空间，支持 MiniMessage、§ 与 & 颜色码。最多 6 行。 |
| online-list.footer-text | 字符串 | POWERED BY HuHoBot | 图片底部文字。 |

玩家卡片布局说明：玩家 ID 独占一行（不会因长称号换行或消失），称号与后缀在第二行按宽度省略。

### 3.9 inventory：背包与末影箱图片

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| inventory.render.custom-background.enabled | 布尔 | false | 是否启用自定义背包/末影箱背景。 |
| inventory.render.custom-background.inventory-file | 字符串 | inventory.png | 自定义玩家背包背景文件名，文件放在 data/inventory/backgrounds/。 |
| inventory.render.custom-background.ender-chest-file | 字符串 |  | 自定义末影箱背景文件名；留空时沿用背包背景。 |
| inventory.render.custom-background.fit | 字符串 | cover | 背景适配方式：cover 裁切铺满，stretch 拉伸到画布。 |

单格查询槽位号：
- 背包：0-8 快捷栏，9-35 主背包，36 靴子、37 护腿、38 胸甲、39 头盔、40 副手。
- 末影箱：0-26，每行 9 格。

### 3.10 chat-log：聊天记录图片

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| chat-log.enabled | 布尔 | true | 是否启用 /聊天记录 与 /玩家聊天 图片命令。 |
| chat-log.keep | 整数 | 300 | 内存中保留的最大条数（30~5000）。仅内存、不落盘；重启清零。 |
| chat-log.page-size | 整数 | 30 | 每页目标行数（5~100）；超过 max-length 的消息占两行，因此实际条数会减少。 |
| chat-log.max-length | 整数 | 80 | 单条消息在图片中的最大字符数（20~300），超出部分用省略号。 |
| chat-log.cooldown-seconds | 整数 | 3 | 同一群查询冷却秒数（0~60）。 |


### 3.11 agent：服务器管理 AI 助手

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| agent.enabled | 布尔 | false | 是否启用服务器管理 AI 功能。 |
| agent.base-url | 字符串 |  | 接口基础地址，通常填写到 /v1。 |
| agent.api-key | 字符串 |  | Agent 接口密钥。不要泄露。 |
| agent.model | 字符串 | gpt-4o-mini | 模型名称，需与服务提供方一致。 |
| agent.max-steps | 整数 | 15 | 每个用户请求允许的模型/工具往返轮数（1~100）；审批或休眠恢复不会重置预算。 |
| agent.max-wait-seconds | 整数 | 300 | 单次 sleep 工具允许等待的最长秒数（1~86400）；默认 5 分钟，可改为 7200 支持两小时。等待不占用游戏线程；重启后不恢复。 |
| agent.max-tokens | 整数 | 0 | 请求最大生成 token 数；0 表示不发送 max_tokens，使用上游默认值。 |
| agent.timeout-seconds | 整数 | 120 | 模型请求读超时秒数（30~600），不是任务总时长。 |
| agent.system-prompt | 字符串 |  | 自定义 Agent 身份描述，留空使用默认；工具、权限及当前服务器规则仍会附加。 |
| agent.command-mode | 字符串 | manual | 命令执行模式：manual（手动确认）等。 |
| agent.hide-fetch-results | 布尔 | true | 是否在返回给用户的结果中隐藏工具抓取的原始内容，只保留总结。 |
| agent.think | 布尔 | false | 是否启用原生推理（thinking）。与 think-guide 互斥。 |
| agent.think-visibility | 字符串 | log | 思考内容展示位置。 |
| agent.think-param | 字符串 | reasoning_effort | 思考参数名，需与上游模型 API 对应。 |
| agent.think-level | 字符串 |  | 思考等级，留空使用上游默认。 |
| agent.think-guide | 布尔 | false | 是否启用提示词模拟推理。与 think 互斥。 |
| agent.think-guide-prompt | 长字符串 | 见默认配置 | Agent 的思考与答复行为提示词。修改前请备份；文本会原样传给模型。 |
| agent.context.compress.target | 整数 | 4000 | 上下文摘要目标长度（token）。 |
| agent.context.compress.keep | 整数 | 20 | 压缩后保留的最近对话条数。 |
| agent.context.compress.prompt | 长字符串 | 见默认配置 | 上下文压缩提示词。仅影响模型摘要内容，不会删除配置或存档。 |
| agent.context.truncate.drop | 整数 | 10 | 需要裁剪的最旧对话条数。 |
| agent.tools | 字符串列表 | [] | 工具名白名单，空列表允许全部；例如 [calculate, random_choice, sleep, get_online_list]。 |

### 3.12 chat-ai：群聊 AI

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| chat-ai.enabled | 布尔 | false | 是否启用群聊 AI 功能。 |
| chat-ai.base-url | 字符串 |  | 接口基础地址，通常填写到 /v1。 |
| chat-ai.api-key | 字符串 |  | 群聊 AI 接口密钥。不要泄露。 |
| chat-ai.model | 字符串 | gpt-4o-mini | 模型名称，需与服务提供方一致。 |
| chat-ai.trigger | 字符串 | ai | 群聊 AI 的触发词，群里以该词开头触发 AI 回复。 |
| chat-ai.think | 布尔 | false | 是否启用原生推理。 |
| chat-ai.think-level | 字符串 |  | 思考等级，留空使用上游默认。 |
| chat-ai.think-param | 字符串 | reasoning_effort | 思考参数名。 |
| chat-ai.system-prompt | 字符串 |  | 群聊 AI 的系统提示词。 |
| chat-ai.tools | 字符串列表 | [] | 本功能允许模型调用的工具列表。 |
| chat-ai.max-context | 整数 | 30 | 最大上下文消息数。 |
| chat-ai.compress.target | 整数 | 4000 | 上下文摘要目标长度。 |
| chat-ai.compress.keep | 整数 | 20 | 压缩后保留的最近对话条数。 |

### 3.13 tts：语音合成

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| tts.enabled | 布尔 | false | 是否启用语音合成功能。 |
| tts.base-url | 字符串 | https://api.xiaomimimo.com/v1 | 接口基础地址，通常填写到 /v1。 |
| tts.api-key | 字符串 |  | 语音合成服务密钥；留空时 TTS 不会发起请求。不要泄露。 |
| tts.public-base-url | 字符串 |  | 服务器对外可访问的公网 URL，用于 QQ 拉取语音文件。 |
| tts.format | 字符串 | wav | 音频格式。注意：QQ 官方要求语音为 silk 格式；wav/mp3 在部分平台环境可用，若被拒请配置 silk 转码。 |
| tts.preset-model | 字符串 | mimo-v2.5-tts | 预置语音模型。 |
| tts.natural-model | 字符串 | mimo-v2.5-tts-voicedesign | 自然语音模型。 |
| tts.clone-model | 字符串 | mimo-v2.5-tts-voiceclone | 克隆语音模型。 |
| tts.preset-default-voice | 字符串 | mimo_default | 预置默认音色。 |
| tts.natural-default-voice | 字符串 | 温柔亲切的中文女声 | 自然默认音色描述。 |
| tts.clone-voices-dir | 字符串 | tts-voices | 本地克隆音色目录，相对 data/ 目录。 |
| tts.clone-default-voice | 字符串 |  | 默认克隆音色。 |
| tts.max-per-turn | 整数 | 2 | 每轮最多生成的语音条数。 |


### 3.14 search：联网搜索

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| search.provider | 字符串 | none | 搜索服务商，none 表示禁用。 |
| search.api-url | 字符串 |  | 接口 URL。 |
| search.api-key | 字符串 |  | 搜索服务密钥；留空时搜索功能不可用。不要泄露。 |
| search.result-count | 整数 | 5 | 最多返回的搜索结果条数（1~10）。 |

### 3.15 audit：敏感词 AI 复核

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| audit.base-url | 字符串 |  | 接口基础地址，通常填写到 /v1。 |
| audit.api-key | 字符串 |  | 复核接口密钥（可选）。不要泄露。 |
| audit.model | 字符串 | gpt-4o-mini | 模型名称，需与服务提供方一致。 |

### 3.16 network：插件网络代理

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| network.proxy.enabled | 布尔 | false | 是否启用插件网络代理。 |
| network.proxy.type | 字符串 | http | 代理类型名称。 |
| network.proxy.host | 字符串 |  | 代理主机名或 IP。 |
| network.proxy.port | 整数 | 8080 | 网络端口。 |

### 3.17 storage：运行数据存储

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| storage.type | 字符串 | sqlite | 运行状态存储后端，新安装默认 SQLite。 |

### 3.18 language / debug / webui-port

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| language | 字符串 | zh_CN | 插件消息语言，例如 zh_CN 或 en_US。 |
| debug | 布尔 | false | 输出额外诊断日志；排障后建议关闭，避免日志过大。 |
| webui-port | 整数 | 5678 | WebUI 监听端口。默认只绑定本机回环地址，需要局域网访问时显式改为 0.0.0.0（在平台端配置绑定地址）。 |

### 3.19 command-sender / custom-commands / command-aliases

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| command-sender | 字符串 | Hybrid | 服务器命令执行方式：Hybrid 混合执行器，或 BukkitConsoleSender 模拟控制台。 |
| custom-commands | 列表或 null | null | 此处可配置自定义命令映射；未使用时保持 null。 |
| command-aliases.map | 映射 | {} | 命令别名映射表。 |
| command-aliases.rename | 映射 | {} | 命令重命名映射表。 |

### 3.20 update-check / placeholder-api

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| update-check.enabled | 布尔 | true | 是否启用版本更新提示检查；不控制强制安全更新策略。 |
| update-check.url | 字符串 |  | 来源地址；仅配置可信的 HTTP(S) 服务。 |
| placeholder-api.enabled | 布尔 | true | 是否启用 PlaceholderAPI 占位符支持。 |


### 3.14 search：联网搜索

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| search.provider | 字符串 | none | 搜索服务商，none 表示禁用。 |
| search.api-url | 字符串 |  | 接口 URL。 |
| search.api-key | 字符串 |  | 搜索服务密钥；留空时搜索功能不可用。不要泄露。 |
| search.result-count | 整数 | 5 | 最多返回的搜索结果条数（1~10）。 |

推荐服务：可接入支持 OpenAI 兼容搜索 API 的提供商，或自建搜索代理。填写前先确认服务商支持函数调用（function calling）返回搜索结果。

### 3.15 audit：敏感词 AI 复核

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| audit.base-url | 字符串 |  | 接口基础地址，通常填写到 /v1。 |
| audit.api-key | 字符串 |  | 复核接口密钥（可选）。不要泄露。 |
| audit.model | 字符串 | gpt-4o-mini | 模型名称，需与服务提供方一致。 |

未配置时只使用本地规则（data/sensitive-words/*.txt 与内置基础词）。

### 3.16 network：插件网络代理

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| network.proxy.enabled | 布尔 | false | 是否启用插件网络代理。 |
| network.proxy.type | 字符串 | http | 代理类型名称。 |
| network.proxy.host | 字符串 |  | 代理主机名或 IP。 |
| network.proxy.port | 整数 | 8080 | 网络端口。 |

仅在服务器无法直接访问外网（皮肤站、AI 接口、搜索服务）时需要配置。

### 3.17 storage：运行数据存储

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| storage.type | 字符串 | sqlite | 运行状态存储后端，新安装默认 SQLite。数据存放在 data/ 目录下。 |

### 3.18 language / debug / webui-port

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| language | 字符串 | zh_CN | 插件消息语言，例如 zh_CN 或 en_US。 |
| debug | 布尔 | false | 输出额外诊断日志；排障后建议关闭，避免日志过大。 |
| webui-port | 整数 | 5678 | WebUI 监听端口。默认只绑定本机回环地址，需要局域网访问时显式改为 0.0.0.0（在平台端配置绑定地址）。 |

WebUI 用于图形化管理配置、扫码授权 QQ、查看日志，地址为 http://127.0.0.1:5678。

### 3.19 command-sender / custom-commands / command-aliases

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| command-sender | 字符串 | Hybrid | 服务器命令执行方式：Hybrid 混合执行器，或 BukkitConsoleSender 模拟控制台。 |
| custom-commands | 列表或 null | null | 此处可配置自定义命令映射；未使用时保持 null。 |
| command-aliases.map | 映射 | {} | 命令别名映射表，将简写映射为完整命令。 |
| command-aliases.rename | 映射 | {} | 命令重命名映射表。 |

### 3.20 update-check / placeholder-api

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| update-check.enabled | 布尔 | true | 是否启用版本更新提示检查；不控制强制安全更新策略。 |
| update-check.url | 字符串 |  | 来源地址；仅配置可信的 HTTP(S) 服务。 |
| placeholder-api.enabled | 布尔 | true | 是否启用 PlaceholderAPI 占位符支持，供在线列表前缀/后缀模板使用。 |

### 3.21 server-notify：服务器启停与性能通知

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| server-notify.enabled | 布尔 | true | 是否启用服务器状态通知功能。 |
| server-notify.shutdown.enabled | 布尔 | true | 是否发送关服通知。 |
| server-notify.shutdown.message | 字符串 | 服务器已正常关闭 | 关服时发送的提示文字。 |
| server-notify.restart.enabled | 布尔 | true | 是否发送重启通知。 |
| server-notify.restart.message | 字符串 | 服务器已重启完成 | 重启时发送的提示文字。 |
| server-notify.crash.enabled | 布尔 | true | 是否发送异常停止通知。 |
| server-notify.crash.message | 字符串 | 服务器意外关闭（崩溃或被强制结束），请管理员检查控制台 | 崩溃时发送的提示文字。 |
| server-notify.restart-window | 整数 | 180 | 上次正常关闭后多少秒内重新启动算作一次重启（0~3600）。 |
| server-notify.performance.enabled | 布尔 | true | 是否向配置群发送持续性能异常/恢复通知。 |
| server-notify.performance.tps-threshold | 浮点 | 15.0 | 连续低于此 TPS 时进入异常判定。 |
| server-notify.performance.memory-threshold | 浮点 | 0.9 | 已用堆内存占最大堆内存的比例阈值，范围 0~1。 |

### 3.22 join-approve：新成员入群审批

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| join-approve.enabled | 布尔 | false | 是否启用新成员入群自动审批功能。 |
| join-approve.interval-seconds | 整数 | 30 | 每隔多少秒执行一次审批检查。 |
| join-approve.strategies | 列表 | [] | 审批策略列表；空列表代表不运行自动审批规则。 |

开启前需确认 QQ 机器人具有相应群管理权限。

### 3.23 binding：QQ 绑定与入服限制

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| binding.member-check.enabled | 布尔 | false | 是否启用群成员退群检查功能。 |
| binding.member-check.interval-minutes | 整数 | 30 | 检查间隔分钟数。 |
| binding.member-check.notify-group | 布尔 | true | 退群是否向群发送通知。 |
| binding.max-bindings | 整数 | 1 | 一个 QQ 号最多可绑定的角色数（1~100）。 |
| binding.require-game-verification | 布尔 | true | 是否要求游戏验证。 |
| binding.force-bind | 布尔 | false | 是否启用强制绑定。QQ 连接失败时会自动关闭此开关，避免未绑定玩家被挡在门外。 |
| binding.force-bind-groups | 字符串列表 | [] | 强制绑定群列表；每项单独占一行。 |
| binding.verify-exempt | 字符串列表 | [] | 免验证名单；每项单独占一行。 |

### 3.24 motd：服务器状态查询与输出

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| motd.server-ip | 字符串 | 127.0.0.1 | 查询服务器地址；不会自动扫描公网 IP。 |
| motd.server-port | 整数 | 25565 | 服务器端口。 |
| motd.api | 字符串 |  | 外部状态查询 API；留空时使用默认服务。 |
| motd.text | 字符串 |  | MOTD 显示文本。 |
| motd.post-img | 布尔 | true | 是否发送 MOTD 图片。 |
| motd.use-markdown | 布尔 | true | 是否使用 Markdown 格式输出 MOTD。 |

### 3.25 markdown：模板文件映射

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| markdown.queryOnline | 字符串 | online.md | 在线查询模板文件名，对应 data/Markdown/ 目录下的模板。 |

### 3.26 compatibility：服务端调度兼容

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| compatibility.server-mode | 字符串 | auto | 服务端调度兼容模式：auto（按运行期类自动判断）、paper（传统 Bukkit 调度）、folia（区域化调度）、hybrid（先区域化，失败才回退）。plugin.yml 的声明不参与运行判断。 |

### 3.27 whitelist：白名单命令模板

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| whitelist.add-command | 字符串 | whitelist add {name} | 添加白名单命令模板，可用 {name}。 |
| whitelist.del-command | 字符串 | whitelist remove {name} | 移除白名单命令模板，可用 {name}。 |

### 3.28 filter-regex / admin / command-blacklist

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| filter-regex | 字符串列表 | [] | 需要过滤的正则表达式列表。 |
| admin.mode | 字符串 | both | 管理员识别与管理员判断方式。 |
| admin.openids | 字符串列表 | [] | QQ 管理员 OpenID 列表。 |
| command-blacklist | 字符串列表 | [] | 禁止 AI 或自定义命令执行的服务器命令。 |

### 3.29 command-panel：QQ 指令面板设置

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| command-panel.show-admin-commands | 布尔 | true | 是否在 QQ 指令面板中显示管理员命令。 |

### 3.30 commands：各项 QQ 机器人命令的启用与面板参数

此段控制每个 QQ 群聊命令是否启用、是否放入指令面板以及面板排序优先级。每个命令有三种写法：

- 简单布尔： / 
- 复杂对象：
  

| 命令 | 默认启用 | 默认面板 | 默认优先级 | 说明 |
|---|---|---|---|---|
| 查信息 | true | 是 | 100 | 查询服务器信息 |
| 查管理 | true | 是 | 100 | 查询管理员列表 |
| 加管理 | true | 是 | 100 | 添加管理员 |
| 删管理 | true | 是 | 100 | 删除管理员 |
| 管理方式 | true | 是 | 100 | 查看/修改管理方式 |
| 添加白名单 | true | 是 | 100 | 添加白名单 |
| 删除白名单 | true | 是 | 100 | 删除白名单 |
| 查白名单 | true | 是 | 100 | 查询白名单 |
| 查在线 | true | 是 | 100 | 查看在线玩家图片 |
| 在线服务器 | true | 是 | 100 | 查看在线服务器状态 |
| 发信息 | true | 是 | 100 | 向游戏内发送消息 |
| 执行命令 | true | 是 | 100 | 执行服务器命令 |
| 执行 | true | 是 | 100 | 执行命令（简写） |
| 管理员执行 | true | 是 | 100 | 管理员执行命令 |
| 全量 | true | 是 | 100 | 全量数据操作 |
| agent | true | 是 | 0 | 调用服务器管理 AI 助手（优先级最高，排在面板最前） |
| 我的背包 | true | 是 | 100 | 查看自己绑定账号的背包 |
| 我的末影箱 | true | 是 | 100 | 查看自己绑定账号的末影箱 |
| 背包查看 | true | 是 | 100 | 管理员查看指定玩家背包 |
| 末影箱查看 | true | 是 | 100 | 管理员查看指定玩家末影箱 |
| 锁定 | true | 是 | 100 | 锁定账号 |
| 解除锁定 | true | 是 | 100 | 解除账号锁定 |
| AI | true | 是 | 1 | 群聊 AI 命令 |

> 隐藏在面板之外的低频运维指令：blockMotd、unblockMotd、压缩、压缩token、压缩保留、删除信息、锁定、解除锁定。

### 3.31 proxy-bridge：跨服务器消息桥

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| proxy-bridge.enabled | 布尔 | false | 是否启用跨服务器消息桥（huhobot:bridge 插件消息通道）。开启后游戏聊天会转发到代理端，由代理广播给其它子服；无人在线时发送静默跳过。 |


### 3.30 commands：各项 QQ 机器人命令的启用与面板参数

此段控制每个 QQ 群聊命令是否启用、是否放入指令面板以及面板排序优先级。每个命令有三种写法：

第一种，简单布尔：命令名: true 或 命令名: false

第二种，复杂对象：
命令名:
  enable: true
  pushMenu: true
  priority: 100

第三种，只写 enable 块。

字段说明：
- enable：是否允许群内调用该命令。
- pushMenu：是否放进 QQ 指令面板。
- priority：面板排序优先级，数值越小越靠前。

| 命令 | 默认启用 | 默认面板 | 默认优先级 | 说明 |
|---|---|---|---|---|
| 查信息 | true | 是 | 100 | 查询服务器信息 |
| 查管理 | true | 是 | 100 | 查询管理员列表 |
| 加管理 | true | 是 | 100 | 添加管理员 |
| 删管理 | true | 是 | 100 | 删除管理员 |
| 管理方式 | true | 是 | 100 | 查看/修改管理方式 |
| 添加白名单 | true | 是 | 100 | 添加白名单 |
| 删除白名单 | true | 是 | 100 | 删除白名单 |
| 查白名单 | true | 是 | 100 | 查询白名单 |
| 查在线 | true | 是 | 100 | 查看在线玩家图片 |
| 在线服务器 | true | 是 | 100 | 查看在线服务器状态 |
| 发信息 | true | 是 | 100 | 向游戏内发送消息 |
| 执行命令 | true | 是 | 100 | 执行服务器命令 |
| 执行 | true | 是 | 100 | 执行命令（简写） |
| 管理员执行 | true | 是 | 100 | 管理员执行命令 |
| 全量 | true | 是 | 100 | 全量数据操作 |
| agent | true | 是 | 0 | 调用服务器管理 AI 助手（优先级最高，排在面板最前） |
| 我的背包 | true | 是 | 100 | 查看自己绑定账号的背包 |
| 我的末影箱 | true | 是 | 100 | 查看自己绑定账号的末影箱 |
| 背包查看 | true | 是 | 100 | 管理员查看指定玩家背包 |
| 末影箱查看 | true | 是 | 100 | 管理员查看指定玩家末影箱 |
| 锁定 | true | 是 | 100 | 锁定账号 |
| 解除锁定 | true | 是 | 100 | 解除账号锁定 |
| AI | true | 是 | 1 | 群聊 AI 命令 |

> 隐藏在面板之外的低频运维指令：blockMotd、unblockMotd、压缩、压缩token、压缩保留、删除信息、锁定、解除锁定。

### 3.31 proxy-bridge：跨服务器消息桥

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| proxy-bridge.enabled | 布尔 | false | 是否启用跨服务器消息桥（huhobot:bridge 插件消息通道）。开启后游戏聊天会转发到代理端，由代理广播给其它子服；无人在线时发送静默跳过。 |


---

## 4. QQ 群聊命令面板说明

在 QQ 群里，机器人会展示一个指令面板（Keyboard），包含所有开启 pushMenu 的命令，按 priority 从小到大排序。管理员可在 WebUI 或配置文件中为每个命令独立开关 enable 与 pushMenu，并调整 priority。

若某个命令被其他插件占用，HuHoBot 会自动将其挂到根命令 /huhobot 下（冲突回退），未冲突时保持原样。控制台会输出「指令冲突」提示。

面板推荐配置：将 agent、AI、查在线、我的背包、我的末影箱 放在前面（priority 0~1），管理类命令放在后面（priority 100+）。

---

## 5. QQ 平台限制与被动回复额度

以下限制来自 QQ 开放平台官方口径，插件无法绕过：

| 限制项 | 说明 |
|---|---|
| 群被动回复 | 有效期 60 分钟，从入站消息到达时算起；普通群每条入站消息最多回复 5 次；机器人开发者在该群为群主/管理员时 50 次。 |
| 单聊被动 | 60 分钟 4 次。 |
| 群主动消息 | 每群每天约 1000 条（受平台与群主设置影响）。 |
| 撤回 | 只能撤回 2 分钟内的消息；机器人为群管理员时可撤回普通成员消息，否则只能撤回自己的消息。 |
| @ 通知 | markdown 格式的 <@openid> 才会触发 QQ 的 @ 通知。 |

> message.passive-reply.enhanced-groups 只能把符合条件的群提到 50 次/条，不能绕过平台对机器人身份的核验。

---

## 6. 图片生成与渲染说明

### 查在线图片

- 顶部标题支持 MiniMessage、§ 与 & 颜色码。
- 玩家卡片：玩家 ID 独占一行（不会因长称号换行或消失），称号与后缀在第二行按宽度省略。
- 管理员（admin-groups 配置的 LuckPerms 组）显示金边卡片。
- 超过 max-render-players 的人数，超出部分用 Markdown 文字列表补全。
- 侧栏显示服务器图标、名称、在线人数、MOTD、服务器地址、自定义信息行、核心/版本/协议/兼容信息。
- 底部显示性能条（TPS/MSPT/内存）、在线趋势折线图（最近 48 小时）、页码与更新时间。

### 背包与末影箱图片

- 支持自定义背景图片（cover 裁切铺满或 stretch 拉伸）。
- 渲染护甲（头盔/胸甲/护腿/靴子）、副手、物品栏（3 行 9 列）、快捷栏。
- 单格物品图显示物品名称、数量、耐久、附魔（中文名）、Lore、NBT/组件、属性。
- 潜影盒/收纳袋内容物可通过单格查询查看内层物品。

### 聊天记录图片

- 仅保留最近公共聊天，仅内存、不落盘；重启清零。
- 超过 max-length 的消息占两行，实际每页条数会减少。

---

## 7. 排错速查与常见问题

| 现象 | 可能原因 | 检查与解决 |
|---|---|---|
| 图片不显示 / 渲染失败 | 字体未加载、online-list.enabled=false、渲染线程异常 | 查看控制台日志中的渲染错误；确认 online-list.enabled: true；检查 data/ 目录写权限 |
| 头像是默认皮肤 | 离线模式 + offline-default-skins: true 属预期 | 装皮肤插件（SkinsRestorer/ChangeSkin）或关闭 offline-default-skins |
| 查在线没有趋势线 | 历史文件缺失或采样不足 | 确认 data/online-history 未被删；等待足够的进出服采样 |
| 命令没反应 | 指令被其他插件占用 | 查看日志里的「指令冲突」提示；被占用的命令会自动挂到 /huhobot 下 |
| 重载后配置回退 | YAML 不合法 | 无效配置会保留旧值并报错；修复 YAML 后执行 /huhobot reload |
| AI 不调用工具 | agent.tools 漏填、模型不支持 function calling | 检查 agent.tools 白名单；确认模型支持工具调用 |
| 群里发图失败 | 富媒体权限、图片过大、被动额度耗尽 | 确认机器人为群成员；检查图片大小；留意被动回复额度（普通 5 次/条） |
| 语音失败 | tts.public-base-url 非公网地址、音频格式不被 QQ 接受 | 确保公网 URL 可达；QQ 官方要求 silk 格式，wav/mp3 部分环境可用 |
| 配置改坏导致插件不启动 | YAML 语法错误 | 插件会保留旧配置并在日志提示行号；修复后重载即可 |
| 强制绑定玩家无法进服 | QQ 机器人未连接 | 插件会自动关闭 binding.force-bind 并记录错误；连上 QQ 后重新开启 |
| QQ 群 ID 无效（11255） | 群 OpenID 错误或机器人未在该群 | 确认群 OpenID 正确，且机器人已被拉入该群；可在群里 @ 机器人获取有效 ID |

---

## 8. 扩展（addons）编写与使用

### 8.1 支持的语言

HuHoBot 扩展支持以下语言：

| 语言 | 入口文件 | 运行方式 |
|---|---|---|
| JavaScript | main.js | 基于 GraalJS 脚本引擎 |
| Lua | main.lua | 基于 GraalVM 脚本引擎 |
| Java | Main.java | 实现 AddonProgram 接口，编译为类文件 |

### 8.2 最小目录结构



### 8.3 metadata.yaml 编写示例



字段说明：
- name：扩展名称，必须唯一，建议小写字母加下划线。
- version：版本号，语义化版本。
- author：作者名称。
- description：扩展功能简述。
- entry：入口文件名，相对于扩展目录。

### 8.4 JavaScript 扩展示例（main.js）



常用 API：
- Bird.log(msg)：向服务器控制台输出日志。
- Bird.onCommand(key, handler)：注册群聊命令，触发词为 key。
- Bird.tell(sender, msg)：向触发者发送消息。
- Bird.getOnlineList()：获取在线玩家名单。

### 8.5 Lua 扩展示例（main.lua）



### 8.6 Java 扩展示例（Main.java）



元数据文件 metadata.yaml 中 entry 应填写类全名或按项目约定填写。

### 8.7 搭建一个最基础的小扩展（步骤）

第一步：创建目录


第二步：写 metadata.yaml


第三步：写 main.js


第四步：重载扩展
在服务器控制台或 QQ 群里执行：


第五步：验证
在 QQ 群里发送 ，应收到欢迎消息。

### 8.8 扩展注意事项

- 扩展拥有插件级宿主权限，**不是安全沙箱**，只装可信代码。
- 示例文件在 data/guide/，**示例不会自动运行**。
- 重载命令：；失败会回滚已注册的命令与任务。
- 扩展之间名称不能重复，否则后加载的会失败。

---

## 9. 重载、升级与备份

### 配置重载

修改 config.yml 后，执行  在后台重载，仅重建发生变化的模块，不中断服务器。
配置加载失败时，插件会保留原文件并继续使用当前已加载配置，控制台会输出错误行号。

### 备份建议

- 升级前备份 plugins/HuHoBotPenguin/config.yml 与 data/ 目录。
- 插件在写配置前会自动创建 data/backups/config-*.yml 临时备份。
- 不要把 data/ 里的绑定数据库发送给他人。

### 版本升级

1. 下载新版 HuHoBot-Penguin-*.jar。
2. 备份旧版 jar 与 config.yml、data/。
3. 替换 plugins/ 下的 jar 文件。
4. 重启服务器，插件会自动检测 config-version 并补齐缺失配置项。
5. 执行  应用新配置。

---

## 10. 版本验证与生产环境注意事项

- 1.24 主包已通过全部组件/回归测试、打包 JS 回调烟测，并完成 Paper 1.18.2/1.20.6/1.21.11、Folia 1.21.11 以及 Velocity/Waterfall/BungeeCord 的启动、重载与指令路由回归。
- 生产环境下 QQ 群真实投递、图片发送、撤回需要你自己的凭据与群权限验证；测试群 OpenID 无效时，请在群里 @ 机器人或提供有效群 ID。
- PR 不会提交到上游 master，而是在 fork 分支 v1.24-community 上等待合并；用户本地验证通过后再提供 GitHub 密钥进行 PR 提交。

---

> 本手册由 HuHoBot Penguin 1.24 自动生成。如有配置项未在此处覆盖，请以 config.yml 项上方的中文注释为准，或联系插件开发者。

---

## 4. QQ 群聊命令面板说明

在 QQ 群里，机器人会展示一个指令面板（Keyboard），包含所有开启 pushMenu 的命令，按 priority 从小到大排序。管理员可在 WebUI 或配置文件中为每个命令独立开关 enable 与 pushMenu，并调整 priority。

若某个命令被其他插件占用，HuHoBot 会自动将其挂到根命令 /huhobot 下（冲突回退），未冲突时保持原样。控制台会输出「指令冲突」提示。

面板推荐配置：将 agent、AI、查在线、我的背包、我的末影箱 放在前面（priority 0~1），管理类命令放在后面（priority 100+）。

---

## 5. QQ 平台限制与被动回复额度

以下限制来自 QQ 开放平台官方口径，插件无法绕过：

| 限制项 | 说明 |
|---|---|
| 群被动回复 | 有效期 60 分钟，从入站消息到达时算起；普通群每条入站消息最多回复 5 次；机器人开发者在该群为群主/管理员时 50 次。 |
| 单聊被动 | 60 分钟 4 次。 |
| 群主动消息 | 每群每天约 1000 条（受平台与群主设置影响）。 |
| 撤回 | 只能撤回 2 分钟内的消息；机器人为群管理员时可撤回普通成员消息，否则只能撤回自己的消息。 |
| @ 通知 | markdown 格式的 <@openid> 才会触发 QQ 的 @ 通知。 |

> message.passive-reply.enhanced-groups 只能把符合条件的群提到 50 次/条，不能绕过平台对机器人身份的核验。

---

## 6. 图片生成与渲染说明

### 查在线图片

- 顶部标题支持 MiniMessage、§ 与 & 颜色码。
- 玩家卡片：玩家 ID 独占一行（不会因长称号换行或消失），称号与后缀在第二行按宽度省略。
- 管理员（admin-groups 配置的 LuckPerms 组）显示金边卡片。
- 超过 max-render-players 的人数，超出部分用 Markdown 文字列表补全。
- 侧栏显示服务器图标、名称、在线人数、MOTD、服务器地址、自定义信息行、核心/版本/协议/兼容信息。
- 底部显示性能条（TPS/MSPT/内存）、在线趋势折线图（最近 48 小时）、页码与更新时间。

### 背包与末影箱图片

- 支持自定义背景图片（cover 裁切铺满或 stretch 拉伸）。
- 渲染护甲（头盔/胸甲/护腿/靴子）、副手、物品栏（3 行 9 列）、快捷栏。
- 单格物品图显示物品名称、数量、耐久、附魔（中文名）、Lore、NBT/组件、属性。
- 潜影盒/收纳袋内容物可通过单格查询查看内层物品。

### 聊天记录图片

- 仅保留最近公共聊天，仅内存、不落盘；重启清零。
- 超过 max-length 的消息占两行，实际每页条数会减少。

---

## 7. 排错速查与常见问题

| 现象 | 可能原因 | 检查与解决 |
|---|---|---|
| 图片不显示 / 渲染失败 | 字体未加载、online-list.enabled=false、渲染线程异常 | 查看控制台日志中的渲染错误；确认 online-list.enabled: true；检查 data/ 目录写权限 |
| 头像是默认皮肤 | 离线模式 + offline-default-skins: true 属预期 | 装皮肤插件（SkinsRestorer/ChangeSkin）或关闭 offline-default-skins |
| 查在线没有趋势线 | 历史文件缺失或采样不足 | 确认 data/online-history 未被删；等待足够的进出服采样 |
| 命令没反应 | 指令被其他插件占用 | 查看日志里的「指令冲突」提示；被占用的命令会自动挂到 /huhobot 下 |
| 重载后配置回退 | YAML 不合法 | 无效配置会保留旧值并报错；修复 YAML 后执行 /huhobot reload |
| AI 不调用工具 | agent.tools 漏填、模型不支持 function calling | 检查 agent.tools 白名单；确认模型支持工具调用 |
| 群里发图失败 | 富媒体权限、图片过大、被动额度耗尽 | 确认机器人为群成员；检查图片大小；留意被动回复额度（普通 5 次/条） |
| 语音失败 | tts.public-base-url 非公网地址、音频格式不被 QQ 接受 | 确保公网 URL 可达；QQ 官方要求 silk 格式，wav/mp3 部分环境可用 |
| 配置改坏导致插件不启动 | YAML 语法错误 | 插件会保留旧配置并在日志提示行号；修复后重载即可 |
| 强制绑定玩家无法进服 | QQ 机器人未连接 | 插件会自动关闭 binding.force-bind 并记录错误；连上 QQ 后重新开启 |
| QQ 群 ID 无效（11255） | 群 OpenID 错误或机器人未在该群 | 确认群 OpenID 正确，且机器人已被拉入该群；可在群里 @ 机器人获取有效 ID |


---

## 8. 扩展（addons）编写与使用

### 8.1 支持的语言

HuHoBot 扩展支持以下语言：

| 语言 | 入口文件 | 运行方式 |
|---|---|---|
| JavaScript | main.js | 基于 GraalJS 脚本引擎 |
| Lua | main.lua | 基于 GraalVM 脚本引擎 |
| Java | Main.java | 实现 AddonProgram 接口，编译为类文件 |

### 8.2 最小目录结构

```
plugins/HuHoBotPenguin/addons/hello/
├── metadata.yaml
└── main.js
```

### 8.3 metadata.yaml 编写示例

```
name: hello
version: '1.0.0'
author: '你的名字'
description: '问候示例'
entry: main.js
```

字段说明：
- name：扩展名称，必须唯一，建议小写字母加下划线。
- version：版本号，语义化版本。
- author：作者名称。
- description：扩展功能简述。
- entry：入口文件名，相对于扩展目录。

### 8.4 JavaScript 扩展示例（main.js）

```
Bird.log('hello ready');

Bird.onCommand('hello', function(sender) {
    Bird.tell(sender, '你好，' + sender.getName());
});

Bird.onCommand('ping', function(sender) {
    Bird.tell(sender, 'pong');
});
```

常用 API：
- Bird.log(msg)：向服务器控制台输出日志。
- Bird.onCommand(key, handler)：注册群聊命令，触发词为 key。
- Bird.tell(sender, msg)：向触发者发送消息。
- Bird.getOnlineList()：获取在线玩家名单。

### 8.5 Lua 扩展示例（main.lua）

```
Bird.log('hello ready')

Bird.onCommand('hello', function(sender)
    Bird.tell(sender, '你好，' .. sender:getName())
end)

Bird.onCommand('ping', function(sender)
    Bird.tell(sender, 'pong')
end)
```

### 8.6 Java 扩展示例（Main.java）

```
package agent.addon.example;

import agent.addon.AddonProgram;
import agent.addon.AddonContext;

public class Main implements AddonProgram {
    @Override
    public void onEnable(AddonContext ctx) {
        ctx.log("hello ready");
        ctx.registerCommand("hello", sender -> {
            ctx.tell(sender, "你好，" + sender.getName());
        });
    }

    @Override
    public void onDisable() {
        // 清理资源
    }
}
```

元数据文件 metadata.yaml 中 entry 应填写类全名或按项目约定填写。

### 8.7 搭建一个最基础的小扩展（步骤）

第一步：创建目录

```
mkdir -p plugins/HuHoBotPenguin/addons/mywelcome
```

第二步：写 metadata.yaml

```
name: mywelcome
version: '1.0.0'
author: '管理员'
description: '欢迎新玩家入群'
entry: main.js
```

第三步：写 main.js

```
Bird.log('mywelcome loaded');

Bird.onCommand('welcome', function(sender) {
    var name = sender.getName();
    Bird.tell(sender, '欢迎来到服务器，' + name + '！祝你游戏愉快。');
});
```

第四步：重载扩展

在服务器控制台或 QQ 群里执行：

```
/huhobot scripts reload mywelcome
```

第五步：验证

在 QQ 群里发送 `/welcome`，应收到欢迎消息。

### 8.8 扩展注意事项

- 扩展拥有插件级宿主权限，不是安全沙箱，只装可信代码。
- 示例文件在 data/guide/，示例不会自动运行。
- 重载命令：`/huhobot scripts reload <目录>`；失败会回滚已注册的命令与任务。
- 扩展之间名称不能重复，否则后加载的会失败。

---

## 9. 重载、升级与备份

### 配置重载

修改 config.yml 后，执行 `/huhobot reload` 在后台重载，仅重建发生变化的模块，不中断服务器。
配置加载失败时，插件会保留原文件并继续使用当前已加载配置，控制台会输出错误行号。

### 备份建议

- 升级前备份 plugins/HuHoBotPenguin/config.yml 与 data/ 目录。
- 插件在写配置前会自动创建 data/backups/config-*.yml 临时备份。
- 不要把 data/ 里的绑定数据库发送给他人。

### 版本升级

1. 下载新版 HuHoBot-Penguin-*.jar。
2. 备份旧版 jar 与 config.yml、data/。
3. 替换 plugins/ 下的 jar 文件。
4. 重启服务器，插件会自动检测 config-version 并补齐缺失配置项。
5. 执行 `/huhobot reload` 应用新配置。

---

## 10. 版本验证与生产环境注意事项

- 1.24 主包已通过全部组件/回归测试、打包 JS 回调烟测，并完成 Paper 1.18.2/1.20.6/1.21.11、Folia 1.21.11 以及 Velocity/Waterfall/BungeeCord 的启动、重载与指令路由回归。
- 生产环境下 QQ 群真实投递、图片发送、撤回需要你自己的凭据与群权限验证；测试群 OpenID 无效时，请在群里 @ 机器人或提供有效群 ID。
- PR 不会提交到上游 master，而是在 fork 分支 v1.24-community 上等待合并；用户本地验证通过后再提供 GitHub 密钥进行 PR 提交。

---

> 本手册由 HuHoBot Penguin 1.24 自动生成。如有配置项未在此处覆盖，请以 config.yml 项上方的中文注释为准，或联系插件开发者。
