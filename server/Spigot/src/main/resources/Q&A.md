# HuHoBot Penguin 1.24 使用手册

本文件由插件自动生成。如果你修改了它，插件会保留你的版本，并把新版作为 `Q&A.md.new` 放在旁边。

## 1. 快速开始

1. 把 `HuHoBot-Penguin-1.23.jar` 放进 `plugins/`，启动一次服务器。
2. 打开 `plugins/HuHoBotPenguin/config.yml`，至少填写：
   - `bot.app-id` / `bot.secret`：QQ 开放平台机器人凭据；也可留空，启动后在控制台或 WebUI 扫码。
   - `bot.groups`：允许机器人工作的群 OpenID；留空表示不限制（首次收消息会自动收录）。
   - `serverName`：图片与播报里显示的服务器名。
   - `online-list.address`：查在线图片里展示的连接地址。
3. 需要 AI 时填写 `agent.*` 与 `chat-ai.*`，两者互不影响。
4. 执行 `/huhobot reload` 应用配置；`/huhobot info` 查看运行状态与调度模式。

## 2. 目录结构

| 路径 | 用途 | 能否删除 |
| --- | --- | --- |
| `config.yml` | 主配置 | 不能；可备份后重建 |
| `data/` | 运行数据（绑定、面板、日志、快照） | 不能随意删 |
| `data/Q&A.md` | 本手册 | 可删，重启再生成 |
| `data/guide/` | 权限组/定时任务/扩展示例，**不会自动启用** | 可删 |
| `addons/` | 小型扩展目录，一目录一扩展 | 无扩展时可删 |
| `data/inventory/snapshots/` | 离线背包只读快照 | 不要手改 |
| `data/logs/` | QQ SDK 与内容日志 | 可归档，不要外发 |

配置文件与运行数据分开：**不要在 `data/` 里找 config.yml**，也不要手工把 `data/` 里的数据库复制给他人（含 openid 与绑定关系）。

## 3. 配置分区

### 3.1 `bot`
`app-id`、`secret`、`name`、`groups`、`suppress-console-output`、`auto-add-groups`。
SDK 控制台输出默认屏蔽；排障时可临时关闭，但不要把带 token 的原始日志发到群里。

### 3.2 `serverName` 与 `online-list`
- `serverName` 与 `online-list.title` 支持 MiniMessage、`§` 与 `&` 颜色码；以 `&`、`#`、`:` 开头的值必须加引号。
- `online-list.title` 为空时使用默认标题；`online-list.address` 只显示你填的文本，不会自动探测。
- `online-list.display.prefix-format` / `suffix-format` 是称号模板，支持 `{name}`、`{display}`、`{world}`、`{ping}`、`{server}` 与 PlaceholderAPI `%占位符%`。
- `online-list.custom-lines`：图片中的自定义信息行，最多 6 行；空行会被忽略。
- `online-list.max-render-players`：单图最多画多少人，默认 27；超出部分用文字补全。
- 玩家卡片：玩家 ID 独占一行（不会因长称号换行或消失），称号与后缀在第二行按宽度省略。

### 3.3 皮肤与离线模式
- `online-list.skin.enabled`：是否允许网络皮肤。
- `online-list.skin.offline-default-skins`（默认开启）：当服务器 `online-mode=false` 时只用内置皮肤，**不向皮肤站请求**。装了 SkinsRestorer / ChangeSkin 等插件仍优先显示其皮肤。
- `online-list.skin.sources`：来源链，按顺序尝试；`avatar`/`crafatar` 只返回头像，不会用于背包人物预览。
- 所有远程下载都有超时、大小上限、重定向校验与内网地址拦截；失败回退内置皮肤，不阻塞服务器线程。

### 3.4 `inventory`
自定义背包/末影箱背景、单格物品图；背景文件放 `data/`，路径在配置里填写。
单格查询的槽位号：
- 背包：`0-8` 快捷栏，`9-35` 主背包，`36` 靴子、`37` 护腿、`38` 胸甲、`39` 头盔、`40` 副手。
- 末影箱：`0-26`，每行 9 格。

### 3.5 `chat-log`（1.23 新增）
- `/聊天记录 [页码]`、`/玩家聊天 <玩家名> [页码]` 生成与查在线同风格的图片。
- 只保留**最近公共聊天**，仅内存、不落盘；重启清零。
- `page-size`：每页目标行数；超过 `max-length` 的消息占两行，因此实际条数会减少，保证图片可读。
- `max-length`：单条在图片中最多显示的字符数，超出用省略号。
- `cooldown-seconds`：同群查询冷却，避免刷图。

