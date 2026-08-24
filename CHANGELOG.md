# Changelog

## v1.3.0-beta.2（2026-08-24）

### Bug 修复

- fix: 游戏命令执行结果以纯文本（msg_type=0）发送，不再被 QQ 渲染为 Markdown 格式
- fix: SDK `InterAction.getEnvType()` NPE 修复 — `chat_type` 空值检查防止交互事件崩溃

### 新功能

- feat: QQ→游戏消息支持 `&` 颜色符号转换（如 `&a绿色` → 绿色文字）
- feat: `ConfigProvider.convertAmpersandColors()` — `&0-9`、`&a-f`、`&k-r` 自动转为 `§` 颜色码
- feat: `GroupMessageHandler` 和 `PublicCommands.sendGameMessage` 内联颜色转换

### 文档

- docs: 文档全面改版适配 HuHoBotPenguin 分支（绑定系统、AI Agent、WebUI）
- docs: 新增 `Binding/index.md` 绑定系统文档
- docs: 新增 `Agent/index.md` AI Agent 文档
- docs: 命令列表补全所有 fork 新增命令
- docs: 非 Spigot 适配器标记为上游仅供参考

---

## v1.3.0-beta.1（2026-08-24）

### 上游同步（PenguinClient 功能合入）

- feat: `FaceEmojiParser` — QQ 表情标签 `<faceType=N>` 转可读文本 `[表情:name]`
- feat: `MessageAttachmentParser` — 语音/图片/视频/文件标签转可读文本
- feat: `@Commands` 注解重构 — `command`/`describe`/`onlyAdmin` 字段 + `RegisteredCommand` 数据类
- feat: `BaseCommand.DispatchResult` 枚举（HANDLED / NOT_HANDLED / CUSTOM_COMMAND）
- feat: `CustomCommandRegistry` 运行时注册/注销 + 面板自动同步
- feat: `MenuManager` 改为 HTTP API 动态面板（启动时同步，上限 20 条命令）
- feat: `ConfigProvider` 新增 `isAuthenticationEnabled()`、`commandMenuList()`、motd.md 模板
- feat: `QClient.syncGroupPanels()` 启动时一次性同步面板，运行时注册不再重复同步
- feat: `motd.md` Markdown 模板内置，首次启动自动解压
- feat: 动态 `/帮助` 命令 — 自动列出所有已注册命令（含自定义命令）
- feat: `blockMotd` / `unblockMotd` — 按群屏蔽 MOTD 查询
- feat: `GroupSettingsRepository` 新增 `motdBlocked` 群级设置
- feat: `BaseCommand.allCommands()` 全局命令注册表

### Bug 修复

- fix: 绑定游戏账号后自动添加白名单（QQ 直接绑定 + 游戏验证两条路径均已修复）
- fix: `HuHoBotSpigot.companion` 静态实例支持 `getInstance()` 调用
- fix: 命令面板超出上限时日志提示，仅同步前 20 条命令

### 已知限制

- 本地 QQ Bot SDK 版本不含 `MsgPack`/`OnBotRecvMsg`/`OnBotCommand` 事件，暂不支持第三方插件监听 QQ 消息事件

---

## v1.2.2（2026-08-23）

- fix: 去掉重复的 [图片] 标签
- fix: 清理 QQ 消息中的 Minecraft 颜色代码乱码

---

## v1.2.1-hotfix（2026-08-18）

- fix: 只对消息中实际被 @ 的玩家播放叮音效

---

## v1.2.0（2026-08-17）

### 群服互通 @提及

- feat: `/at` 命令 — 游戏内发送 QQ @消息，支持 Tab 补全昵称
- feat: QQ→游戏 @提及蓝色高亮（`§9@玩家名`）+ 叮音效提醒
- feat: `NicknameManager` 昵称 ↔ openid 双向映射，持久化到 `nicknames.dat`
- feat: `resolveAtMentions` 自动将 `@昵称` 转为 QQ `<@openid>` 格式
- feat: `escapeMarkdown` 转义玩家名中 `_` 等特殊字符

### WebUI

- feat: 暗色简约风格 WebUI，14 个配置分组
- feat: 启动时自动生成 24 位随机密码
- fix: 侧边栏空 bug
- fix: 保存后值丢失 bug

### AI Agent 群管理

- feat: 禁言到期时间服务端计算
- feat: @提及识别与用户名显示
- feat: 审批/拒绝通知显示操作管理员用户名

### Bug 修复

- fix: openid 被当作昵称存储导致 Tab 补全显示 openid
- fix: QQ→游戏转发时 openid 被当作发送者显示名
- fix: NicknameManager.load() 自动跳过昵称是 openid 的脏数据

---

## v1.1.0-alpha.3

- AI Agent 系统初版
- SKILL 按需加载系统
- `/motd` 服务器状态查询
- `/stop` 紧急停止命令
- 指令面板自动同步

---

## v1.1.0-alpha.2

- @提及识别与用户名显示修复

---

## v1.1.0-alpha.1

- 初版 WebUI 图形化配置
- AI Agent 群管理功能（禁言、入群审批、自动审批策略）

---

## v1.0.1

- 初始发布版本
