# HuHoBot EndStone Adapter

[![GitHub Release](https://img.shields.io/github/v/release/HuHoBot/EndStoneAdapter?style=for-the-badge)](https://github.com/HuHoBot/EndStoneAdapter/releases)
[![License](https://img.shields.io/github/license/HuHoBot/EndStoneAdapter?style=for-the-badge)](https://github.com/HuHoBot/EndStoneAdapter/blob/main/LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/HuHoBot/EndStoneAdapter/build.yml?style=for-the-badge)](https://github.com/HuHoBot/EndStoneAdapter/actions)

专为EndStone设计的下一代基岩版服务器管理解决方案，提供安全的无第三方QQ机器人依赖管理体验。

### 进阶功能

- **扩展API**
    - 自定义命令系统(详见下文)

## 📥 安装指南

### 环境要求

- EndStone 0.7.2+

### 快速开始

1. **访问 GitHub Releases 页面**：
    - 打开浏览器，访问 [HuHoBot-EndStoneAdapter Releases](https://github.com/HuHoBot/EndStoneAdapter/releases)
    - 下载最新版本的 `endstone-huhobot.dll` 或 `endstone-huhobot.so` 文件

2. **参照** [快速开始](../QuickStart/index.md)

### 高级配置（可选）

- 如果需要自定义功能，请参考`⚙️ 配置示例`进行详细设置

---

## ⚙️ 配置示例

```json5
{
  "chatFormat": {
		"game": "<{name}> {msg}", // 服内消息转发到群内时的文本
		"group": "群:<{nick}> {msg}", //群内消息转发到服内时的文本
		"post_chat": true, //是否在群内发送消息到服务器内
		"post_prefix": "" //服内消息转发到群内时的前缀
	},
  "customCommand": [ //见下文 "配置自定义命令"
    {
      "command": "whitelist add &1",
      "key": "加白名",
      "permission": 0
    },
    {
      "command": "whitelist add &1",
      "key": "管理加白名",
      "permission": 1
    }
  ],
  "hashKey": "", //不用管
  "motdUrl": "play.easecation.net:19132", //使用/查在线的时候显示的图片地址（改成你自己的进服地址）
  "serverId": "", //不用管
  "serverName": "EndStone", // 服务器名称
  "callbackConvertImg": 0, //命令回调转换成图片的行数（0为不转换）
  "version": 1 //不用管
}

```


### 配置自定义命令

#### 通过配置文件设置

在 `config.json` 文件中，你可以通过 `customCommand` 字段来定义自定义命令。每个自定义命令包含以下属性：

- **key**：触发命令的关键词（字符串）
- **command**：实际执行的服务器命令（字符串）
- **permission**：权限级别（整数）

示例配置如下：

```json5
{
  "customCommand": [
    {
      "command": "whitelist add &1", //&1为参数占位符，第一个参数为&1，第二个&2，以此类推
      "key": "加白名", //执行关键词，可使用"/关键词 参数1 参数2"来执行自定义命令
      "permission": 0 //0是普通权限，大于0则为管理员权限
    }
  ]
}
```

## 🤝 参与贡献

欢迎提交PR或通过[Discussions](https://github.com/HuHoBot/EndStoneAdapter/discussions)提出建议