### 3.6 `message`
- `send-policy`：`auto` 优先被动回复，额度用尽才主动；`active` 始终主动（会消耗每日主动额度）。
- `passive-reply.enhanced-groups`：只填**已确认**「机器人开发者在本群为群主/管理员」的群 OpenID，这些群每条消息可回复 50 次；未列出为 5 次。插件不能代替平台核验资格。

### 3.7 `moderation`
- `blacklist` / `block-messages`：入群与消息黑名单。
- `sensitive-words`：本地词表、AI 二次审核、违规自动撤回、`allow-none`。
- 默认内置基础词始终生效；删空自定义词表也会保留内置词。
- `allow-none: true` 会停用内置词表，**风险由服主承担**：不能绕过 QQ/Minecraft 平台审核，可能导致账号受限。插件只在启动/重载时写一条告警，不把风险告知文本写入任何配置，也不跨目录到处存放。

### 3.9 `agent` 与 `chat-ai`
- `agent.max-steps`：一次请求最多模型/工具往返次数，审批与休眠后继续累计，不会无限重置。
- `agent.max-wait-seconds`：`sleep` 工具单次上限；要真正等待两小时请设为 7200。
- `agent.tools`：工具白名单，空列表允许全部；漏填会让 AI「看起来什么都不会」。
- `agent.system-prompt`：只替换身份描述，服务器与权限规则仍会附加。
- 两者都可配置 `think`（原生推理）或 `think-guide`（提示词模拟），二者互斥。

## 4. 命令

### 4.1 群内公开命令
| 命令 | 说明 |
| --- | --- |
| `查在线 [页码]` | 在线玩家图片 |
| `聊天记录 [页码]` | 最近公共聊天图片 |
| `玩家聊天 <玩家名> [页码]` | 指定玩家的聊天图片 |
| `我的背包` / `我的末影箱` | 绑定账号的背包/末影箱 |
| `绑定` / `解除绑定` | QQ 与游戏账号绑定 |
| `motd` / `在线` 等 | 见 `/huhobot info` 与 QQ 指令面板 |

### 4.2 管理员命令
| 命令 | 说明 |
| --- | --- |
| `背包查看 <玩家>` / `末影箱查看 <玩家>` | 指定玩家整包图片 |
| `背包物品查询 <玩家> <槽位>` | 单格物品图（含名称/附魔/Lore/NBT/属性） |
| `末影箱物品查询 <玩家> <槽位>` | 末影箱单格 |
| `/agent <任务>` | AI 助手 |
| `/huhobot reload` | 后台重载，仅重建发生变化的模块 |

### 4.3 游戏内命令
`/huhobot`（短名冲突时自动改为回退根命令，见日志）；`/at`、`/send`、`/qqbind` 被其他插件占用时会自动挂到根命令下，未冲突时保持原样。

## 5. QQ 平台限制（官方口径）
- 群被动回复：有效期 60 分钟，从入站消息到达时算起；普通 5 次；开发者在该群为群主/管理员时 50 次。
- 单聊被动：60 分钟 4 次。
- 群主动消息：每群每天约 1000 条（受平台与群主设置影响）。
- 撤回：只能撤回 2 分钟内的消息；机器人为群管理员时可撤回普通成员消息，否则只能撤回自己的消息。

## 6. 扩展（addons）
- 一目录一扩展：`addons/<名字>/main.js`、`main.lua` 或 `Main.java`。
- `metadata.yaml` 支持 `name/version/author/description/entry`；示例见 `data/guide/`，**示例不会自动运行**。
- 扩展拥有插件级宿主权限，不是安全沙箱；只装可信代码。
- `/huhobot scripts reload <目录>` 热重载；失败会回滚已注册的命令与任务。

## 7. 排错速查
| 现象 | 检查 |
| --- | --- |
| 图片不显示 | 字体是否加载、`online-list.enabled`、日志中的渲染错误 |
| 头像是默认皮肤 | 离线模式 + `offline-default-skins: true` 属预期；装皮肤插件或关闭该开关 |
| 查在线没有趋势 | 历史文件 `data/online-history` 是否被删、时间是否足够 |
| 命令没反应 | 指令是否被其他插件占用；查看日志里的「指令冲突」提示 |
| 重载后配置回退 | YAML 是否合法；无效配置会保留旧值并报错 |
| AI 不调用工具 | `agent.tools` 是否漏填、模型是否支持 function calling |
| 群里发图失败 | 富媒体权限、图片大小、被动额度是否耗尽 |

## 8. 验证与版本
1.24 主包通过全部组件/回归测试、打包 JS 回调烟测，并完成启动、重载与指令路由回归。生产 QQ 投递、踢人与撤回需要你自己的凭据与群权限验证。
