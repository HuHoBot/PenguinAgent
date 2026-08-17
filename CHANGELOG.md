# HuHoBot Penguin v1.2.0

## 新功能

- feat(binding): QQ-MC 角色绑定系统 — `/绑定 <游戏名>` 将 QQ 账号与 Minecraft 玩家绑定，自动同步白名单
- feat(binding): `/解除绑定`（`/解绑`）解除绑定关系并移除白名单
- feat(binding): `/MC显示名称 MC|QQ` 控制 QQ→游戏方向的显示名（MC 名或 QQ 昵称）
- feat(binding): `/QQ显示名称 MC|QQ` 控制游戏→QQ方向的显示名（MC 名或 QQ 昵称）
- feat(at): 游戏内提到绑定玩家名时自动转为 QQ 蓝色 @ 通知（无需 @ 前缀）
- feat(at): `/at <昵称> <消息>` Minecraft 命令，游戏内发送 QQ @消息，支持 Tab 补全
- feat(at): QQ→游戏 @提及蓝色高亮（`§9@玩家名`）+ 叮音效提醒
- feat(nickname): NicknameManager 昵称 ↔ openid 双向映射，持久化到 nicknames.dat
- feat(nickname): resolveAtMentions 自动将 `@昵称` 转为 QQ `<@openid>` 格式
- feat(nickname): escapeMarkdown 转义玩家名中 `_` 等特殊字符
- feat(webui): 暗色简约风格 WebUI，14 个配置分组
- feat(webui): 启动时自动生成 24 位随机密码
- feat(agent): AI Agent 群管理 — 禁言、审批、@提及识别、用户名显示
- feat(version): `/版本` 命令查看插件版本信息

## 改进

- fix: QQ 转发异步化（Thread），不再阻塞游戏聊天显示
- fix: 清理 QQ 图片消息中的 SDK 原始标签（`<faceType=...>`），只显示 `[图片]` 和文字
- fix: `/at` Tab 补全过滤 OpenID，只显示真实昵称
- fix: NicknameManager.all() / matchByPrefix() 过滤昵称是 OpenID 的条目
- fix: QQ→游戏转发时 openid 被当作发送者显示名（forwardFullGroupMessage 解析真实昵称）
- fix: NicknameManager.load() 自动跳过昵称是 openid 的脏数据
- fix: plugin.yml `/at` 命令描述修正
- fix: WebUI 侧边栏空 bug、保存后值丢失 bug
- fix: get_group_members API 移除（无权限 11253）

## 依赖

- 基于 [qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java) (AGPL-3.0)
