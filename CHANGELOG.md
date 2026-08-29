# Changelog

## v1.4.0（2026-08-29）

### 新功能

- feat: `/背包查看 <玩家名>` 命令 — 查看指定在线玩家背包内容（PNG 图片渲染，仅管理员）
- feat: Faithful 32x 贴图包集成 — 物品图标与方块预览使用 Faithful 32x 纹理
- feat: 玩家皮肤预览 — 背包图片中显示玩家正面皮肤
- feat: 等距3D方块预览 — 有面贴图的方块自动合成等距3D效果
- feat: `/qqbind` 权限改为所有玩家可用（默认 true）

### 变更

- change: `/背包查看` 仅管理员可用（`onlyAdmin = true`）

### Bug 修复

- fix: 命令方块等动画贴图渲染异常（自动裁剪为第一帧）
- fix: 铁砧等无面贴图方块使用基础贴图做2D图标
- fix: 箱子贴图从 overrides 目录正确加载（overrides 优先级提升）
- fix: QQ markdown 转义玩家名中 `_` 字符防止被识别为斜体

---

## v1.3.0（2026-08-27）

### 新功能

- feat: 命令黑名单（`command-blacklist`）— 禁止通过 `/执行` 或 Agent `run_command` 运行指定服务器命令
- feat: `/版本` 命令输出中显示文档链接
- feat: WebUI 新增「绑定」和「命令黑名单」配置分区
- feat: WebUI 新增 `features.enable-auth`（头像认证开关）
- feat: `ConfigUpgrader.upgradeValues()` 支持版本化升级已有配置字段
- feat: `BaseCommand.handleMessage()` 实际执行 `onlyAdmin` 拦截（之前仅用于面板显示）

### 变更

- change: motd `post-img` 和 `use-markdown` 默认值改为 `true`
- change: AI Agent 命令（`/agent`、`/newsession`、`/stop`）标记为仅管理员
- change: 配置文件版本升级到 v6，旧版自动迁移 motd 默认值

### Bug 修复

- fix: 自定义命令执行时不再转发到游戏（如群内执行 /test，游戏内不再显示 [QQ] xxx：/test）
- fix: 文档许可证从 GPL v3 更正为 AGPL v3
- fix: 文档网址从 `huhobot.txssb.cn` 更正为 GitHub Pages
- fix: LICENSE.txt 替换为 AGPL v3 正文

### 文档

- docs: 文档全面改版适配 HuHoBotPenguin 分支
- docs: 新增命令黑名单配置说明
- docs: Agent `run_command` 工具标注受黑名单限制
- docs: motd 默认值说明更新

---

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
