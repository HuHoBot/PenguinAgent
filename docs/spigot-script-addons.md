# 小型脚本扩展

主包内置 Rhino JavaScript、LuaJ 和 Java 源码扩展接口。无需下载外部语言虚拟机；不再提供 Python 或大型完整运行时包。不会删除用户原有脚本或数据。

## 目录与命令

每个扩展放到 `plugins/HuHoBotPenguin/addons/<目录名>/`，入口为 `main.js`、`main.lua` 或 `Main.java`。`metadata.yaml` 可声明 `name`、`version`、`author`、`description` 和 `entry`。

- `/huhobot scripts reload [目录名]`：重载扩展，同时撤销旧命令、监听器、任务、回调及元数据。
- `/huhobot scripts install <目录名> <HTTPS地址[,镜像]> <SHA256>`：下载、校验并解包，不自动执行。确认来源可信后再加载。
- `/huhobot modules install <font-名字> <HTTPS地址[,镜像]> <SHA256>`：安装字体；校验后下次重启生效。

数据与配置放在 `plugins/HuHoBotPenguin/data/addons/data/`、`plugins/HuHoBotPenguin/data/addons/config/`，重载不删除。Java 源码入口需完整 JDK，实现 `agent.spigot.scripting.AddonProgram`，提供 `start(BirdScriptApi api)` 和可选 `stop()`。

## JavaScript 示例

```javascript
Bird.log('ready');
Bird.onCommand('hello', function(sender, label, args) {
    Bird.tell(sender, 'Hello ' + sender.getName());
});
Bird.runTaskLater(function() { Bird.log('deferred callback'); }, 20);
```

JavaScript 使用 ES6 模式。它不是 Node.js，不提供 npm、浏览器 DOM 或全部新 ECMAScript 功能。JSON、数组/对象、Java 互操作和延后函数回调可直接使用。

## 调度与状态

```javascript
var p = Bird.getPlayer('Astra');
Bird.runForPlayer(p, function() {
    Bird.log(Bird.getHealth(p));
    Bird.giveItem(p, 'STONE', 1);
});
Bird.runAt('world', 0, 64, 0, function() {
    Bird.log(Bird.getBlockType('world', 0, 64, 0));
});
```

- `runTask`、`runTaskLater`、`runTaskTimer`：非玩家、非方块任务；tick 为时间单位。
- `runTaskAsync`、`runTaskTimerAsync`：不访问游戏世界状态的工作。
- `runForPlayer`：玩家实时读取和修改；`runAt`：指定位置的方块操作。
- `cancelTask(id)` 只取消本扩展登记的任务。每扩展至多 256 个任务。
- 不应在游戏回调中 sleep、等待另一区域的同步结果或发起阻塞网络请求。直接使用 `Bukkit`/`server` 对象时仍须遵守其线程约束。

## 网络与资源

```javascript
Bird.fetch('https://example.com/data.json', function(result) {
    Bird.log(result.status);
    if (result.ok) Bird.log(JSON.parse(result.body));
});
```

支持 HTTP(S)；每扩展最多 4 个并发，回复最多 4MiB，请求正文最多 1MiB。网络操作在后台执行；回调不会在卸载后重新进入旧上下文。重载取消已登记任务并断开活动连接。

每个 JS 扩展独立持有上下文；回调串行进入且保留词法作用域。解释执行的指令预算可中断 JS 死循环，但不能阻止可信 Java 宿主代码执行阻塞操作。此接口具有插件级权限，不是安全沙箱。只安装可审查、可信来源的扩展。

源码编译、字体解码、网络下载和文件校验不应阻塞游戏线程。文件保存接口限制在扩展自己的目录中；下载归档拒绝路径穿越、覆盖已有目录及过大内容。

## 完整描述文件与配置

启动后 `data/guide/` 会生成教学文件；它们不是活动配置，也不会自动加载或执行。新手依次复制：

1. `addons-js.example` → `addons/hello/main.js`（Lua、Java分别用相应示例）。
2. `metadata.example.yaml` → `addons/hello/metadata.yaml`。
3. 修改扩展名和入口，再执行当前根命令下的 `scripts reload hello`。只有短指令冲突时才使用插件给出的回退根命令。

完整 `metadata.yaml`：

```yaml
# 扩展名唯一，不含 /、反斜杠或 ..；改名会改变配置/数据的对应关系。
name: hello
version: '1.0.0'
author: 'Server owner'
description: '问候示例扩展'
# JS: main.js；Lua: main.lua；Java: Main.java。
entry: main.js
```

描述文件只读取顶层字符串，不支持嵌套、数组、多行块；注释必须独立占行，不能接在值后。省略描述文件时名称/作者用目录名、版本用 `1.0.0`、描述为空，入口按 `main.js` → `main.lua` → `Main.java` 顺序查找。入口必须在扩展自己的目录内。

可选 `_conf_schema.json`：

```json
{"greeting":{"type":"string","default":"你好"}}
```

缺省值填进 `data/addons/config/hello.json`；JS 用 `config.getString('greeting')`，Lua 用 `config:getString('greeting')`。在该 JSON 中加 `"_enabled": false` 可禁用扩展。`kv` 和 `DATA_DIR` 用于持久状态。`requirements.txt` 每行一个依赖，仅供预检提示，不会自动运行 npm、pip 或 Maven。

## Lua 示例

目录 `addons/hello-lua/`，`metadata.yaml` 中 name 为 `hello-lua`，entry 为 `main.lua`：

```lua
Bird:log('hello Lua addon ready')
Bird:registerBotCommand('hello-lua', 'Lua问候', 'say 你好，来自Lua扩展', 0, false)
```

Lua 访问 Bird/config/kv 的实例方法用冒号。QQ 指令登记的第一个参数必须与 metadata 中的扩展名匹配。

## Java 示例

目录 `addons/hello-java/`，name 为 `hello-java`，entry 为 `Main.java`。运行服务器必须是完整 JDK，而不只是精简 JRE：

```java
import agent.spigot.scripting.AddonProgram;
import agent.spigot.scripting.BirdScriptApi;

public final class Main implements AddonProgram {
    @Override public void start(BirdScriptApi api) {
        api.log("hello Java addon ready");
        api.registerBotCommand("hello-java", "Java问候", "say 你好，来自Java扩展", 0, false);
    }
    @Override public void stop() {
        // 自行创建的线程、连接由扩展自己关闭。
    }
}
```

Bird 登记的命令/任务随卸载清理；绕开 Bird 创建的资源不在清理范围内。扩展有插件级宿主权限，不是给陌生用户运行任意代码的沙箱。主包不再提供 Python 或大型完整运行时；不会因此删除旧数据。
