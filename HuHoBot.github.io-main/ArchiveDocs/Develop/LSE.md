# HuHoBot 自定义LSE附属插件

### 您可以按如下代码创建一个插件，编写一个HuHoBot拓展模块，并使用HuHoBot的API注册回调函数

```javascript
const HUHONAMESPACE = 'HuHo_Bot' //勿动
const NAMESPACE = 'Test' //修改成你自己想要的Namespace

/**
 * 回调函数
 * @param {array} keyWord
 */

function Callback(params){ 
    return "测试成功" //可以返回一个文本消息到群内
    return JSON.stringify({
        "text": `测试成功`,
        "imgUrl":qlogoUrl}
    ) //可以返回一个带图片的文本消息到群内(不发图请留空)
}

/**
 * 注册回调函数
 * @param {string} keyWord
 * @param {function} func
 */

function regCallbackEvent(type,keyWord,func){
    if(!ll.hasExported(HUHONAMESPACE,'regEvent')){
        return;
    }
    let regEvent = ll.imports(HUHONAMESPACE,'regEvent')
    ll.exports(func,NAMESPACE,func.name)
    regEvent(type,keyWord,NAMESPACE,func.name)
}

mc.listen("onServerStarted",()=>{
    let keyWord = "测试" //触发关键词
    /*NOTE:
        当注册为run时，只有普通用户可以触发，当注册为runAdmin时，只有管理员可以触发
        若要使该指令普通用户和管理员都可以触发，请同时注册两个事件回调
    */
    regCallbackEvent("run",keyWord,Callback); 
    regCallbackEvent("runAdmin",keyWord,Callback);
})
```

### 注册成功
```bash
[HuHo_Bot] 注册run类型事件: 关键词(关键词名称) 回调函数(NameSpace::CallbackFunc) 成功
```

- 当出现该信息时，表示注册成功，您可以尝试在群内使用测试命令来进行测试

### 测试自定义命令

#### 发送测试命令

在群内发送消息 `@HuHoBot /关键词` 查看是否正确触发监听器。

### 附录：事件说明
- 详见[事件](Events.md)

### 致谢
希望这份文档能帮助你更好地理解和开发 `HuHoBot附属插件`！