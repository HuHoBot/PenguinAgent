# 事件

## 触发自定义命令(BotCustomCommand)
- 在 `BotCustomCommand` 事件中，`event.data` 包含以下 JSON 数据结构：见[数据包](DataPack.md/#botcustomcommand)
- 可使用`event.isRunByAdmin()`来获取是否有管理员权限(仅Java类核心支持)
- 支持的适配器: `Spigot` `LSE` `Allay` `Nukkit-MOT`

### 附录：返回文本消息
- Java:
```java 
event.response("文本", "custom");
```

- JavaScript:
```javascript
return "文本";
```

### 附录：返回自定义响应

如果需要返回复杂的 JSON 结构，可以使用 `JSONObject` 形式的 `response` 方法：

- Java:
```java 
event.response(responseJson, "custom");
```

- JavaScript:
```javascript
return JSON.stringify({"text": `测试成功`,"imgUrl":qlogoUrl})
```

- responseJson示例：
```json
{
  "text": "这是返回的文本消息",
  //可留空
  "imgUrl": "https://example.com/image.jpg"
  //可留空
}
```

---


## 触发查询白名单(BotQueryWhiteList)
- `BotQueryWhiteList`事件中，包含以下方法

```java
event.getKeyWord() // 获取关键词(可能为Null)
event.getPages() // 获取页码(可能为Null)
event.getQueryMode() //  获取查询模式(enum枚举:BotQueryWhiteList.QueryMode)
event.responseList(List<String> results, int totalPages) // 返回一个列表(自动格式化)
event.responeString(String whiteList) // 返回一个字符串
```

- 支持的适配器: `Spigot`
