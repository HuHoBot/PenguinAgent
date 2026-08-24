# HuHoBotPenguin 部署与配置教程

在使用 HuHoBotPenguin 前，需要完成 QQ 开放平台机器人的创建与申请：

1. 访问 [QQ 开放平台官网](https://q.qq.com)
2. 登录并创建/申请一个机器人。
3. 在配置时，连接方式请选择 **"其他方式连接"**。
4. 记录 Bot 的 `app-id` 与 `secret` 密钥，供后续配置使用。

## 配置设置

打开插件的 `config.yml` 配置文件，按照如下结构配置 Bot 基础信息并绑定群聊。

### Bot 基础信息与群聊绑定

```yaml
bot:
  app-id: "_AppID"
  secret: "Your_Secret"
  name: HuHoBot
  groups:
    - "66535AF0D529..."
    - "DFD5BB8CBA5C..."
```

**配置要点说明：**

- **群号填写：** 在群聊中发送 `/查信息` 获取当前群的 OpenId，并将获取到的群号依次填入 `groups` 表中。
- **群号规范：** 填入 `groups` 列表中的群号时，务必加上双引号（例如：`"66535AF0D529..."`）。
- **隐藏日志：** `suppress-console-output: true` 可屏蔽 QQ Bot SDK 的调试输出。

### 消息互通格式

```yaml
chat-format:
  from-game: "[游戏] {name}: {message}"
  from-group: "[QQ] {name}: {message}"
  post-chat: true
  start-with: "#"
```

- `from-game`：游戏消息转发到 QQ 群的格式
- `from-group`：QQ 群消息转发到游戏的格式
- `post-chat: true`：开启群→游戏转发
- `start-with: "#"`：只有以 `#` 开头的游戏消息才转发到 QQ（留空表示全部转发）

### 玩家事件通知

```yaml
player-events:
  join:
    enabled: true
    format: "[游戏] {name} 加入了服务器"
  quit:
    enabled: true
    format: "[游戏] {name} 离开了服务器"
```

可用占位符：`{name}`、`{player}`、`{server}`、`{platform}`

## 绑定系统配置

绑定系统允许 QQ 用户与 Minecraft 玩家名绑定，支持自动白名单同步。

```yaml
binding:
  # 绑定时是否需要游戏内 /qqbind 验证；关闭时直接绑定无需游戏内操作
  require-game-verification: false
```

- `require-game-verification: false`（默认）：直接绑定，自动添加白名单
- `require-game-verification: true`：需要玩家进入服务器执行 `/qqbind <验证码>` 确认

### 白名单同步

```yaml
whitelist:
  add-command: "whitelist add {name}"
  del-command: "whitelist remove {name}"
```

绑定/解绑时自动执行白名单命令，`{name}` 会被替换为玩家名。

## AI Agent 配置

AI Agent 让管理员通过 QQ 群直接管理服务器（执行命令、查日志、管理插件等）。

```yaml
agent:
  enabled: false
  base-url: ""
  api-key: ""
  model: "gpt-4o-mini"
  # auto: 自动执行命令
  # manual: 执行前需管理员审批（默认）
  command-mode: "manual"
```

- `enabled`：启用 AI Agent 功能
- `base-url`：OpenAI 兼容的 Chat Completions 接口地址
- `api-key`：API 密钥
- `model`：使用的模型名称
- `command-mode`：`auto`（AI 直接执行）或 `manual`（管理员审批后执行）

详细文档请查看 [AI Agent](../Agent/index.md)。

## MOTD API 与在线查询配置

### MOTD API 启用配置

若要启用服务器状态及 MOTD 展示，需要在配置中定位到 `motd` 字段下的 `api` 项，并将其值换为标准 API 接口：

```yaml
motd:
  server-ip: "127.0.0.1"
  server-port: 25565
  api: "http://motd.txssb.cn/api/app_img?ip={ip}&port={port}&dark=true&lang=zh-CN"
  text: "共{online}人在线"
  post-img: true
  use-markdown: true
```

**参数与规则说明：**

- **保留占位符**：URL 中的 `{ip}` 和 `{port}` 必须保持原样，**不可变动**。
- **主题配色调节：**
  - `dark`：深色模式
  - `dark=false`：浅色

**详细文档与调试**：参考[官方文档](https://motd.txssb.cn/docs)，或使用其提供的在线测试器测试对应的 URL。

- **兼容性限制：** 暂不支持 **Simpfun (简幻欢)** 节点的状态查询。

### 在线查询 Markdown 权限说明

使用 `/查在线` 时，请务必在配置中将 `use-markdown` 设置为 `true`，否则无法正常发送信息。

## 管理员配置

```yaml
admin:
  # qq: 仅群主/QQ 群管理员；config: 仅手动名单；both: 任一满足
  mode: both
  openids: []
```

- `mode: qq`：仅 QQ 群主/管理员
- `mode: config`：仅 `openids` 列表中的用户
- `mode: both`：任一满足即可

也可在群内使用 `/管理方式` 命令实时切换。

## 自定义命令配置

```yaml
custom-commands:
  - key: "公告"
    command: "say {params}"
    permission: 0
    push-menu: true
```

- `key`：命令关键词（`/执行 公告 ...`）
- `command`：发送到服务器的实际命令，支持占位符
- `permission`：0 = 所有人，1 = 仅管理员
- `push-menu`：是否同步到 QQ 指令面板

占位符详见 [命令列表](../command.md#自定义命令)。

## 命令开关

```yaml
commands:
  查信息: true
  查管理: true
  加管理: true
  # ... 其他命令
  agent: true
```

将对应命令设为 `false` 可禁用该命令。

## 敏感词审核（可选）

```yaml
audit:
  base-url: ""
  api-key: ""
  model: "gpt-4o-mini"
```

配置后，消息会先经本地 `sensitive-words/*.txt` 过滤，命中后再调用 OpenAI 兼容接口二审。

## WebUI 管理面板

插件启动时会在控制台输出 WebUI 地址和密码。浏览器访问即可通过图形界面管理所有配置项，无需手动编辑 YAML 文件。
