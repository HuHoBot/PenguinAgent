# HuHoBot Penguin v1.2.2

## 修复

- fix: 只对消息中实际被 @ 的玩家播放叮音效，不再对所有在线玩家触发
- fix: 去掉 QQ 图片消息中重复的 `[图片]` 文字，`replyWithImg` 空文本不再输出占位符
- fix: 清理 QQ→游戏消息中的 Minecraft 颜色代码（`§x`）和 ANSI 转义码，消除聊天框乱码
- fix: 清理 `/执行命令` 等游戏命令输出中的 ANSI 转义码
- fix: `/执行` 命令现在正确传递 QQ 管理员身份给游戏端，管理员可执行 `permission > 0` 的自定义命令
- fix: 配置升级器不再写入 `motd.api` 默认值，避免 `hb reload` 时生成无效 YAML 导致配置损坏

## 依赖

- 基于 [qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java) (AGPL-3.0)
