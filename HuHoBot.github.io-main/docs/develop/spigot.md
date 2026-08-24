# Spigot/Paper 适配器

本文介绍如何为 Spigot、Paper 或兼容 Bukkit API 的服务端编写 HuHoBotPenguin 附属插件。

## 引入 SDK

将 `HuHoBot-Penguin_Spigot-x.y.z.jar` 放到项目的 `libs` 目录：

```kotlin
dependencies {
    compileOnly(files("libs/HuHoBot-Penguin_Spigot-x.y.z.jar"))
    compileOnly("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")
}
```

`plugin.yml`：

```yaml
name: MyHuHoBotAddon
main: com.example.myaddon.MyHuHoBotAddon
version: 1.0.0
api-version: '1.16'
depend:
  - HuHoBotPenguin
```

`depend` 保证 HuHoBotPenguin 先于附属插件加载。

## 获取主插件实例

```java
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import org.bukkit.plugin.Plugin;

Plugin raw = getServer().getPluginManager().getPlugin("HuHoBotPenguin");
if (raw instanceof HuHoBotSpigot) {
    HuHoBotSpigot bot = (HuHoBotSpigot) raw;
}
```

Kotlin：

```kotlin
val bot = HuHoBotSpigot.getInstance()
```

### 查询认证 QQ 号

查询指定群中某个 OpenID 已绑定的 QQ 号；如果没有认证，返回 `null`：

```java
String qq = bot.getAuthenticatedQq(groupOpenId, openId);
if (qq == null) {
    // 当前 OpenID 未认证
} else {
    getLogger().info("已认证 QQ: " + qq);
}
```

方法签名：

```java
String getAuthenticatedQq(String groupOpenId, String openId);
```

## 注册命令和发送消息

```java
HuHoBotSpigot bot = (HuHoBotSpigot) raw;

bot.registerBotCommand(
    "hello",
    "say Hello {params}",
    0,
    true
);

bot.sendBotText("发送到配置中的所有 QQ 群");
bot.sendBotText(groupOpenId, "发送到指定 QQ 群");
bot.sendBotMarkdown("# Markdown");
bot.sendBotMarkdown(groupOpenId, markdown, keyboard);
```

方法说明：

- `permission > 0`：仅管理员可执行
- `pushMenu = true`：同步到 QQ 指令面板
- 指定群发送方法返回 `boolean`
- `unregisterBotCommand(key)` 用于移除运行时注册的命令

## 命令模板占位符

注册自定义命令时，支持以下占位符：

| 占位符 | 说明 |
|---|---|
| `{params}` | 完整参数 |
| `{group}` | 群 ID |
| `{user}` | 用户 ID |
| `{name}` | 绑定的 MC 玩家名（未绑定则返回 QQ 用户名） |
| `{nickname}` | 用户的 QQ 昵称 |
| `{0}`、`{1}` | 按空格拆分后的参数 |
| `&1`、`&2` | 按空格拆分后的参数 |

示例：

```java
// 注册一个发送 QQ 消息的命令
bot.registerBotCommand(
    "公告",
    "say 公告：{params}",
    0,
    true
);
// 用户执行 /执行 公告 服务器维护
// 实际执行：say 公告：服务器维护
```

## 游戏内命令

HuHoBotPenguin 注册了以下游戏内命令：

### /qqbind

完成 QQ 绑定的游戏内验证：

```
/qqbind <验证码>
```

验证码从 QQ 群 `/绑定` 命令获取，5 分钟内有效。

### /at

从游戏内向 QQ 群发送 @提醒消息：

```
/at <群成员昵称> <消息内容>
```

- 支持昵称自动补全（Tab 键）
- 如果昵称对应有效的 OpenId，会发送真正的 @提醒

## 注意事项

事件会在 Bukkit 主线程触发。监听器中不要进行同步网络请求或长时间数据库操作，必要时使用 Bukkit Scheduler 异步执行。
