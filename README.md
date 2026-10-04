# HuHoBotPenguin

将 QQ 群机器人接入 Minecraft 服务器：游戏聊天与 QQ 群双向转发、白名单管理、在线查询、命令执行、敏感词审核、**AI Agent 智能管理**与 **WebUI 图形化配置**。

[HuHoBot-Penguin](https://github.com/HuHoBot/PenguinClient)的更新最激进与快速，功能最齐全的分支

基于 [qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java)（HuHoBot fork，以 git submodule 引入）。

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)

![HuHoBot](https://count.moeyy.cn/@huhobot?name=huhobot&theme=booru-lewd&padding=7&offset=0&align=top&scale=1&pixelated=1&darkmode=auto)

---

## 功能概览

| 功能 | 说明 |
|------|------|
| 双向聊天转发 | 游戏 ↔ QQ 群消息实时互通，支持格式模板与前缀过滤 |
| QQ 群指令系统 | 30+ 条内置命令，支持权限分级、命令开关与动态帮助 |
| 白名单管理 | 映射到服务器原生命令，支持自定义模板 |
| 敏感词审核 | 本地正则词库 + 可选 OpenAI 兼容接口 AI 二审 |
| MOTD 服务器状态 | `/motd` 查询服务器状态，返回图片 + Markdown 卡片 |
| 自定义命令 | 占位符替换，支持权限分级（普通/管理员） |
| 多群支持 | 每群独立的管理员名单与配置 |
| **AI Agent** | `@机器人 /agent 任务描述` —— AI 自动执行服务器管理任务 |
| **WebUI 图形化配置** | 浏览器访问 `http://127.0.0.1:<port>` 管理所有配置项（端口可配置） |
| **群服互通 @提及** | 游戏内 `/at` 命令发送 QQ @消息，QQ→游戏蓝色高亮+叮音效 |
| **QQ 群管理** | AI Agent 集成禁言、入群审批、自动审批策略等群管理工具 |
| **指令面板自动同步** | 启动时自动同步命令面板到 QQ 群（上限 20 条） |
| **背包与末影箱查看** | `/我的背包`、`/我的末影箱` 查询唯一绑定账户；管理员可用 `/背包查看 <在线玩家名>`、`/末影箱查看 <在线玩家名>` |
| **脚本扩展** | `addons/<名字>/` 目录插件，入口 `main.js` / `main.lua` / `main.py`，用 `Bird` 桥 |

---

## 支持的平台

| 平台 | 状态 | JDK 要求 | 产物 |
|------|------|----------|------|
| **Spigot / Paper**（api-version 1.18+） | 活跃开发 | JDK 8+ | `HuHoBot-Penguin_Spigot-<版本>.jar` |
| Nukkit / PMMP | 社区适配版（非本仓库维护） | — | [PenguinAgent-NukkitPlatform](https://github.com/HuHoBot/PenguinAgent-NukkitPlatform) |
| Velocity / BungeeCord | 待适配 | — | — |
| Allay | 待适配 | — | — |

> Nukkit-MOT 适配版本维护在独立仓库 [PenguinAgent-NukkitPlatform](https://github.com/HuHoBot/PenguinAgent-NukkitPlatform)，不由本仓库维护，功能与 issue 请前往该仓库反馈。

---

## 快速开始

### 1. 准备

1. 到 [q.qq.com](https://q.qq.com/) 申请机器人，获得 **AppID** 和 **Secret**
2. 准备运行环境：**JDK 8+**（推荐 JDK 17+）
3. 准备 Minecraft 服务器：**Paper 1.20.5+** 或兼容 Spigot 的服务端

### 2. 构建

```bash
git clone --recurse-submodules git@github.com:HuHoBot/PenguinAgent.git
cd PenguinAgent
./gradlew build
```

> **注意**：项目依赖 `deps/qqpd-bot-java` 子模块。克隆时若未使用 `--recurse-submodules`，需运行：
> ```bash
> git submodule update --init --recursive
> ```

构建产物位于 `build/gather-jar/` 目录。

### 3. 安装

将 `HuHoBot-Penguin_Spigot-<版本>.jar` 放入服务器 `plugins/` 目录，重启服务器。

### 4. 配置

首次启动后会在插件数据目录生成 `config.yml`，也可通过 **WebUI** 进行图形化配置。

#### 核心配置项

```yaml
# QQ 机器人凭据
bot:
  app-id: "你的AppID"
  secret: "你的Secret"
  name: "机器人昵称"
  groups: []                    # 群 OpenID 列表，留空则不限制

# 聊天格式
chat-format:
  from-game: "[游戏] {message}"       # 游戏→群 格式
  from-group: "[QQ] {name}: {message}" # 群→游戏 格式
  post-chat: true                       # 是否转发游戏聊天
  start-with: ""                        # 触发前缀（留空转发全部）

# AI Agent
agent:
  enabled: false
  base-url: ""        # OpenAI 兼容接口地址
  api-key: ""         # AI 接口密钥
  model: "gpt-4o-mini"
  command-mode: "manual"  # manual=手动审批 / auto=自动执行
```

完整配置说明请参考 WebUI 或 `config.yml` 中的注释。

---

## AI Agent

### 使用方式

在 QQ 群中 `@机器人 /agent <任务描述>`，AI 会自动执行服务器管理任务。

**示例：**
```
@HuHoBot /agent 查看服务器插件列表
@HuHoBot /agent 给玩家 Steve 添加白名单
@HuHoBot /agent 禁言玩家 BadBoy 10分钟
```

### 工作原理

1. 用户发送 `/agent` 命令
2. AI 根据任务描述选择合适的工具执行
3. 每次执行最多 **15 步**，上下文窗口 **40 条消息**
4. 危险操作（执行命令、禁言等）需管理员通过按钮审批

### 可用工具（17 个）

**服务器管理（5 个）：**

| 工具 | 说明 |
|------|------|
| `get_server_plugin_list` | 获取已安装插件列表 |
| `get_command_help` | 获取命令帮助信息 |
| `run_command` | 执行服务器控制台命令（需审批） |
| `read_server_logs` | 读取服务端日志（支持关键词过滤） |
| `load_skill` | 加载 MC 命令语法文档（components/give/item/summon/data/loot/clear） |

**QQ 群管理（12 个）：**

| 工具 | 说明 |
|------|------|
| `get_group_info` | 获取群基本信息 |
| `get_bot_state` | 获取机器人在群内的状态 |
| `get_join_requests` | 列出待审批的入群申请 |
| `approve_join_request` | 审批入群申请 |
| `get_mute_status` | 查询群禁言状态 |
| `set_member_mute` | 设置/解除成员禁言（最长 30 天，需审批） |
| `list_auto_approve_policies` | 列出自动审批策略 |
| `create_auto_approve_policy` | 创建自动审批策略（需审批） |
| `update_auto_approve_policy` | 更新自动审批策略（需审批） |
| `delete_auto_approve_policy` | 删除自动审批策略（需审批） |
| `execute_auto_approve_policy` | 执行自动审批策略（需审批） |
| `update_whitelist_users` | 更新自动审批白名单 QQ 号（需审批） |

### 命令执行模式

| 模式 | 说明 |
|------|------|
| **手动审批（manual）** | AI 执行危险操作时发送审批卡片，管理员/群主点击同意/拒绝 |
| **自动执行（auto）** | AI 直接执行所有操作，无需审批 |

**不建议开auto！不建议开auto！不建议开auto！**

**有用户反馈使用auto后AI幻觉问题导致误刷神装给玩家！！！**

**数据无价，谨慎操作 --DiskGenius**

### SKILL 系统

AI 支持按需加载 Minecraft 命令语法文档：

| SKILL | 内容 |
|-------|------|
| `components` | 物品组件语法（1.20.5+） |
| `give` | `/give` 命令语法 |
| `item` | 物品格式与 NBT |
| `summon` | `/summon` 实体命令 |
| `data` | `/data` 数据操作命令 |
| `loot` | `/loot` 掉落物命令 |
| `clear` | `/clear` 清除物品命令 |

### 相关命令

| QQ 群命令 | 说明 |
|-----------|------|
| `agent <任务描述>` | 触发 AI Agent 执行任务 |
| `newsession` | 清除当前用户的 AI 会话上下文 |
| `stop` | 紧急停止所有 AI 任务 |

---

## WebUI 图形化配置

### 访问方式

启动后在服务器控制台查看密码，然后浏览器访问（默认端口 5678，可在 config.yml 中修改 `webui-port`）：

```
http://127.0.0.1:5678
```

### 功能

- **暗色简约风格**，响应式布局
- **14 个配置分组**，覆盖所有配置项
- 修改后**实时保存**，自动重载配置
- 首次启动自动生成 **24 位随机密码**

### 配置分组

| 分组 | 说明 |
|------|------|
| QQ 机器人 | AppID、Secret、机器人名称、群列表 |
| 服务器 | 服务器名称、命令执行器类型 |
| 聊天格式 | 双向转发格式模板、触发前缀 |
| 玩家事件 | 进服/退服通知开关与格式 |
| Markdown | 在线查询 Markdown 模板 |
| MOTD | 服务器状态查询配置 |
| 白名单命令 | 添加/删除白名单的命令模板 |
| 正则过滤 | 敏感词正则表达式列表 |
| 管理员 | 管理员模式与 OpenID 列表 |
| 功能 | 全量转发等开关 |
| 内容审核 | AI 敏感词审核接口配置 |
| AI Agent | Agent 启用、接口、模型、审批模式 |
| 命令开关 | 逐个开关 20+ 条内置命令 |
| 自定义命令 | 自定义命令模板与权限 |

### 控制台命令

| 命令 | 说明 |
|------|------|
| `/hb reload` | 重载配置文件 |
| `/hb info` | 查看适配器信息 |
| `/hb webui` | 查看 WebUI 地址 |
| `/hb password <新密码>` | 修改 WebUI 登录密码 |

---

## 群服互通 @提及

### 游戏 → QQ

在 Minecraft 聊天中输入 `@玩家名 消息`，消息会以 QQ @消息格式发送到群，触发被 @ 玩家的通知。

支持格式：`@张三 你好`、`@张三@李四 一起玩`

### QQ → 游戏

QQ 群中的 @消息会自动解析为 `§9@玩家名§r`（蓝色高亮），被 @ 的在线玩家会收到**叮**音效提醒。

### /at 命令（Minecraft 游戏内）

```
/at <群成员昵称> <消息内容>
```

向 QQ 群发送 @消息，触发被 @ 玩家的通知。支持 Tab 补全昵称。

### 昵称缓存

- 从收到的 QQ 消息中自动缓存昵称 ↔ openid 映射
- 持久化到 `nicknames.dat` 文件，重启不丢失
- 自动过滤 openid 作为昵称的脏数据

---

## QQ 群指令系统

### 公开命令（所有群成员可用）

| 命令 | 说明 |
|------|------|
| `查信息` `[OpenId]` | 查询自己的或指定用户的 OpenId 和认证状态 |
| `发信息` `<内容>` | 将消息转发到游戏内聊天 |
| `查在线` | 查询在线玩家列表（支持图片/Markdown 渲染） |
| `在线服务器` | 查看已连接的服务器名称 |
| `帮助` | 查看所有可用命令 |
| `执行` `<命令>` | 执行自定义命令（普通权限） |

### 管理员命令（需要管理员权限）

| 命令 | 说明 |
|------|------|
| `查管理` `<OpenId>` | 查询某用户是否为管理员 |
| `加管理` `<OpenId>` | 添加管理员 |
| `删管理` `<OpenId>` | 删除管理员 |
| `管理方式` `[QQ/手动/双重]` | 查看/设置管理员判定方式 |
| `添加白名单` `<玩家名>` | 添加玩家白名单 |
| `删除白名单` `<玩家名>` | 删除玩家白名单 |
| `查白名单` | 查看白名单列表 |
| `执行命令` `<命令>` | 执行服务器原生命令 |
| `管理员执行` `<命令>` | 以管理员权限执行自定义命令 |
| `全量` | 切换全量聊天转发开关 |
| `blockMotd` | 屏蔽本群 MOTD 查询 |
| `unblockMotd` | 解除本群 MOTD 屏蔽 |
| `我的背包` | 查看唯一绑定账户的背包（账户需在线） |
| `我的末影箱` | 查看唯一绑定账户的末影箱（账户需在线） |
| `背包查看` `<在线玩家名>` | 查看指定在线玩家背包（仅管理员） |
| `末影箱查看` `<在线玩家名>` | 查看指定在线玩家末影箱（仅管理员） |

背包和末影箱默认使用内置底图。若要更换壁纸，将 PNG 放到
`plugins/HuHoBotPenguin/inventory/backgrounds/`，再在 `config.yml` 中启用
`inventory.render.custom-background.enabled`。格子和人物区域的圆角遮罩会始终保留。

### 认证命令

| 命令 | 说明 |
|------|------|
| `认证` `[OpenId]` | 查看/设置用户认证状态 |
| `解除认证` `<OpenId>` | 解除用户认证（仅管理员） |

### MOTD 命令

| 命令 | 说明 |
|------|------|
| `motd <服务器地址>` | 查询 MC 服务器状态（图片 + Markdown 卡片） |

### AI Agent 命令

| 命令 | 说明 |
|------|------|
| `agent <任务描述>` | 触发 AI Agent 执行任务 |
| `newsession` | 清除 AI 会话上下文 |
| `stop` | 紧急停止所有 AI 任务 |

### 命令面板

启动时自动将命令同步到 QQ 群命令面板（上限 20 条，超出部分仅在 `/帮助` 中显示）。

---

## 配置详解

### 管理员判定模式

| 模式 | 说明 |
|------|------|
| `qq` | 使用 QQ 群主/管理员身份 |
| `config` | 仅使用配置文件中的 `admin.openids` 列表 |
| `both` | 两者皆可（默认） |

### 聊天格式占位符

**游戏→群（from-game）：**
- `{name}` —— 玩家名
- `{message}` / `{msg}` —— 消息内容

**群→游戏（from-group）：**
- `{name}` / `{nick}` —— 发送者昵称
- `{message}` / `{msg}` —— 消息内容

### 敏感词审核

支持两级过滤：

1. **本地正则过滤**：`filter-regex` 列表中的正则匹配内容会被替换为 `*`
2. **AI 二审**（可选）：配置 `audit.base-url` 后，本地未命中的内容会发送到 OpenAI 兼容接口进行二次审核

内置默认敏感词：傻逼、操你、色情、反动、赌博

### 自定义命令

```yaml
custom-commands:
  - key: "触发词"
    command: "huhobot run 命令模板"
    permission: 0    # 0=普通, 1=管理员
```

占位符：
- `{params}` —— 命令参数
- `{group}` —— 群 OpenID
- `{user}` —— 用户 OpenID
- `{0}`, `{1}` ... —— 按空格分割的参数

---

## 项目结构

```
PenguinAgent/
├── build.gradle.kts              # 根构建文件
├── settings.gradle.kts           # 模块配置
├── deps/qqpd-bot-java/           # QQ Bot SDK（git submodule）
├── common/Bot/                   # 平台无关核心模块
│   └── src/main/kotlin/cn/huohuas001/bot/
│       ├── HuHoBot.kt            # 核心接口（各平台实现）
│       ├── QClient.kt            # QQ 客户端单例
│       ├── NicknameManager.kt    # 昵称 ↔ openid 映射
│       ├── MenuManager.kt        # 命令面板自动同步
│       ├── agent/                # AI Agent 系统
│       │   ├── AgentManager.kt       # Agent 调度器
│       │   ├── AgentTools.kt         # 工具定义与执行
│       │   ├── AgentApiClient.kt     # OpenAI 兼容 HTTP 客户端
│       │   ├── AgentCommands.kt      # agent/newsession/stop 命令
│       │   └── GroupManagementApi.kt # QQ 群管理 API 封装
│       ├── events/               # 事件处理
│       │   ├── GroupMessageHandler.kt    # 群消息入口
│       │   └── commands/                 # 命令系统
│       │       ├── BaseCommand.kt            # 命令基类（反射分发）
│       │       ├── PublicCommands.kt         # 公开命令
│       │       ├── AdministrationCommands.kt # 管理员命令
│       │       ├── AuthenticationCommands.kt # 认证命令
│       │       ├── MotdCommands.kt           # MOTD 命令
│       │       ├── CustomCommandRegistry.kt  # 自定义命令注册
│       │       └── SensitiveFilter.kt        # 敏感词过滤
│       ├── provider/             # 提供者接口
│       │   ├── ConfigProvider.kt    # 配置提供者
│       │   ├── MessageProvider.kt   # 消息提供者
│       │   └── CommandProvider.kt   # 命令提供者
│       ├── state/                # 持久化状态
│       ├── web/                  # WebUI
│       │   ├── WebUiServer.kt      # HTTP 服务器
│       │   ├── WebUiSchema.kt      # 配置表单 Schema
│       │   └── WebUiPassword.kt    # 密码管理
│       └── tools/                # 工具类
├── server/Spigot/                # Spigot/Paper 平台适配
│   └── src/main/kotlin/cn/huohuas001/huhobotPenguin/spigot/
│       ├── HuHoBotSpigot.kt      # 插件主类
│       └── commands/
│           ├── AtCommand.kt      # /at 命令
│           ├── HuHoBotCommand.kt # /huhobot 命令
│           └── ...
└── server/AdapterCommon/         # 适配器公共层
```

---

## 构建与开发

### 环境要求

- **JDK 24**（构建时）/ **JDK 8+**（运行时，Spigot 模块）
- **Gradle 9.6.1**（使用项目自带的 `gradlew`）
- **Git**（子模块管理）

### 构建命令

```bash
# 完整构建
./gradlew clean build

# 仅构建 Spigot 产物
./gradlew :server-Spigot:shadowJar

# 构建产物位置
ls build/gather-jar/
```

### 模块说明

| 模块 | 说明 |
|------|------|
| `common-Bot` | 平台无关核心：QQ 客户端、群消息分发、指令、AI Agent、WebUI |
| `server-AdapterCommon` | 服务端适配公共层 |
| `server-Spigot` | Spigot/Paper 平台适配（活跃），含 JS / Python / Lua 脚本扩展 |
| `addon-GraalJs` | 可选引擎包：Spigot 的 GraalJS。不进主插件产物，放到 `plugins/HuHoBotPenguin/engines/` |
| `addon-GraalPy` | 可选引擎包：Spigot 的 GraalPy。不进主插件产物，放到 `plugins/HuHoBotPenguin/engines/` |
| `server-Nukkit` | Nukkit/PMMP 平台适配（待适配） |
| `server-Proxy` | Velocity/BungeeCord 代理适配（待适配） |
| `server-Allay` | Allay 平台适配（待适配） |

---

## Spigot 脚本扩展（JS / Python / Lua）

一个插件是 `addons/` 下的一个目录，不是单个文件。根上直接放的 `.js` / `.lua` / `.py` 不加载。
Lua 打在主 jar 里；GraalJS（约 34 MB）与 GraalPy（约 125 MB）拆成两个引擎包，不放也能启动：

| 入口 | 引擎 | 放在哪 |
|------|------|--------|
| `main.lua` | LuaJ | 主 jar 内 |
| `main.js` | GraalJS | `engines/HuHoBot-Engine-GraalJs-<版本>.jar` |
| `main.py` | GraalPy（Python 3） | `engines/HuHoBot-Engine-GraalPy-<版本>.jar` |

```bash
./gradlew :addon-GraalJs:shadowJar :addon-GraalPy:shadowJar
# 放到 plugins/HuHoBotPenguin/engines/
```

```
plugins/HuHoBotPenguin/addons/hello/
├── metadata.yaml          name / version / author / description / entry
├── _conf_schema.json      配置声明，实际值写到 addons/config/hello.json
└── main.js                或 main.lua / main.py
```

```
/huhobot scripts reload
/huhobot scripts reload hello
```

三种语言都注入 `Bird`、`Bukkit`、`server`、`plugin`、`config`、`kv`、`DATA_DIR`。
加载失败会撤掉已经登记的 addon 和它注册过的命令、监听器、定时任务。
两个目录写成同一个 `name` 会被拒绝。`"_enabled": false` 跳过该插件（算「跳过」不算「失败」）。

两个容易踩的点：

- **Lua 侧列表下标从 1 开始。** 命令参数和 `Bird` 返回的 `List` / `Map` 都会转成真正的 Lua table，
  所以是 `args[1]`、`#Bird:getDataKeys()`、`pairs(...)`，不是 0 起。JS 和 Python 保持各自的 0 起。
- **引擎 jar 的文件名带版本。** 和主插件版本不一致时启动会告警，脚本可能以难懂的方式失败，
  换同一次构建产出的 jar 即可。

详细说明见 [`docs/spigot-script-addons.md`](docs/spigot-script-addons.md)，
其中「[引擎桥 API](docs/spigot-script-addons.md#11-引擎桥-api)」一节记录了主插件与引擎 jar 之间的反射契约
（改 `GraalJsBridge` / `GraalPyBridge` 的方法签名时必须同步改 `ScriptAddonLoader`）。

脚本系统借鉴自 [birdlibraryapi](https://github.com/prach1121/birdlibraryapi)（Apache-2.0），详见[许可证](#许可证)一节。


## 版本历史

> 更早的版本见 [CHANGELOG.md](CHANGELOG.md) 与 [Releases](https://github.com/HuHoBot/PenguinAgent/releases)。

### v1.17.0（最新）

**新功能：**
- feat: 强制绑定 — 未绑定的玩家进服务器立刻被踢出并拿到 5 位验证码，在 QQ 群执行 `/绑定 <验证码>` 完成绑定后重新进入即可游玩（`binding.force-bind` / `binding.force-bind-groups`）
- feat: 解绑后立即断开在线会话，不必等到下次进服才被拦下
- feat: 配置文件文本级自动升级 — 新增配置项连同注释补进旧 `config.yml`，配置版本号升到 9

**优化：**
- opt: 绑定时的游戏内验证改为默认开启（已显式配置 `false` 的服务器不受影响）
- opt: 强制绑定开启时跳过绑定/解绑的白名单同步
- opt: WebUI 每个分节改用手写介绍文案，不再把字段说明拼成一长串
- opt: 踢出提示改为带颜色的完整信息，不再额外发送标题

**Bug 修复：**
- fix: 旧配置文件补不上新配置项 — `config.contains()` 会回落到 jar 模板默认值，导致模板新增的键永远补不进去
- fix: 补注释时产生重复注释
- fix: 强制绑定提示里的服务器名称不再带中括号

### v1.16.0

**新功能：**
- feat: 玩家死亡播报转发到 QQ 群（`player-events.death.*`），死亡原因自动生成中文描述，覆盖 14 种死法
- feat: WebUI「QQ 机器人」页面新增扫码连接入口，手机 QQ 扫码自动写入 AppID / Secret 并连接
- feat: 新增管理员命令 `/强制解绑` — 支持 MC 玩家名、`@某人`、openid、QQ 昵称
- feat: `/解除绑定` 增加二次确认（内联键盘，30 秒未选择视为取消）

**优化：**
- opt: 绑定改为按 openid 全局共享 — 一个群绑定后所有群通用，MC 玩家名全局唯一
- opt: 换绑角色时保留已切换的显示名称偏好
- opt: WebUI 先于 QQ 连接启动，未配置凭据时也能打开管理页面

**Bug 修复：**
- fix: QQ 扫码登录改为后台会话制，不再在主线程 `while(true)` 阻塞
- fix: 内联键盘不渲染（漏调 `RowBuilder.build()` 导致 `rows` 为空）
- fix: 按钮点击无响应、客户端提示「请求第三方失败 / 请求超时」（未按官方要求回应互动事件）
- fix: 绑定数据自动从旧的按群分组格式迁移为按 openid 索引

### v1.15.1

**Bug 修复：**
- fix: QQ 指令面板同步报 30019「面板版本冲突」— 原位更新按官方文档补传 `panel.version`，冲突时重读版本重试
- fix: 面板同步并发自撞与撞限频 — 同步入口串行化，两次同步最小间隔 6 秒（官方写接口 10 QPM）
- improve: 面板同步错误提示区分 30013 / 30019 / 40030009 并给出排查建议

### v1.15.0

**新功能：**
- feat: 脚本扩展系统（仅 Spigot）— 用 JavaScript / Lua / Python 写脚本插件，无需编译
- feat: 目录式脚本插件 — `addons/<名字>/` 下放 `main.js` / `main.lua` / `main.py` 加 `metadata.yaml`
- feat: `Bird` 桥 — 脚本可注册 Bukkit 事件、游戏内命令（含 Tab 补全）、定时任务、HTTP 回调、QQ 群命令
- feat: `/huhobot scripts reload [目录名]`
- feat: 脚本配置与数据 — `_conf_schema.json` 声明默认值，`config` / `kv` 键值空间，`setData` 持久化
- feat: 跨脚本事件总线 — `Bird.on` / `Bird.emit`
- feat: 引擎按需安装 — LuaJ 打进主 jar；GraalJS、GraalPy 拆成独立包从 `engines/` 加载

**Bug 修复：**
- fix: LuaJ 重载解析、容器转 userdata、宿主对象回调解包等一批绑定层问题
- fix: 脚本重载时同分重载选择不确定，改为按参数类型具体程度择优
- fix: 脚本加载失败时未回滚已登记内容，重载不再留下幽灵命令

### v1.14.0

**新功能：**
- feat: WebUI 附属插件中心 — 从附属插件中心 API 拉取列表、搜索、看详情、一键下载安装、删除已下载插件
- feat: 群列表为空或未收录时，收到任意消息即自动把群 OpenID 写入 `bot.groups`

**优化：**
- opt: WebUI 体验优化 — Toast 改为顶部飘窗、面板错峰淡入、点击涟漪反馈、群名称加载态
- opt: 群服互通默认格式恢复为 `[游戏] {name}: {message}` / `[QQ] {name}: {message}`
- opt: 绑定后不再自动把 QQ→游戏显示名切成 MC 名，需要时用 `/MC显示名称 MC` 切换
- opt: PlaceholderAPI 解析改用在线玩家实例，玩家类占位符可正常返回

### v1.13.0

**新功能：**
- feat(#7): QQ 群接入简化 — WebUI 群列表显示群名称与 OpenID 尾号、可点击复制，名称缓存 30 分钟并持久化
- feat(#8): 版本更新提醒 — 启动与 `/版本` 检查 GitHub 正式 Release，走 gh-proxy 代理国内可用
- feat(#9): 接入 PlaceholderAPI — 纯反射接入无编译期依赖，覆盖双向转发与各类模板
- feat(#10): Agent 获取类工具结果不再刷屏 — 新增 `agent.hide-fetch-results`（默认开启）

### v1.12.0

**新功能：**
- feat: 集成 bStats 统计（插件 ID `34268`），库重定位到 `cn.huohuas001.bstats`，不与其他插件冲突

### v1.11.0

**新功能：**
- feat: 所有带键盘的消息发送后 30 秒自动撤回，点击按钮后可立即撤回对应卡片
- feat: AI Agent 手动审批 30 秒未处理自动拒绝并撤回卡片，超时后继续执行后续流程并说明操作已被拒绝

**Bug 修复：**
- fix: qqpd-bot-java 消息撤回接口始终返回失败（`RestApi` 依赖未绑定导致 DELETE 请求被跳过）
- fix: 撤回失败时输出 HTTP 状态码与响应体

### v1.10.0

**新功能：**
- feat(inventory): 背包与末影箱渲染整合（6 项）— 3D 玩家模型渲染、离线背包快照、默认皮肤库解析、护甲材质与附魔光泽资源
- feat(bot): QQ 命令面板与命令体验整合（#6）— 面板分页、重复面板清理、20 条上限截断、命令按权限分组、`pushMenu` / `priority` 支持

**Bug 修复：**
- fix: 背包查询名字大小写不匹配时正确解析在线玩家
- fix: 末影箱渲染失败与无离线快照的错误提示区分
- fix: `onServerThread` 增加 10s 超时，背包快照序列化移出主线程
- fix: 瘦手臂（slim）胸甲 UV 宽度修正
- fix: `registerBotCommand` / `unregisterBotCommand` 面板同步改为异步

### v1.9.0

**新功能：**
- feat: 首次启动自动扫码登录 — 控制台打印二维码，手机 QQ 扫码后自动写入 AppID/Secret 到 config.yml

**优化：**
- opt: jar 文件名统一为 `PenguinAgent.jar`
- opt: Gradle 配置缓存清理，确保 plugin.yml 版本号正确替换

### v1.8.0

**新功能：**
- feat: `/send <消息>` — 游戏内向 QQ 群发送消息，所有人可用
- feat: `/hb reload` 完整重启 — 注销所有命令 → 重新加载配置 → 重启 QQ 客户端

**Bug 修复：**
- fix: plugin.yml 重复 permissions 段导致 `/qqbind` 权限丢失
- fix: 禁用的命令不再注册到 QQ 指令面板
- fix: 命令面板同步日志增强，便于排查问题

### v1.6.0

**新功能：**
- feat: WebUI 端口可通过 `config.yml` 中的 `webui-port` 配置（默认 5678）
- feat: 配置文件版本升级到 v7，旧版自动迁移添加 `webui-port` 默认值

### v1.5.0
- feat: `MsgPack` / `MsgPackFactory` — MsgPack 消息序列化
- feat: `OnBotRecvMsg` / `OnBotCommand` 事件 — 第三方插件可监听 QQ 消息与命令事件
- feat: `AddonManager` 扩展注册中心 — 管理已安装扩展及其命令
- feat: `registerAddon(name, version, description, author)` — 注册扩展元数据
- feat: `registerBotCommand(addonName, key, command, permission, pushMenu)` — 注册扩展命令并关联归属
- feat: `/addons` 命令 — 查看已安装扩展列表
- feat: `/帮助` 三段式显示（内置命令、自定义命令、扩展命令），按 command key 全局去重
- feat: `MessageProvider.onBotReceivedGroupMessage()` / `onBotCommand()` 回调

**Bug 修复：**
- fix: `/帮助` 命令重复显示问题（旧 API 注册的命令不再重复归类）
- fix: QQ markdown 转义玩家名中 `_text_` 被识别为斜体

### v1.4.0

**新功能：**
- feat: `/背包查看 <玩家名>` — 查看指定在线玩家背包内容（PNG 图片渲染，仅管理员）
- feat: Faithful 32x 贴图包集成 — 物品图标与方块预览使用 Faithful 32x 纹理
- feat: 玩家皮肤预览 — 背包图片中显示玩家正面皮肤
- feat: 等距3D方块预览 — 有面贴图的方块自动合成等距3D效果
- feat: `/qqbind` 权限改为所有玩家可用（默认 true）

**变更：**
- change: `/背包查看` 仅管理员可用

**Bug 修复：**
- fix: 命令方块等动画贴图渲染异常（自动裁剪为第一帧）
- fix: 铁砧等无面贴图方块使用基础贴图做2D图标
- fix: 箱子贴图从 overrides 目录正确加载
- fix: QQ markdown 转义玩家名中 `_` 字符

### v1.3.0

**新功能：**
- feat: 命令黑名单（`command-blacklist`）— 禁止通过 `/执行` 或 Agent `run_command` 运行指定服务器命令
- feat: `/版本` 命令输出中显示文档链接
- feat: WebUI 新增「绑定」和「命令黑名单」配置分区
- feat: `ConfigUpgrader.upgradeValues()` 支持版本化升级已有配置字段
- feat: `BaseCommand.handleMessage()` 实际执行 `onlyAdmin` 拦截

**变更：**
- change: motd `post-img` 和 `use-markdown` 默认值改为 `true`
- change: AI Agent 命令（`/agent`、`/newsession`、`/stop`）标记为仅管理员
- change: 配置文件版本升级到 v6，旧版自动迁移 motd 默认值

**Bug 修复：**
- fix: 自定义命令执行时不再转发到游戏
- fix: 文档许可证从 GPL v3 更正为 AGPL v3
- fix: 文档网址从 `huhobot.txssb.cn` 更正为 GitHub Pages

### v1.3.0-beta.2

**Bug 修复：**
- fix: 游戏命令执行结果以纯文本发送，不再被 QQ 渲染为 Markdown 格式
- fix: SDK `InterAction.getEnvType()` NPE 修复 — `chat_type` 空值检查

**新功能：**
- feat: QQ→游戏消息支持 `&` 颜色符号转换（如 `&a绿色` → 绿色文字）

**文档：**
- docs: 文档全面改版适配 HuHoBotPenguin 分支

### v1.3.0-beta.1

**上游同步（PenguinClient 功能合入）：**
- feat: `FaceEmojiParser` —— QQ 表情标签转可读文本
- feat: `MessageAttachmentParser` —— 语音/图片/视频/文件标签转可读文本
- feat: `@Commands` 注解重构 —— `command`/`describe`/`onlyAdmin` 字段 + `RegisteredCommand` 数据类
- feat: `BaseCommand.DispatchResult` 枚举（HANDLED/NOT_HANDLED/CUSTOM_COMMAND）
- feat: `CustomCommandRegistry` 运行时注册/注销 + 面板自动同步
- feat: `MenuManager` 改为 HTTP API 动态面板（启动时同步，上限 20 条命令）
- feat: `ConfigProvider` 新增 `isAuthenticationEnabled()`、`commandMenuList()`、motd.md 模板
- feat: `QClient.syncGroupPanels()` 启动时一次性同步面板，运行时注册不再重复同步
- feat: `motd.md` Markdown 模板内置，首次启动自动解压
- feat: 动态 `/帮助` 命令 —— 自动列出所有已注册命令（含自定义命令）
- feat: `blockMotd` / `unblockMotd` —— 按群屏蔽 MOTD 查询
- feat: `GroupSettingsRepository` 新增 `motdBlocked` 群级设置
- feat: `BaseCommand.allCommands()` 全局命令注册表

**Bug 修复：**
- fix: 绑定游戏账号后自动添加白名单（QQ 直接绑定 + 游戏验证两条路径均已修复）
- fix: `HuHoBotSpigot.companion` 静态实例，支持 `getInstance()` 调用
- fix: 命令面板超出上限时日志提示，仅同步前 20 条命令

**已知限制：**
- 本地 QQ Bot SDK 版本不含 `MsgPack`/`OnBotRecvMsg`/`OnBotCommand` 事件，暂不支持第三方插件监听 QQ 消息事件

**群服互通 @提及：**
- feat: `/at` 命令 —— 游戏内发送 QQ @消息，支持 Tab 补全昵称
- feat: QQ→游戏 @提及蓝色高亮（`§9@玩家名`）+ 叮音效提醒
- feat: `NicknameManager` 昵称 ↔ openid 双向映射，持久化到 `nicknames.dat`
- feat: `resolveAtMentions` 自动将 `@昵称` 转为 QQ `<@openid>` 格式
- feat: `escapeMarkdown` 转义玩家名中 `_` 等特殊字符，防止被识别为斜体

**WebUI：**
- feat: 暗色简约风格 WebUI，14 个配置分组，覆盖所有配置项
- feat: 启动时自动生成 24 位随机密码
- fix: 侧边栏空 bug（renderNav 移到 schema 加载后调用）
- fix: 保存后值丢失 bug（保存后重新读取配置刷新 UI）

**AI Agent 群管理：**
- feat: 禁言到期时间服务端计算，不依赖 AI
- feat: @提及识别 —— AI 可直接使用 @成员的用户名进行操作
- feat: 用户名显示 —— Agent 操作结果显示管理员/成员真实用户名
- feat: 审批/拒绝通知显示操作管理员用户名
- fix: `get_group_members` API 移除（无权限）
- fix: 禁言、审批等工具 `group_openid` 标注"可选，已自动绑定当前群"

**Bug 修复：**
- fix: openid 被当作昵称存储导致 Tab 补全显示 openid
- fix: QQ→游戏转发时 openid 被当作发送者显示名
- fix: NicknameManager.load() 自动跳过昵称是 openid 的脏数据
- fix: plugin.yml `/at` 命令描述修正

### v1.2.0-alpha.1

- 初版 WebUI 图形化配置
- AI Agent 群管理功能（禁言、入群审批、自动审批策略）
- @提及识别与用户名显示

### v1.1.0-alpha.3

- AI Agent 系统初版
- SKILL 按需加载系统
- `/motd` 服务器状态查询
- `/stop` 紧急停止命令
- 指令面板自动同步

---

## 许可证

本项目采用 [GNU Affero General Public License v3.0](LICENSE) 许可证。

### 第三方资源

**脚本扩展系统**（`addons/<名字>/main.js` / `main.lua` / `main.py` 与 `Bird` 桥）借鉴并移植自
[birdlibraryapi](https://github.com/prach1121/birdlibraryapi)（Apache-2.0），原作者 prach1121。
`BirdScriptApi` 由该项目的 `BirdAPI` 移植而来，并按 Spigot 1.16+（不用 Adventure API）与
HuHoBot 附属插件注册做了适配；`ScriptAddonLoader`、`ScriptPackage`、`AddonManifest`、
`ScriptLoadResult`、`LoadedScript` 等类同样参考了它的加载与脚本生命周期设计。
源码文件头部保留了原始出处与许可证声明。

背包查看功能使用 [Faithful 32x](https://faithfulpack.net/) 贴图包（[Faithful License v3](server/Spigot/src/main/resources/inventory/faithful32x/LICENSE.txt)）。

这意味着你可以自由使用、修改和分发本软件，但：
- 修改后的版本必须以相同许可证发布
- 如果通过网络提供服务，必须向用户提供源代码
- 包含来自贡献者的专利授权

---

[![bStats](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fbstats.org%2Fapi%2Fv1%2Fplugins%2F34268%2Fcharts%2Fservers%2Fdata%3FmaxElements%3D1&query=%24%5B0%5D%5B1%5D&label=servers&color=blue)](https://bstats.org/plugin/bukkit/34268)
[![players](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fbstats.org%2Fapi%2Fv1%2Fplugins%2F34268%2Fcharts%2Fplayers%2Fdata%3FmaxElements%3D1&query=%24%5B0%5D%5B1%5D&label=players&color=blue)](https://bstats.org/plugin/bukkit/34268)
