# HuHoBotPenguin

把 QQ 群机器人接入 Minecraft 服务器：游戏聊天与 QQ 群双向转发、白名单管理、在线查询、命令执行与敏感词审核。基于 [qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java)（HuHoBot fork，以 git submodule 引入）。

## 支持的平台

- **Spigot / Paper**（api-version 1.18）：JDK 8，产物 `HuHoBot-Penguin_Spigot-<版本>.jar`
- **Nukkit**（MOT）：JDK 17，产物 `HuHoBot-Penguin_Nukkit-<版本>.jar`
- **Allay**（≥ 0.17.0）：JDK 21，产物 `HuHoBot-Penguin_Allay-<版本>.jar`
- **BungeeCord / Velocity**（3.4）：JDK 17，产物 `HuHoBot-Penguin_Proxy-<版本>.jar`

## 功能特性

- 游戏 ↔ QQ 群聊天双向转发，支持格式模板与转发前缀过滤（默认只转发以 `#` 开头的游戏消息）
- QQ 群指令系统：查在线、发信息、白名单、管理员、认证等，可在配置中逐个开关
- 白名单管理：映射到服务器原生命令（如 `whitelist add/remove`），代理平台可配置路由到子服
- 敏感词审核：正则 + 本地词库 + 可选 OpenAI 兼容接口 AI 二审；接口不可用时自动回退为本地屏蔽
- MOTD 服务器状态展示：内置查询或第三方 API，可选 Markdown / 图片输出
- 自定义命令：占位符替换（`{params}`、`{group}`、`{user}`、`{0}`…），支持权限分级
- 多群支持：每群独立的管理员名单、管理员判定方式与全量转发开关
- 各平台打包为独立 fat jar（shadow），放进 plugins 目录即可用

## 快速开始

### 准备

1. 到 [q.qq.com](https://q.qq.com/) 申请机器人，获得 AppID 和 Secret。
2. 准备运行环境：Spigot 平台需要 JDK 8+，Nukkit / BungeeCord / Velocity 需要 17+，Allay 需要 21+。

### 构建

```bash
git clone --recurse-submodules git@github.com:HuHoBot/PenguinClient.git
cd PenguinClient
./gradlew build
```

- 项目依赖 `deps/qqpd-bot-java` 子模块；克隆时若未使用 `--recurse-submodules`，运行 `git submodule update --init --recursive`。
- 所有产物会收集到 `build/gather-jar/`。
- 同时构建全部平台需要本机可用的 JDK 8 / 17 / 21（Gradle toolchain 可自动下载时无需手动安装）。
- 运行 Gradle 本身请使用 JDK 17 / 21（Gradle 8.14.5 不支持 JDK 25 及更新版本，报错时可用 `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build` 指定）。

也可以从 [Releases](https://github.com/HuHoBot/PenguinClient/releases) 下载已构建的 jar。

### 安装

把对应平台的 jar 放入服务端插件目录（Spigot/Allay/Nukkit 为 `plugins/`，Velocity 为 `plugins/`，BungeeCord 也为 `plugins/`），重启服务器。

### 配置

首次启动后会在插件数据目录生成 `config.yml`。关键配置项：

- **bot**：`app-id` / `secret` 为 QQ 机器人凭据，任一留空则不启动机器人；`groups` 为允许使用的群 OpenId 列表。
- **chat-format**：双向转发格式模板；`post-chat` 总开关；`start-with` 指定只有以该前缀开头的游戏消息才会转发（转发时移除前缀，留空表示全部转发）。
- **whitelist**：白名单原生命令模板，代理平台需改为可路由到子服的命令。
- **admin**：管理员判定方式 `qq`（群主/群管理员）、`config`（手动名单）、`both`（任一满足），及手动名单 `openids`。
- **audit**：OpenAI 兼容审核接口（`base-url` / `api-key` / `model`）。
- **custom-commands**：自定义指令列表，`permission: 0` 供所有成员执行，更高等级需要管理员。
- **commands**：各群指令的开关。

Spigot 版另有 `command-sender: Hybrid`，用于同时收集命令发送者输出与服务端日志。

### QQ 群指令

在群内发送指令（无需 @机器人）。`[]` 表示可选参数，`<>` 表示必填参数：

- **查信息** `[OpenId]` —— 查询自己的 OpenId / 群 OpenId；带参数且是管理员时查询他人认证状态
- **查管理** `<OpenId>` —— 查询某人是否为本群管理员
- **加管理** `<OpenId>` / **删管理** `<OpenId>` —— 管理本群管理员名单
- **管理方式** `[QQ|手动|双重]` —— 查看或设置本群管理员判定方式
- **添加白名单** `<玩家名>` / **删除白名单** `<玩家名>` / **查白名单** —— 白名单管理
- **查在线** —— 查询服务器在线玩家
- **在线服务器** —— 查询已连接的服务器
- **发信息** `<内容>` —— 发送消息到游戏内
- **执行命令** `<命令>` —— 以管理员身份执行服务器命令
- **执行** `<key> [参数]` / **管理员执行** `<key> [参数]` —— 执行 `custom-commands` 中定义的自定义命令
- **全量** —— 切换本群全量聊天转发
- **认证** —— 查询自己的认证状态；**认证 / 解除认证** `<OpenId>`（管理员）—— 管理他人认证状态

## 模块结构

- **common/Bot** —— 平台无关核心：QQ 客户端、群消息分发、指令、审核与状态存储
- **server/AdapterCommon** —— 服务端适配公共层（YAML 配置、调度与命令原语）
- **server/Spigot** / **server/Allay** / **server/Nukkit** / **server/Proxy** —— 各平台入口与适配
- **deps/qqpd-bot-java** —— QQ 机器人 SDK（git submodule，[HuHoBot fork](https://github.com/HuHoBot/qqpd-bot-java)），源码直接参与编译

## 开发与发布

- Kotlin + Gradle（wrapper 已随仓库提供，版本 8.14.5）。
- CI 见 `.github/workflows/build.yml`：push 到 `main` / `dev` 或发起 PR 触发构建；推送 `v*` 标签触发构建并创建 GitHub Release。
