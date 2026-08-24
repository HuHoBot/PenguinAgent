# HuHoBot Nukkit-MOT Adapter

[![GitHub Release](https://img.shields.io/github/v/release/HuHoBot/KotlinMergeAdapter?style=for-the-badge)](https://github.com/HuHoBot/KotlinMergeAdapter/releases)
[![License](https://img.shields.io/github/license/HuHoBot/KotlinMergeAdapter?style=for-the-badge)](https://github.com/HuHoBot/KotlinMergeAdapter/blob/main/LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/HuHoBot/KotlinMergeAdapter/build.yml?style=for-the-badge)](https://github.com/HuHoBot/KotlinMergeAdapter/actions)

新一代Minecraft服务器管理机器人解决方案，突破传统机器人框架限制，提供更安全稳定的交互体验。

## 🌟 核心优势

| 特性     | 传统方案             | HuHoBot           |
|--------|------------------|-------------------|
| 账号安全   | ❌ 需要实体QQ号，存在封号风险 | ✅ 无QQ第三方客户端依赖，零风控 |
| 部署复杂度  | ❌ 需搭建完整机器人框架     | ✅ 即装即用，一键绑定       |
| 服务器兼容性 | ❌ 部分面板服不支持       | ✅ 全平台兼容，有网即用      |
| 协议更新影响 | ❌ 需要频繁适配新协议      | ✅ 协议无关设计，相对稳定     |

### 进阶功能
  - 自定义命令系统(详见下文)

## 📥 安装指南

### 环境要求

- Java 17+ Runtime
- **任意支持的 Nukkit-MOT 核心**

### 快速开始

1. **访问 GitHub Releases 页面**：
  - 打开浏览器，访问 [HuHoBot Adapter Releases](https://github.com/HuHoBot/KotlinMergeAdapter/releases)
  - 下载最新版本的 `HuHoBot-vx.x.x-Nukkit-MOT.jar` 文件

2. **参照** [快速开始](../QuickStart/index.md)

### 高级配置（可选）

- 如果需要自定义功能，请参考`⚙️ 配置示例`进行详细设置

---

#### 注意事项：

- 确保服务器已正确安装 Java 17+ 运行时环境
- 插件兼容所有支持的 Nukkit-MOT 核心版本，具体版本请参考官方文档

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
  post_prefix: "" #服内消息转发到群内时的前缀

motd:
  server_ip: "play.easecation.net" #使用"/查在线"时的Motd图片地址（改成你的进服地址）
  server_port: 19132 #使用"/查在线"时的Motd图片端口（改成你的进服端口）
  api: "https://motdbe.blackbe.work/status_img?host={server_ip}:{server_port}" #使用"/查在线"时的Motd图片地址Api（无特殊需求勿动）（必须返回图片）
  text: "共{online}人在线" #使用"/查在线"时的Motd文本，可使用PlaceholderAPI，留空不显示
  output_online_list: true #是否显示在线列表
  post_img: true #是否显示Motd图片

callbackConvertImg: 0 #命令回调转换成图片的行数（0为不转换）

#自定义执行命令
customCommand:
  - key: "加白名" #执行关键词，可使用"/关键词 参数1 参数2"来执行自定义命令
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
  - key: "加白名" #执行关键词，可使用"/关键词 参数1 参数2"来执行自定义命令
    command: "whitelist add &1" #&1为参数占位符，第一个参数为&1，第二个&2，以此类推
    permission: 0 #0是普通权限，大于0则为管理员权限

  - key: "管理加白名"
    command: "whitelist add &1"
    permission: 1
```

## 🤝 参与贡献

欢迎提交PR或通过[Discussions](https://github.com/HuHoBot/KotlinMergeAdapter/discussions)提出建议

