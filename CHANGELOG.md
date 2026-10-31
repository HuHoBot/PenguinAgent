# Changelog

## v1.19.1（2026-10-31）

### Bug 修复

- fix: 草方块在背包 / 末影箱图片里显示成覆雪草方块（#32）— 资源包里的 `grass_block_top.png` 是灰度遮罩图，原版靠生物群系颜色把它染绿；渲染器此前直接把灰度图铺上去，顶面因此发白
- fix: 同一批需要群系染色的贴图一并修复，此前全部渲染成灰色：橡木 / 白桦 / 云杉 / 丛林 / 金合欢 / 深色橡木 / 红树树叶，短草、蕨、高草、大型蕨、藤蔓、睡莲
- fix: 染色改为原版同款乘法混色（`通道 × 染色 / 255`），保留贴图明暗层次，不会把图案压成一片死色

### 说明

- 染色按面区分：草方块只染顶面（`grass_block_side.png` 本身已带绿色），树叶则所有面都染
- 配色取平原默认值：草色 `#91BD59`、树叶色 `#77AB2F`；白桦 `#80A755`、云杉 `#619961`、睡莲 `#71C35C` 用原版固定色
- 只染白名单内的贴图，铁块、雪、石英等浅色方块不受影响；已带颜色的樱花、杜鹃、苍白橡木树叶不会被二次染色
- 西瓜 / 南瓜茎同为灰度图，但原版按生长阶段取不同颜色，本次未改动

---

## v1.19.0（2026-10-30）

### 新功能

- feat: Agent 深度思考 — 原生推理模型开启后按厂商适配思考参数（`agent.think` / `agent.think-level` / `agent.think-param`），支持 `reasoning_effort`、`effort`、`thinking_level`、`reasoning.effort`、`thinking.type`、`thinking_budget`、`enable_thinking`；档位类型与参数不匹配时不使请求失败，只把提示写进日志
- feat: Agent 思考引导 — 给不具备原生推理能力的模型追加提示词，用 `[思考]` / `[/思考]` 标签模拟深度思考，程序把标签内容取出来当思考过程、从正文里剥离；与深度思考互斥，开启后 `think` / `think-level` / `think-param` 一律不生效
- feat: 思考展示方式 `agent.think-visibility` — `log` 只写日志（默认，避免刷屏）/ `group` 作为「思考」卡片发群 / `both` 两边都发
- feat: Agent 上下文压缩与截断 — 新增 `/压缩`、`/压缩token <数值>`、`/压缩保留 <条数>`、`/删除信息 [条数]`；压缩把较早的对话换成结构化摘要，保留最近若干条消息；裁剪与压缩都避开 `tool_calls` 与 `tool` 的配对边界，不会切出接口会拒绝的孤立消息
- feat: 上下文用量按接口返回的 `usage.prompt_tokens` 判断，接口不返回时退回本地估算
- feat: 群成员角色被动缓存 — 群消息 `author.member_role` 自动写入状态文件新增的 `[member-roles]` 段，用于 Agent 审批鉴权
- feat: WebUI 新增深度思考、思考引导、上下文压缩相关配置项，修改后写入 `config.yml` 并刷新配置缓存，无需重启

### 改进

- change: Agent 审批权限改为三级判定 — 插件管理员名单（发起者 / 全局管理员 / 本群管理员）→ 被动角色缓存 → 群成员信息接口（白名单能力，失败一次即停用）
- change: 同一会话只允许一个 `runLoop` 执行，`/agent` 与审批回调不再可能并发写同一份消息列表
- change: `/agent` 复用会话改为按群隔离，多群部署时不再复用别的群的会话
- change: 互动事件（解绑确认、Agent 审批）按结果回不同 code：真正处理成功才回 0，无权限 / 已过期 / 被他人点击回非零，客户端不再弹误导性的「操作成功」
- change: 昵称映射改为「有变化才保存」，一条消息里出现多个新昵称只在循环结束后落盘一次
- change: WebUI 自己持有请求线程池并在 `stop()` 与启动失败分支显式关闭，插件重载不再泄漏线程
- change: 群成员信息接口加 5 秒连接 / 读取超时
- change: 命令面板最多展示 20 条，`压缩`、`压缩token`、`压缩保留`、`删除信息` 与 `blockMotd`、`unblockMotd` 一并移出面板菜单

### Bug 修复

- fix: 普通成员点击解绑确认的「确定 / 取消」提示无权限 — 按钮权限由「仅管理者可操作」改为「所有人可操作」，身份边界改由服务端严格校验（#31）
- fix: 拿不到点击者 openid 时曾经放行他人解绑 — 改为一律拒绝，且身份不符时不消费待确认项，发起者仍可在有效期内操作
- fix: 他人点击解绑按钮时，提示消息现在 @ 点击者本人：「@xxx 这不是你的绑定信息，请让发起者本人操作」
- fix: `/stop` 之后复用会话会被立刻判定为已停止，新任务不执行 — 复用前复位 `stopped`
- fix: 身份校验通过后才原子消费待确认项，避免同一张卡片被连点两次重复执行
- fix: 配置里遗留的 `commands.深度思考 / 思考展示 / 思考程度 / 思考引导` 会被自动清理

