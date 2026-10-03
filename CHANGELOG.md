# Changelog

## v1.16.0（2026-10-04）

### 新功能

- feat: 玩家死亡播报转发到 QQ 群 — 新增 `player-events.death.enabled` / `format`，支持 `{name}`、`{killer}`、`{message}`（中文死亡描述）等占位符
- feat: 死亡原因中文识别 — 玩家 / 生物 / 投射物击杀，以及摔落、岩浆、火烧、溺水和窒息、虚空、闪电、爆炸、中毒、饿死等 14 种环境死法
- feat: WebUI「QQ 机器人」页面新增扫码连接入口 — 手机 QQ 扫码自动写入 AppID / Secret 并连接，支持取消、重试与状态提示（等待扫码 / 过期刷新 / 成功 / 失败）
- feat: 新增管理员命令 `/强制解绑` — 支持 MC 玩家名、`@某人`、openid、QQ 昵称指定目标，用于撤销冒名顶替的绑定
- feat: `/解除绑定` 增加二次确认 — 发送「确定 / 取消」内联键盘，30 秒内未选择视为取消，确认消息与键盘消息都会自动撤回

### 改进

- change: 绑定改为按 openid 全局共享 — 同一个 openid 在任意群绑定一次后，该服务器所有群都视为已绑定，无需重复绑定
- change: MC 玩家名改为全局唯一 — 避免不同群各绑同一个角色导致冒名顶替
- change: 换绑角色时保留已切换的显示名称偏好，不再重置为默认值
- change: WebUI 先于 QQ 连接启动 — 未配置 bot 凭据时也能打开管理页面完成扫码授权，不再卡在服务器启动阶段
- change: 停服时自动结束进行中的扫码授权会话

### Bug 修复

- fix: QQ 扫码登录改为后台会话制 — 原实现会在主线程 `while(true)` 阻塞等待扫码，凭据为空时服务器卡在启动阶段
- fix: 扫码会话在控制台与网页之间共享同一次授权任务，两边看到同一张二维码
- fix: 修复内联键盘不渲染 — `Keyboard.RowBuilder.build()` 未调用导致 `rows` 为空数组，按钮全部丢失
- fix: 修复解绑确认点击无响应 — 按钮回调里的确认 ID 提取时带上了 `:yes` 后缀，导致找不到待确认项
- fix: 修复按钮点击后客户端提示「请求第三方失败 / 请求超时」— 按官方要求调用互动事件响应接口 `PUT /interactions/{interaction_id}`，AI 审批按钮同样受益
- fix: 按官方文档修正消息载荷 — 传 `markdown` 时 `content` 必须为空，此前 7 处两者同时下发，可能导致服务端按纯文本处理并丢弃键盘
- fix: 绑定数据自动迁移 — 旧的 `[bindings]` / `[binding-settings]` 段导入为按 openid 索引的 `[user-bindings]`，跨群重复记录合并并保留显示名称设置

---

## v1.15.1（2026-10-01）

### Bug 修复

- fix: QQ 指令面板同步报 30019「面板版本冲突」— 原位更新按官方文档补传 `panel.version`（详情接口顶层与 `panel` 内两个位置都读取），冲突时重读版本后重试一次
- fix: 面板同步并发自撞与撞限频 — 同步入口串行化，同一时刻只跑一次，期间的请求收敛为一次补跑；两次同步最小间隔 6 秒（对应官方写接口 10 QPM），消除 40030009「面板操作进行中」
- improve: 面板同步错误提示区分 30013 / 30019 / 40030009 并给出排查建议

---

## v1.15.0（2026-09-30）

### 新功能

- feat: 脚本扩展系统（仅 Spigot）— 用 JavaScript / Lua / Python 写脚本插件，无需编译
- feat: 目录式脚本插件 — `addons/<名字>/` 下放 `main.js` / `main.lua` / `main.py` 加 `metadata.yaml` 即一个插件
- feat: `Bird` 桥 — 脚本可注册 Bukkit 事件、游戏内命令（含 Tab 补全）、定时任务、HTTP 回调、QQ 群命令
- feat: `/huhobot scripts reload [目录名]` — 重载全部或单个脚本插件
- feat: 脚本配置与数据 — `_conf_schema.json` 声明配置默认值，`config` / `kv` 两套键值空间，`setData` 持久化
- feat: 跨脚本事件总线 — `Bird.on` / `Bird.emit`
- feat: 引擎按需安装 — LuaJ 打进主 jar；GraalJS、GraalPy 拆成 `addon-GraalJs` / `addon-GraalPy`，从 `plugins/HuHoBotPenguin/engines/` 加载，不放也能启动
- feat: 扩展注册 API 支持注销 — `AddonManager.unregister`，重载时真正撤掉 QQ 面板上的旧命令

### 变更

- change: 脚本加载失败或重载时撤销该脚本登记的全部内容（命令、监听器、定时任务、QQ 群命令、addon 元数据）
- change: `_enabled: false` 与「目录里没有入口」记为「跳过」而不是「失败」
- change: 插件停用时关闭共享的 GraalPy `Engine` 与引擎 `URLClassLoader`
- change: 主插件保持 Java 8 字节码与 Spigot 1.16.5 基线，Java 9+ API 只允许出现在两个引擎包里

### Bug 修复

- fix: LuaJ 在目标方法有 3 个以上参数且含函数式接口时不做自动转换（`no coercible public method`），改为在桥接层用动态代理接管，Lua 侧全部重载可用
- fix: LuaJ 把 Java `List` / `Map` 变成 userdata 导致 `#` / 下标 / `pairs` 全部失效，Lua 绑定层改为转成真正的 table（下标 1 起）
- fix: Lua 侧宿主对象（`Player` 等）回调时未解包导致 `argument type mismatch`
- fix: 脚本重载时同分重载靠反射返回顺序选择，结果不确定，改为按参数类型具体程度择优
- fix: `Bird.tell` 缺少 `CommandSender` 重载，从控制台执行命令时无法回应
- fix: `CommandMap` 反射只查当前类，字段在父类时所有 `onCommand` 静默失效
- fix: 脚本加载失败时未回滚已登记的内容，半路失败的脚本会留下幽灵命令与监听器
- fix: 启动早期脚本登记 QQ 群命令会触发面板同步，此时 QQ 尚未鉴权导致 `contextManager` 为空
- fix: GraalPy 首次运行时 home 尚未解压完就加载 `.py` 脚本，导致标准库模块全部找不到
- fix: 共享 Engine 的多个 Context 使用不同 host access 实例被 GraalVM 拒绝

---

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
