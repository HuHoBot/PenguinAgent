# HuHoBot Penguin v1.2.0-alpha.2

## 新功能

- feat(at): 新增 `/at <昵称> <消息>` Minecraft 命令，游戏内发送 QQ @消息，支持 Tab 补全
- feat(at): QQ→游戏 @提及蓝色高亮（`§9@玩家名`）+ 叮音效提醒
- feat(nickname): NicknameManager 昵称 ↔ openid 双向映射，持久化到 nicknames.dat，重启不丢失
- feat(nickname): resolveAtMentions 自动将 `@昵称` 转为 QQ `<@openid>` 格式
- feat(nickname): escapeMarkdown 转义玩家名中 `_` 等特殊字符，防止被识别为斜体
- feat(webui): 暗色简约风格 WebUI，14 个配置分组，覆盖所有配置项
- feat(webui): 启动时自动生成 24 位随机密码，日志打印一次
- feat(agent): 禁言到期时间服务端计算，不依赖 AI
- feat(agent): @提及识别 —— AI 可直接使用 @成员的用户名进行操作
- feat(agent): 用户名显示 —— Agent 操作结果显示管理员/成员真实用户名
- feat(agent): 审批/拒绝通知显示操作管理员用户名
- feat(readme): 全面重写 README，补充所有功能、配置、命令、项目结构说明

## 改进

- fix: openid 被当作昵称存储导致 Tab 补全显示 openid（NicknameManager.put 拒绝 openid 作为昵称）
- fix: QQ→游戏转发时 openid 被当作发送者显示名（forwardFullGroupMessage 解析真实昵称）
- fix: NicknameManager.load() 自动跳过昵称是 openid 的脏数据
- fix: plugin.yml `/at` 命令描述修正
- fix: WebUI 侧边栏空 bug（renderNav 移到 schema 加载后调用）
- fix: WebUI 保存后值丢失 bug（保存后重新读取配置刷新 UI）
- fix: resolveAtMentions 处理直接输入的 @openid（转为昵称或去掉）
- fix: get_group_members API 移除（无权限 11253）
- fix: 禁言、审批等工具 group_openid 标注"可选，已自动绑定当前群"

## 依赖

- 基于 [qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java) (AGPL-3.0)
