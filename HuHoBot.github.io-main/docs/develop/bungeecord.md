# BungeeCord 适配器

!!! warning

    本文描述的是上游 PenguinClient 的 BungeeCord 适配器。HuHoBotPenguin 分支当前仅支持 Spigot/Paper。

本文介绍如何为 BungeeCord 编写 HuHoBotPenguin Proxy 附属插件。

!!! note

    BungeeCord 和 Velocity 共用 `HuHoBot-Penguin_Proxy-x.y.z.jar`，但事件包名和平台注册方式不同。本页只介绍 BungeeCord。

## 引入 SDK

```kotlin
dependencies {
    compileOnly(files("libs/HuHoBot-Penguin_Proxy-x.y.z.jar"))
    compileOnly("net.md-5:bungeecord-api:1.16-R0.4")
}
```

`bungee.yml`：

```yaml
name: MyHuHoBotAddon
main: com.example.myaddon.MyHuHoBotAddon
version: 1.0.0
depend:
  - HuHoBotPenguin
```

## 监听 QQ 群消息

事件类：

```text
cn.huohuas001.huhobotPenguin.bungee.events.OnBotRecvMsg
```

```java
package com.example.myaddon;

import cn.huohuas001.huhobotPenguin.bungee.events.OnBotRecvMsg;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

public final class BotListener implements Listener {
    @EventHandler
    public void onBotMessage(OnBotRecvMsg event) {
        String content = event.getMessage().getContent();
        if (content.equalsIgnoreCase("/hello")) {
            event.replyText("你好");
            event.setCancelled(true);
        }
    }
}
```

在 Bungee 插件的 `onEnable` 注册：

```java
getProxy().getPluginManager().registerListener(this, new BotListener());
```

## 自定义命令事件

事件类：

```text
cn.huohuas001.huhobotPenguin.bungee.events.OnBotCommand
```

```java
@EventHandler
public void onBotCommand(OnBotCommand event) {
    if ("hello".equals(event.getMessage().getCommandKey())) {
        event.replyText("参数: " + event.getMessage().getCommandArguments());
        event.setCancelled(true);
    }
}
```

## 获取主插件实例

```java
import cn.huohuas001.huhobotPenguin.bungee.HuHoBotBungee;
import net.md_5.bungee.api.plugin.Plugin;

Plugin raw = getProxy().getPluginManager().getPlugin("HuHoBotPenguin");
if (raw instanceof HuHoBotBungee) {
    HuHoBotBungee bot = (HuHoBotBungee) raw;
}
```

### 查询认证 QQ 号

```java
String qq = bot.getAuthenticatedQq(groupOpenId, openId);
// 未认证时 qq == null
```
## 注册命令和发送消息

```java
bot.registerBotCommand("hello", "say Hello {params}", 0, true);
bot.sendBotText("发送到配置中的所有群");
bot.sendBotText(groupOpenId, "发送到指定群");
bot.sendBotMarkdown("# Markdown");
bot.sendBotMarkdown(groupOpenId, markdown, keyboard);
bot.unregisterBotCommand("hello");
```

也可以使用 Proxy 共享 API：

```java
import cn.huohuas001.huhobotPenguin.proxy.api.ProxyBotApi;

ProxyBotApi.registerBotCommand("hello", "say Hello {params}");
ProxyBotApi.sendBotText(bot, "发送到配置群");
ProxyBotApi.sendBotText(groupOpenId, "发送到指定群");
```

## 回复事件

```java
event.replyText("普通文本");
event.replyMarkdown("Markdown 内容");
event.replyMarkdown("Markdown 内容", keyboard);
```

## 注意事项

BungeeCord 使用 `PluginManager.callEvent` 同步分发这些事件。事件监听器会阻塞 QQ 消息分发链，因此不要在监听方法内执行耗时网络或数据库操作。
