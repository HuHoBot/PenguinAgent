# HuHoBot 自定义Allay附属插件

### 1. 准备工作

#### 1.1 确保 HuHoBot 已安装

在使用 `HuHoBot 附属插件` 前，请确保你的服务器已正确安装并配置了 `HuHoBot` 插件。

#### 1.2 获取最新版 HuHoBot JAR 文件

从 GitHub Releases 页面下载最新版本的 `HuHoBot-AllayMC.jar` 文件：

- 访问 [HuHoBot Releases](https://github.com/HuHoBot/AllayMCAdapter/releases)
- 下载最新版本的 `HuHoBot-AllayMC.jar`

### 2. 配置项目依赖

#### 2.1 添加 HuHoBot 作为编译时依赖

在你的 `build.gradle` 文件中添加以下内容，将本地 HuHoBot JAR 文件作为编译时依赖：

```gradle 
dependencies {
    compileOnly(fileTree("libs") { include("*.jar") })
}
```

### 3. 开发自定义命令监听器

#### 3.1 创建插件类

创建一个新的 Java 类 `JavaPluginTemplate.java`，继承 `Plugin`：

```java 
package me.yourpackage;

import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.plugin.Plugin;
import org.allaymc.api.server.Server;


@Slf4j
public class JavaPluginTemplate extends Plugin {

    @Override
    public void onEnable() {
        Server.getInstance().getEventBus().registerListener(new MyEventHandle()); //注册事件监听器
        //... 
    }
}
```

#### 3.2 创建监听器类

再创建一个新的 Java 类 `MyEventHandle.java`：

```java 
package me.yourpackage;

import org.allaymc.api.eventbus.EventHandler;
import cn.huohuas001.huHoBot.Api.BotCustomCommand;

public class MyEventHandle {

    @EventHandler
    private void onBotCommand(BotCustomCommand event) {
        String keyWord = "test"; //触发该命令的关键字
        if(event.getCommand().equals(keyWord)){
            event.respone("test","success"); //返回信息
            event.setCancelled(true); //一定要取消，证明你接管了这个命令
        }
    }

}
```

### 4. 编译与部署

#### 4.1 编译插件

运行 Gradle 构建任务以编译你的插件：

```bash 
./gradlew shadowJar
```

#### 4.2 部署插件

将生成的 JAR 文件（位于 `build/libs` 目录）复制到 Minecraft 服务器的 `plugins` 目录，并重启服务器。

### 5. 测试自定义命令

#### 5.1 发送测试命令

在群内发送消息 `@HuHoBot /关键词` 查看是否正确触发监听器。

### 6. 注意事项

- 确保 `HuHoBot` 和 `插件` 的版本兼容性。
- 如果遇到类找不到错误，请检查 HuHoBot JAR 文件路径和版本号是否正确。
- 根据实际需求扩展 `onCommandSend` 方法中的逻辑，实现更多自定义功能。

---

通过以上步骤，你就可以成功地开发并部署一个基于 `HuHoBot` 的自定义命令监听器插件。如果有任何问题或建议，请随时提交 Issue 或
PR！

### 附录：事件说明
- 详见[事件](Events.md)

### 致谢
希望这份文档能帮助你更好地理解和开发 `HuHoBot附属插件`！