### 升级提示

- 配置版本升到 11。启动时自动补上 `agent.think`、`agent.think-visibility`、`agent.think-level`、`agent.think-param`、`agent.think-guide`、`agent.think-guide-prompt`、`agent.context.compress.target`、`agent.context.compress.keep`、`agent.context.compress.prompt`、`agent.context.truncate.drop`
- 深度思考与思考引导默认都关闭，升级后行为与 1.18.1 一致
- 深度思考相关设置只在配置文件 / WebUI 调整，QQ 群不再提供设置命令

---

## v1.18.1（2026-10-04）

### Bug 修复

- fix: 末地烛等 5 个物品在背包图片里形状变形 — 原版 `models/item/<id>.json` 的 parent 是 `block/<model>`，也就是说这些物品的图标本来就是 3D 方块模型渲染，而不是一张平面贴图。渲染器只有「等距完整方块」和「平面贴图」两条路，这些物品 `isSolid()=false` 走了平面分支，把方块贴图图集直接铺平画了出来：末地烛看上去被掰弯，脚手架因为根本没有对应贴图只能画成 unknown 问号方块
- fix: 受影响的 5 个物品改为按原版方块模型离线烘焙图标，投影与明暗与现有等距方块完全一致：末地烛 `end_rod`、雪 `snow`、传送植物 `chorus_plant`、传送花 `chorus_flower`、脚手架 `scaffolding`

### 说明

- 只新增 5 张图标（`inventory/faithful32x/special-variants/`），无代码改动、无配置变化，配置版本仍为 10
- 其余取自 `block/` 贴图的物品（虞美人、火把、轨道、珊瑚等）在原版本就是 `layer0: block/xxx` 平面贴图，图标正确，未做改动

---

## v1.18.0（2026-10-04）

### 新功能

- feat: 免验证名单 — 新增 `binding.verify-exempt`，名单内的玩家无需 QQ 绑定即可进入服务器，用于受限于自身设备或环境、无法使用 QQ 的人员
- feat: 新增管理员命令 `/添加免验证 <玩家名>` 与 `/取消免验证 <玩家名>`，改动即时写入配置，无需重启即可生效
- feat: WebUI「绑定」页新增免验证名单编辑

### 改进

- change: 免验证玩家解绑后不再被踢出 — 他们本来就能免绑定进服，踢出没有意义；非免验证玩家维持原样，解绑即踢出
- change: 配置项的补充说明改为按位置替换 — 同一配置项的说明文字调整后，旧的那行会被覆盖而不是越堆越多

### Bug 修复

- fix: 「强制绑定开启时游戏内验证配置无效」的说法有误 — 只有被踢出的玩家用 5 位验证码那条路绕过游戏内验证；免验证玩家主动 `/绑定 <游戏ID>` 走的仍是普通流程，是否需要游戏内 `/qqbind` 验证仍由 `binding.require-game-verification` 决定。config.yml、WebUI 说明与文档已更正

### 升级提示

- 配置版本升到 10。启动时会自动补上 `binding.verify-exempt` 与两条命令开关，并把 `binding.require-game-verification` 的补充说明更正为新的准确描述
- 免验证名单默认留空，升级后行为与 1.17.0 一致

---

## v1.17.0（2026-10-04）

### 新功能

- feat: 强制绑定 — 新增 `binding.force-bind` 与 `binding.force-bind-groups`。开启后未绑定的玩家进服务器会被立刻踢出，并拿到一个 5 位验证码；在 QQ 群执行 `/绑定 <验证码>` 完成绑定后重新进入即可游玩
- feat: 解绑后立即断开在线会话 — 强制绑定开启时，玩家在 QQ 解除绑定的瞬间，其在线连接会被踢出，不必等到下次进服
- feat: 配置文件自动升级 — 新增文本级增量升级器，把新增配置项连同注释补进旧配置文件；配置版本号升到 9

### 改进

- change: 绑定时的游戏内验证改为**默认开启**（`binding.require-game-verification`）。已显式配置为 `false` 的服务器不受影响
- change: 强制绑定开启时跳过绑定/解绑的白名单同步，避免与白名单机制互相干扰
- change: WebUI 每个分节改用手写的介绍文案，不再把各字段说明拼成一长串
- change: 踢出提示改为带颜色的完整信息（分隔线、红色标题、绿色验证码与命令），不再额外发送标题

### Bug 修复

- fix: 旧配置文件补不上新配置项 — `config.contains()` 会回落到 jar 模板的默认值，导致模板里新增的键永远补不进去（`update-check`、`placeholder-api`、`binding.force-bind` 等均受影响）
- fix: 补注释时产生重复注释 — 原先按注释文字精确比对，旧模板遗留的措辞差异会被判定为缺注释
- fix: 强制绑定提示里的服务器名称不再带中括号

### 升级提示

- 从 1.16.x 升级无需改配置。启动时会自动把新增配置项与注释补进 `config.yml`，建议在停服状态下替换 jar 后启动一次
- 若此前没有配置过 `binding.require-game-verification`，升级后该项会补为 `true`，即绑定需要游戏内 `/qqbind` 验证；不希望改变的服务器请手动填 `false`

---

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
