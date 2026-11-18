package cn.huohuas001.huhobotPenguin.spigot.manager

import java.io.File

/**
 * 在插件数据目录生成 CONFIG.md 使用教程（只在不存在时写入，用户修改不会被覆盖）。
 * 解决"没有教程、不知道该填什么"的问题。
 */
object ConfigGuide {

    fun ensure(dataFolder: File) {
        val target = File(dataFolder, "CONFIG.md")
        if (target.isFile && target.length() > 0) return
        runCatching {
            dataFolder.mkdirs()
            target.writeText(GUIDE, Charsets.UTF_8)
        }
    }

    private val GUIDE = """
# HuHoBot Penguin 配置教程

> 本文件由插件自动生成，不会覆盖你手动修改过的版本。删除后重启服务器可重新生成。

## 目录结构

```
plugins/HuHoBotPenguin/
├── config.yml            主配置（所有开关都在这里）
├── CONFIG.md             本教程
├── groups/<群OpenID>/    按群覆盖的指令面板配置（可选）
├── permission-groups.yml 超级权限组（可手改）
├── timed-tasks.yml       定时任务（可手改）
├── Markdown/             查在线/状态通知的文字模板
├── online-history.json   在线人数历史（自动维护，勿手改）
├── update-state.json     OTA 状态（自动维护）
└── tts-voices/           音色克隆参考音频（mp3/wav，≤10MB）
```

## 1. 让机器人上线

`config.yml` → `bot`：
- 有开放平台应用：填 `app-id` 与 `secret`；
- 没有：留空，启动时控制台/WebUI 会显示扫码授权二维码。

WebUI 地址在控制台输出（默认 127.0.0.1:5678），密码用 `/hb password <新密码>` 修改。

## 2. 查在线图片

```yaml
online-list:
  enabled: true
  page-size: 27              # 每页玩家数
  max-render-players: 27     # 单图最多画多少玩家，超出部分用 Markdown 文字补发
  render:
    theme: "dark"            # dark 深色科技风 / light 亮色清爽风
    events: true             # 节日横幅（春节/中秋/国庆/圣诞/APEC 等）
    custom-lines: []         # 自定义信息行，见下方说明
  display:
    address: ""              # 图上显示的地址，写纯域名（如 play.example.com），不要带端口
    prefix: true             # 显示 LuckPerms 称号
    suffix: true             # 显示 LuckPerms 后缀
    name-color: ""           # 玩家名颜色，如 gold / #FFD700
    admin-groups: []         # 这些 LP 组按管理员金色高亮，如 [admin, 管理]
  skin:
    enabled: true
    avatar-api: "https://mc-heads.net/avatar/{name}/64"   # 离线模式头像兜底，留空禁用
```

### 自定义信息行 custom-lines

显示在地址下方，支持颜色（§代码 / &#RRGGBB / hex / MiniMessage）：

```yaml
  render:
    custom-lines:
      - "&#35E0C0服务器: 星昉雲宇 | 腐竹: SmallLinkBoy"
      - "<gradient:#4FE3C1:#8B7CF6>欢迎加入我们的交流群</gradient>"
```

不配置就完全不占空间（自动排版）。最多显示 3 行。

### 关于头像

优先级：玩家皮肤（正版/皮肤插件）→ SkinsRestorer（装了就自动用）→ avatar-api（默认 mc-heads，离线名也有头像）→ 内置 Steve/Alex 头像。
**离线模式且没装任何皮肤插件时，玩家本身没有皮肤数据，任何插件都拿不到"他的"皮肤**，此时显示的是同名正版账号头像或默认头像。

## 3. 命令映射与切换

```yaml
command-aliases:
  map:            # 原名与新名都能用
    查在线: 在线
  rename:         # 只有新名能用，原名停用
    执行: cmd
```
命令名不带斜杠；面板会自动显示新名字。改完 `/hb reload` 生效。

## 4. 权限组

`permission-groups.yml`：
```yaml
groups:
  vip:
    commands: [查在线, 我的背包]      # 组内成员可用的命令（留空 = 不限）
    members: [<QQ openid>]
```
按群覆盖面板：WebUI「命令与 QQ 面板」选择群后单独配置，保存到 `groups/<群>/config.yml`。

## 5. 聊天 AI（群员闲聊，只读）

```yaml
chat-ai:
  enabled: true
  base-url: "https://api.deepseek.com/v1"
  api-key: "sk-..."
  model: "deepseek-chat"
  trigger: "ai"       # 群里 @机器人 后以 ai 开头触发，例如「ai 今天天气怎么样」
```
注意：QQ 客户端会拦截 `/` 开头的未知指令，所以默认不带斜杠。
聊天 AI 只有只读工具（联网搜索/服务器状态/在线列表/TTS），**不能执行任何命令**。
同一群共享一条聊天记录，不同群互相隔离；聊天记录与工作 Agent 完全隔离。

## 6. 工作 Agent（可执行操作）

`/agent <任务描述>` 触发。审批模式 `agent.command-mode`：
- `manual`：敏感操作发按钮卡片，管理员点同意才执行；
- `ai`：由 AI 审批（干净上下文复核，拒绝时给出理由）；
- `auto`：直接执行（慎用）。

## 7. TTS 语音（AI 可调用 speak_tts）

```yaml
tts:
  enabled: true
  base-url: "https://api.xiaomimimo.com/v1"
  api-key: "..."
  public-base-url: "https://你的公网地址:5678"   # QQ 服务器要能访问（端口映射/反代到 WebUI）
  preset-model: "mimo-v2.5-tts"                  # 预制音色；留空=AI 看不到该能力
  natural-model: "mimo-v2.5-tts-voicedesign"     # 自然语言音色
  clone-model: "mimo-v2.5-tts-voiceclone"        # 音色克隆（音频放 tts-voices/）
  max-per-turn: 2
```
官方富媒体语音要求 silk 格式；wav/mp3 在部分环境可直接播放，被拒时需配合转码。

## 8. 服务器通知与性能预警

```yaml
server-notify:
  shutdown: { enabled: true }
  crash:    { enabled: true }
  performance:
    enabled: true
    tps-threshold: 15.0
    memory-threshold: 0.9
```
关机/重启/崩溃会发 QQ 群（带服务器、平台、时间信息）；性能连续 3 分钟异常会预警，恢复后通知一次。

## 9. 强制 OTA（应急更新）

```yaml
update:
  manifest-url: ""      # 指向仓库里的 versions.json
  check-hours: 6
```
清单格式：
```json
{ "minimumVersion": "1.23", "downloadUrl": "https://.../HuHoBot-Penguin-1.23.jar", "notes": "修复 XX" }
```
当前版本 ≥ minimumVersion 时什么都不做；需要更新时下载到 `update/` 目录，**同一版本只下载一次**，
重启服务器后用它替换 plugins 里的旧 jar。

## 10. 消息发送策略

```yaml
message:
  send-policy: "auto"   # auto=5 分钟内优先被动回复，省每日主动额度；active=总是主动
```

## 11. 语言

```yaml
language: "zh_CN"   # 或 en_US
```
""".trimIndent()
}
