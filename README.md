# HuHoBotPenguin

把 QQ 群机器人接入 Minecraft 服务器：游戏聊天与 QQ 群双向转发、白名单管理、在线查询、命令执行、敏感词审核与 **AI Agent 智能管理**。基于 [qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java)（HuHoBot fork，以 git submodule 引入）。

## 支持的平台

- **Spigot / Paper**（api-version 1.18）：JDK 8，产物 `HuHoBot-Penguin_Spigot-<版本>.jar`

## 功能特性

- 游戏 ↔ QQ 群聊天双向转发，支持格式模板与转发前缀过滤
- QQ 群指令系统：查在线、发信息、白名单、管理员、认证等
- 白名单管理：映射到服务器原生命令
- 敏感词审核：正则 + 本地词库 + 可选 OpenAI 兼容接口 AI 二审
- MOTD 服务器状态展示
- 自定义命令：占位符替换，支持权限分级
- 多群支持：每群独立的管理员名单与配置

### AI Agent（v1.1.0 新增）

- `@机器人 /agent 任务描述` —— AI 自动执行服务器管理任务
- AI 通过 function call 获取插件列表、命令帮助、执行命令、读取日志
- 命令执行支持手动审批模式（按钮卡片，仅管理员/群主可审批）
- SKILL 按需加载系统：components / give / item / summon / data / loot / clear
- `/newsession` —— 手动清除 AI 会话上下文
- 支持推理模型思考过程输出

## 快速开始

### 准备

1. 到 [q.qq.com](https://q.qq.com/) 申请机器人，获得 AppID 和 Secret。
2. 准备运行环境：JDK 8+。

### 构建

```bash
git clone --recurse-submodules git@github.com:HuHoBot/PenguinClient.git
cd PenguinClient
./gradlew build
```

- 项目依赖 `deps/qqpd-bot-java` 子模块；克隆时若未使用 `--recurse-submodules`，运行 `git submodule update --init --recursive`。
- 所有产物会收集到 `build/gather-jar/`。

### 安装

把 `HuHoBot-Penguin_Spigot-<版本>.jar` 放入 `plugins/` 目录，重启服务器。

### 配置

首次启动后会在插件数据目录生成 `config.yml`。关键配置项：

- **bot**：`app-id` / `secret` 为 QQ 机器人凭据
- **chat-format**：双向转发格式模板
- **whitelist**：白名单命令模板
- **admin**：管理员判定方式
- **agent**：AI Agent 配置（`enabled` / `base-url` / `api-key` / `model` / `command-mode`）

### QQ 群指令

- **帮助** —— 查看所有命令
- **查信息** `[OpenId]` —— 查询 OpenId
- **查管理** `<OpenId>` —— 查询管理员状态
- **加管理** `<OpenId>` —— 添加管理员
- **删管理** `<OpenId>` —— 删除管理员
- **管理方式** `[QQ/手动/双重]` —— 设置管理员判定方式
- **添加白名单** `<玩家名>` —— 添加玩家白名单
- **删除白名单** `<玩家名>` —— 删除玩家白名单
- **查白名单** —— 查看白名单列表
- **查在线** —— 查询在线玩家
- **在线服务器** —— 查看已连接服务器
- **发信息** `<内容>` —— 发送消息到游戏内
- **执行命令** `<命令>` —— 执行服务器命令
- **执行** `<命令>` —— 执行自定义命令
- **管理员执行** `<命令>` —— 管理员执行自定义命令
- **全量** —— 切换全量聊天转发
- **motd** `<服务器地址>` —— 查询 MC 服务器状态（图片 + Markdown）
- **agent** `<任务描述>` —— AI Agent 执行服务器管理任务
- **stop** —— 紧急停止所有 AI 任务
- **newsession** —— 清除 AI 会话上下文

## 模块结构

- **common/Bot** —— 平台无关核心：QQ 客户端、群消息分发、指令、AI Agent
- **server/AdapterCommon** —— 服务端适配公共层
- **server/Spigot** —— Spigot/Paper 平台适配
- **deps/qqpd-bot-java** —— QQ 机器人 SDK（git submodule）

## 许可证

本项目采用 [GNU Affero General Public License v3.0](LICENSE) 许可证。
