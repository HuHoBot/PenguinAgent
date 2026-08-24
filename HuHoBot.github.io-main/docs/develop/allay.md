# Allay 适配器

!!! warning

    本文描述的是上游 PenguinClient 的 Allay 适配器。HuHoBotPenguin 分支当前仅支持 Spigot/Paper。

本文介绍如何为 AllayMC 编写 HuHoBotPenguin 附属插件。

## 引入 SDK

将 `HuHoBot-Penguin_Allay-x.y.z.jar` 放到项目的 `libs` 目录：

```kotlin
dependencies {
    compileOnly(files("libs/HuHoBot-Penguin_Allay-x.y.z.jar"))
    compileOnly("org.allaymc.allay:api:0.17.0")
}
```

Allay 插件使用 Gradle Plugin DSL 时，也可以按照 Allay 官方 Gradle 插件方式声明 `apiOnly` 和 `api` 版本。

`plugin.json` 的依赖由 Allay Gradle 插件生成。使用 DSL：

```kotlin
allay {
    api = "0.17.0"
    apiOnly = true
    plugin {
        entrance = "com.example.myaddon.MyHuHoBotAddon"
        apiVersion = ">=0.17.0"
        name = "MyHuHoBotAddon"
        authors += "Example"
        dependency("HuHoBotPenguin")
    }
}
```

生成的依赖对象结构如下：

```json
{
  "name": "HuHoBotPenguin",
  "version": null,
  "optional": false
}
```

## 监听 QQ 群消息

事件类：

```text
cn.huohuas001.huhobotPenguin.allay.events.OnBotRecvMsg
```

Allay 使用自己的 EventBus 和 `@EventHandler`：

```kotlin
import cn.huohuas001.huhobotPenguin.allay.events.OnBotRecvMsg
import org.allaymc.api.eventbus.EventHandler

class BotListener {
    @EventHandler
    fun onBotMessage(event: OnBotRecvMsg) {
        val message = event.message
        if (message.content.equals("/hello", ignoreCase = true)) {
            event.replyText("你好，${message.sender.username}")
            event.setCancelled(true)
        }
    }
}
```

注册监听器：

```kotlin
Server.getInstance().eventBus.registerListener(BotListener())
```

## 自定义命令事件

事件类：

```text
cn.huohuas001.huhobotPenguin.allay.events.OnBotCommand
```

```kotlin
@EventHandler
fun onBotCommand(event: OnBotCommand) {
    if (event.message.commandKey == "hello") {
        event.replyText("参数: ${event.message.commandArguments}")
        event.setCancelled(true)
    }
}
```

消息 `/hello world` 对应：

```text
commandKey = hello
commandArguments = world
```

## 获取主插件实例

Allay 的插件管理器返回 `PluginContainer`：

```kotlin
val container = Server.getInstance()
    .pluginManager
    .getEnabledPlugin("HuHoBotPenguin")

val bot = container?.plugin as? HuHoBotAllay
```

### 查询认证 QQ 号

```kotlin
val qq: String? = bot?.getAuthenticatedQq(groupOpenId, openId)
if (qq == null) {
    // 当前 OpenID 未认证
}
```

未认证时返回 `null`，已认证时返回绑定的 QQ 号。
## 注册命令和发送消息

```kotlin
bot?.registerBotCommand(
    key = "hello",
    command = "say Hello {params}",
    permission = 0,
    pushMenu = true
)

bot?.sendBotText("发送到配置中的所有群")
bot?.sendBotText(groupOpenId, "发送到指定群")
bot?.sendBotMarkdown("# Markdown")
bot?.sendBotMarkdown(groupOpenId, markdown, keyboard)
bot?.unregisterBotCommand("hello")
```

命令模板支持 `{params}`、`{group}`、`{user}`、`{0}` 和 `&1` 等公共占位符。

## 回复和取消

```kotlin
event.replyText("普通文本")
event.replyMarkdown("Markdown 内容")
event.setCancelled(true)
```

Allay 的 `Event` 和 `CancellableEvent` 已提供取消状态，事件通过 `Server.getInstance().eventBus.callEvent(event)` 分发。

## 注意事项

HuHoBot 会将 QQ 回调切换到 Allay 服务线程后触发自定义事件。Allay 事件监听器中不要执行长时间阻塞操作；外部网络和数据库操作应使用异步任务。
