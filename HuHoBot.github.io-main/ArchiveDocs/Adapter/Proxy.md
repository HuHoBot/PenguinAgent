# HuHoBot Velocity/BungeeCord Adapter

[![GitHub Release](https://img.shields.io/github/v/release/HuHoBot/KotlinMergeAdapter?style=for-the-badge)](https://github.com/HuHoBot/KotlinMergeAdapter/releases)
[![License](https://img.shields.io/github/license/HuHoBot/KotlinMergeAdapter?style=for-the-badge)](https://github.com/HuHoBot/KotlinMergeAdapter/blob/main/LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/HuHoBot/KotlinMergeAdapter/build.yml?style=for-the-badge)](https://github.com/HuHoBot/KotlinMergeAdapter/actions)

新一代Minecraft服务器管理机器人解决方案，支持 Velocity/BungeeCord 代理端，突破传统机器人框架限制，提供更安全稳定的交互体验。

## 🚀 功能特性

- **跨平台支持**：适配 Velocity / BungeeCord 代理服务端
- **多服务器管理**：通过代理端统一管理多个子服
- **Redis 消息通道**：使用 Redis 实现机器人与服务端通信

### 进阶功能

- **扩展API**
  - 自定义命令系统(详见下文)
  - 支持跨服命令执行

## 📥 安装指南

### 环境要求

- Java 17+ Runtime
- **Velocity 或 BungeeCord 代理服务端**
- **Redis 服务器**（用于机器人与服务端通信）

### 快速开始

1. **访问 GitHub Releases 页面**：
   - 打开浏览器，访问 [HuHoBot Adapter Releases](https://github.com/HuHoBot/KotlinMergeAdapter/releases)
   - 下载最新版本的 `HuHoBot-vx.x.x-Proxy.jar` 文件

2. **安装插件**：
   - 将下载的 jar 文件放入代理服务端的 `plugins` 文件夹
   - 重启代理服务端

3. **参照** [快速开始](../QuickStart/index.md) 配置机器人端

4. **安装子服适配器**:
    - 打开浏览器，访问 [HuHoBot GroupRCAdapter Releases](https://github.com/HuHoBot/GroupRCAdapter/releases)
    - 下载最新版本的 `RCHuHoBot-x.x.x-Spigot.jar` 文件
    - 按下文配置

### 高级配置（可选）

- 如果需要自定义功能，请参考`⚙️ 配置示例`进行详细设置

---

#### 注意事项：

- 确保服务器已正确安装 Java 8+ 运行时环境
- 确保 Redis 服务器正常运行且可访问
- 代理端需要与机器人端使用相同的 Redis 配置

## ⚙️ 配置示例

### 代理端主配置 (config.yml)

```yaml
name: HuHoBot
# 服务器标识（自动生成，无需修改）
serverId: null
# 密钥（自动生成，无需修改）
hashKey: null

chatFormat:
  from_game: "<{name}> {msg}" # 服内消息转发到群内时的文本
  from_group: "群:<{nick}> {msg}" # 群内消息转发到服内时的文本
  post_chat: true # 是否在群内发送消息到服务器内
  post_prefix: "" # 服内消息转发到群内时的前缀

motd:
  server_ip: play.hypixel.net # 使用"/查在线"时的服务器地址
  server_port: 25565 # 使用"/查在线"时的服务器端口
  api: https://motdbe.blackbe.work/status_img/java?host={server_ip}:{server_port} # Motd图片API
  text: 共{online}人在线 # 使用"/查在线"时的Motd文本，支持变量 {online}
  output_online_list: true # 是否显示在线列表
  post_img: true # 是否显示Motd图片

whiteList:
  add: server-name:whitelist add {name} # 添加白名单的指令
  del: server-name:whitelist remove {name} # 删除白名单的指令

callbackConvertImg: 1 # 命令回调转换成图片的行数（0为不转换）

# 自定义执行命令
customCommand: [] # 自定义命令列表

redis:
  enabled: true # 是否启用Redis
  host: 127.0.0.1 # Redis服务器地址
  port: 6379 # Redis端口
  password: your_password # Redis密码
  channel: HuHoBotChannel # Redis通信频道
```

### 子服配置 (子服的 config.yml)

每个子服需要配置独立的 `server-name` 以便机器人识别并执行命令到正确的服务器。

```yaml
server-name: "lobby" # 子服名称，用于区分不同服务器

redis:
  host: "127.0.0.1" # Redis服务器地址
  port: 6379 # Redis端口
  password: "your_password" # Redis密码
  database: 0 # Redis数据库编号
  timeout: 2000 # 连接超时时间
  command-channel: "HuHoBotChannel" # 命令通信频道
  callback-channel: "HuHoBotChannel_callback" # 回调通信频道
  pool:
    max-total: 8 # 最大连接数
    max-idle: 8 # 最大空闲连接数
    min-idle: 0 # 最小空闲连接数
```

### 命令执行格式

在机器人中执行命令时，需要使用 `server-name:命令` 的格式来指定目标服务器，如需全部服务器，则为`ALL:命令`。

**示例：**

- `lobby:op player123` - 在 lobby 子服执行 op 命令
- `survival:gamemode survival player123` - 在 survival 子服执行游戏模式命令
- `skyblock:eco give player123 1000` - 在 skyblock 子服执行经济命令

### 配置自定义命令

#### 通过配置文件设置

在 `config.yml` 文件中，你可以通过 `customCommand` 字段来定义自定义命令。每个自定义命令包含以下属性：

- **key**：触发命令的关键词（字符串）
- **command**：实际执行的服务器命令（字符串）
- **permission**：权限级别（整数）

示例配置如下：

```yaml
customCommand:
  - key: "加白名" #执行关键词，可使用"/关键词 参数1 参数2"来执行自定义命令
    command: "server-name:whitelist add &1" #&1为参数占位符，第一个参数为&1，第二个&2，以此类推
    permission: 0 #0是普通权限，大于0则为管理员权限

  - key: "管理加白名"
    command: "server-name:whitelist add &1"
    permission: 1
```

### 架构说明

代理端适配器使用 Redis Pub/Sub 机制实现与机器人的通信：

1. **机器人 → 代理端**：机器人发送命令到 Redis command-channel
2. **代理端 → 子服**：代理端转发命令到指定子服
3. **子服 → 代理端 → 机器人**：子服执行结果通过 Redis callback-channel 返回

这种架构允许你通过机器人管理多个子服，每个子服通过唯一的 `server-name` 进行标识。

## 🤝 参与贡献

欢迎提交PR或通过[Discussions](https://github.com/HuHoBot/KotlinMergeAdapter/discussions)提出建议
