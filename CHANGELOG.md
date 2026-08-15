# HuHoBot Penguin v1.1.0-alpha.1

## 新功能

- feat(agent): 新增 AI Agent 系统，管理员通过 `@机器人 /agent 任务描述` 触发 AI 执行服务器管理任务
- feat(agent): AI 通过 function call 获取插件列表、命令帮助、执行命令、读取日志
- feat(agent): 命令执行支持手动审批模式（按钮卡片同意/拒绝，仅管理员与群主可审批）
- feat(agent): 新增 SKILL 按需加载系统（components/give/item/summon/data/loot/clear）
- feat(agent): 新增 `/newsession` 命令手动清除 AI 会话上下文
- feat(agent): AI 推理模型支持（reasoning_content 思考过程输出）
- feat(command): 原版命令帮助通过反射 CraftServer commandMap 获取
- feat(spigot): 新增 `read_server_logs` 工具读取服务端日志

## 改进

- refactor(agent): 会话上下文复用，同一用户连续对话保留历史
- refactor(agent): 错误输出清理，只显示根本原因（Brigadier CommandSyntaxException）
- refactor(agent): 消息无截断，超长内容分块发送
- refactor(agent): 上下文窗口管理（MAX_CONTEXT_MESSAGES=40）
- fix(agent): 命令输出剥离 Minecraft 格式化代码（§x），避免 QQ 群显示乱码
- fix(agent): 修复 interaction 回调 405 错误日志噪音
- fix(spigot): BukkitConsoleSender 改用真实 console sender 执行命令

## 依赖

- 基于 [qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java) (AGPL-3.0)
