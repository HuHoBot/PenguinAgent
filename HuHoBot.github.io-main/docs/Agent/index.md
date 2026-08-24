# AI Agent

AI Agent 是 HuHoBotPenguin 的增强功能，让管理员可以通过 QQ 群直接管理 Minecraft 服务器。

通过 `/agent` 命令启动 AI 会话，AI 可以：

- 查看服务器插件列表和命令帮助
- 执行服务器命令（如白名单、封禁等）
- 读取服务器日志
- 管理 QQ 群设置（审批入群、禁言等）

## 启用

在 `config.yml` 中配置：

```yaml
agent:
  enabled: true
  base-url: "https://api.openai.com/v1"
  api-key: "your-api-key"
  model: "gpt-4o-mini"
  command-mode: "manual"
```

需要 OpenAI 兼容的 Chat Completions 接口（支持 function calling）。

## 命令

| 命令 | 参数 | 说明 | 权限 |
|---|---|---|---|
| `/agent` | `(任务描述)` | 启动 AI Agent 执行任务 | 管理员 |
| `/newsession` | — | 清除 AI 会话上下文 | 管理员 |
| `/stop` | — | 紧急停止所有 AI 任务 | 管理员 |

## 执行模式

### 自动模式（auto）

AI 直接执行命令，无需人工审批。适合信任 AI 判断的场景。

```
玩家：/agent 查看当前在线玩家
HuHoBot：🤖 正在执行...
HuHoBot：当前在线玩家：3人
HuHoBot：- Steve
HuHoBot：- Alex
HuHoBot：- Notch
```

### 手动模式（manual）

执行命令前需要管理员在 QQ 按钮卡片上点击审批。适合需要人工确认的场景。

```
玩家：/agent 重启服务器
HuHoBot：🤖 AI 建议执行以下命令：
HuHoBot：[重启服务器] [取消]
（管理员点击"重启服务器"按钮后执行）
```

## 服务器管理工具

AI Agent 可以调用以下工具：

| 工具 | 说明 |
|------|------|
| `get_server_plugin_list` | 获取服务器已安装插件列表 |
| `get_command_help` | 获取指定命令的帮助信息 |
| `run_command` | 执行服务器命令 |
| `read_server_logs` | 读取服务器日志 |
| `load_skill` | 加载 Minecraft 命令语法文档 |

## QQ 群管理工具

AI Agent 还支持以下 QQ 群管理操作：

| 工具 | 说明 |
|------|------|
| `get_group_info` | 获取群信息 |
| `get_bot_state` | 获取机器人状态 |
| `get_join_requests` | 获取入群申请列表 |
| `approve_join_request` | 审批入群申请 |
| `get_mute_status` | 获取禁言状态 |
| `set_member_mute` | 设置成员禁言 |
| `list_auto_approve_policies` | 列出自动审批策略 |
| `create_auto_approve_policy` | 创建自动审批策略 |
| `update_auto_approve_policy` | 更新自动审批策略 |
| `delete_auto_approve_policy` | 删除自动审批策略 |
| `update_whitelist_users` | 更新白名单用户 |

## SKILL 系统

AI Agent 支持按需加载 Minecraft 命令语法文档，帮助 AI 更准确地生成命令。

可用的 Skill 文档：

| Skill | 说明 |
|-------|------|
| `skill-components` | Minecraft 组件语法（JSON 文本组件） |
| `skill-give` | /give 命令语法 |
| `skill-item` | 物品 ID 和数据值 |
| `skill-summon` | /summon 命令语法 |
| `skill-data` | 数据标签和 NBT 语法 |
| `skill-loot` | /loot 命令语法 |
| `skill-clear` | /clear 命令语法 |

AI 会在需要时自动加载相关 Skill，也可以通过 `load_skill` 工具手动加载。

## 会话管理

- 每个用户独立的会话上下文
- 最大保留 40 条消息（超出后自动裁剪最早的消息）
- 同一用户重复 `/agent` 会复用之前的会话
- `/newsession` 清除上下文，开始全新对话
- 长时间无操作会自动清理会话

## 注意事项

- AI Agent 仅管理员可用
- 建议在 `manual` 模式下使用，避免 AI 误操作
- AI 的回复会自动分割为多条消息（每条最长 3000 字符）
- 支持推理模型（reasoning model），会自动提取思考过程
