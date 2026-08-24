# 适配器开发

HuHoBotPenguin 为不同 Minecraft 服务端提供独立的附属插件 API。请根据运行平台阅读对应页面：

- [Spigot/Paper](spigot.md)
- [Nukkit-MOT](nukkit-mot.md)
- [BungeeCord](bungeecord.md)
- [Velocity](velocity.md)
- [Allay](allay.md)

## 公共 API

所有适配器都提供以下能力：

- 注册和注销运行时自定义命令
- 向所有配置群或指定群发送文本、Markdown
- 查询认证 QQ 号

各平台主类提供：

```text
registerBotCommand(key, command, permission, pushMenu)
unregisterBotCommand(key)
```

命令模板支持：

- `{params}`：完整参数
- `{group}`：群 ID
- `{user}`：用户 ID
- `{name}`：绑定的 MC 玩家名（未绑定则返回 QQ 用户名）
- `{nickname}`：用户的 QQ 昵称
- `{0}`、`{1}`：按空格拆分后的参数
- `&1`、`&2`：按空格拆分后的参数

`permission > 0` 表示仅管理员可以执行，`pushMenu = true` 表示同步到 QQ 指令面板。

## 查询认证 QQ 号

所有适配器主类都提供同名方法：

```text
getAuthenticatedQq(groupOpenId, openId): String?
```

返回指定群中指定 OpenID 绑定的 QQ 号；没有认证时直接返回 `null`。

Java：

```java
String qq = bot.getAuthenticatedQq(groupOpenId, openId);
```

Kotlin：

```kotlin
val qq: String? = bot.getAuthenticatedQq(groupOpenId, openId)
```

## 发送消息

```text
sendBotText(text)                    // 发送到所有配置群
sendBotText(groupOpenId, text)       // 发送到指定群
sendBotMarkdown(markdown)            // 发送 Markdown 到所有配置群
sendBotMarkdown(groupOpenId, md)     // 发送 Markdown 到指定群
```

## 线程约束

QQ 消息回调来自 QQ 客户端线程。Spigot、Nukkit、Allay 会切换到平台服务器线程后触发事件；Bungee 使用 Bungee 事件总线；Velocity 等待 `EventManager.fire` 完成后再读取取消状态。

监听器中不要执行长时间阻塞操作。网络请求、数据库操作和复杂计算应提交到平台异步调度器。

## 版本

附属插件应使用与服务器中 HuHoBotPenguin 相同版本的适配器 JAR 编译，并使用 `compileOnly`，避免将另一份 HuHoBot 类打包进附属插件。
