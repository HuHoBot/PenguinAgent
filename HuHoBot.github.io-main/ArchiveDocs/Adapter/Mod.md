# HuHoBot Fabric/Forge Adapter

[![GitHub Release](https://img.shields.io/github/v/release/HuHoBot/KotlinMergeAdapter?style=for-the-badge)](https://github.com/HuHoBot/KotlinMergeAdapter/releases)
[![License](https://img.shields.io/github/license/HuHoBot/KotlinMergeAdapter?style=for-the-badge)](https://github.com/HuHoBot/KotlinMergeAdapter/blob/main/LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/HuHoBot/KotlinMergeAdapter/build.yml?style=for-the-badge)](https://github.com/HuHoBot/KotlinMergeAdapter/actions)

新一代Minecraft服务器管理机器人解决方案，突破传统机器人框架限制，提供更安全稳定的交互体验。

## 🚀 功能特性
- **无缝绑定**：通过WebSocket实现服务器与控制端即时绑定
- **跨平台支持**：适配Fabric/Forge(1.16+)

### 进阶功能

- **扩展API**
    - 自定义命令系统(详见下文)

## 📥 安装指南

### 环境要求

- Java 17+ Runtime
- `Architectury API`
- `fabric-language-kotlin` 或 `forge-language-kotlin`
- **任意支持的 Fabric/Forge 核心**（包括但不限于 1.16+ 版本）

### 快速开始

1. **访问 GitHub Releases 页面**：
- 打开浏览器，访问 [HuHoBot Adapter Releases](https://github.com/HuHoBot/KotlinMergeAdapter/releases)
- 下载最新版本的 `huhobot-x.x.x-Fabric.jar` 或 `huhobot-x.x.x-Forge.jar` 文件

2. **参照** [快速开始](../QuickStart/index.md)

### 高级配置（可选）

- 如果需要自定义功能，请参考`⚙️ 配置示例`进行详细设置

---

#### 注意事项：

- 确保服务器已正确安装 Java 17+ 运行时环境
- 插件兼容所有支持的 Fabric/Forge 核心版本，如没有对应的版本，请自行编译Mod(具体方法见文档)
- `仅测试原版Fabric/Forge核心，如有其他核心，该Mod暂不受理不正常工作的情况`

## ⚙️ 配置示例

```yaml
#不用管
serverId: null
#不用管
hashKey: null

chatFormat:
  from_game: "<{name}> {msg}" #服内消息转发到群内时的文本
  from_group: "群:<{nick}> {msg}" #群内消息转发到服内时的文本
  post_chat: true #是否在群内发送消息到服务器内
  post_prefix: "" #群内消息转发到服内时的前缀

motd:
  server_ip: "play.hypixel.net" #使用"/查在线"时的Motd图片地址（改成你的进服地址）
  server_port: 25565 #使用"/查在线"时的Motd图片端口（改成你的进服端口）
  api: "https://motdbe.blackbe.work/status_img/java?host={server_ip}:{server_port}" #使用"/查在线"时的Motd图片地址Api（无特殊需求勿动）（必须返回图片）
  text: "共{online}人在线" #使用"/查在线"时的Motd文本，可使用PlaceholderAPI，留空不显示
  output_online_list: true #是否显示在线列表
  post_img: true #是否显示Motd图片

whiteList:
  add: "whitelist add {name}" #添加白名单的指令
  del: "whitelist remove {name}" #删除白名单的指令

callbackConvertImg: 0 #命令回调转换成图片的行数（0为不转换）

#自定义执行命令
customCommand:
  - key: "加白名" #执行关键词，可使用"/执行 关键词 参数1 参数2"来执行自定义命令
    command: "whitelist add &1" #&1为参数占位符，第一个参数为&1，第二个&2，以此类推
    permission: 0 #0是普通权限，大于0则为管理员权限

  - key: "管理加白名"
    command: "whitelist add &1"
    permission: 1

```

### 配置自定义命令

#### 通过配置文件设置

在 `config.yml` 文件中，你可以通过 `customCommand` 字段来定义自定义命令。每个自定义命令包含以下属性：

- **key**：触发命令的关键词（字符串）
- **command**：实际执行的服务器命令（字符串）
- **permission**：权限级别（整数）

示例配置如下：

```yaml
customCommand:
  - key: "加白名" #执行关键词，可使用"/执行 关键词 参数1 参数2"来执行自定义命令
    command: "whitelist add &1" #&1为参数占位符，第一个参数为&1，第二个&2，以此类推
    permission: 0 #0是普通权限，大于0则为管理员权限

  - key: "管理加白名"
    command: "whitelist add &1"
    permission: 1
```

## 🤝 参与贡献

欢迎提交PR或通过[Discussions](https://github.com/HuHoBot/KotlinMergeAdapter/discussions)提出建议

