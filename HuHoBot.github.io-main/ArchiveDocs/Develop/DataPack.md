# 数据包


## 触发自定义命令(BotCustomCommand)
- 在 `BotCustomCommand` 事件中，`event.data` 包含以下 JSON 数据结构：
- 注: LSE中为回调函数中的`param`回调参数(String类型，需要使用`JSON.parse(param)`转换成Object对象)
- 支持的适配器: `Spigot` `LSE` `Allay` `Nukkit-MOT`

### 事件数据结构说明
```jsonc
{
  "key": "关键字",
  "runParams": [
    "参数1",
    "参数2"
  ],
  "author": {
    "qlogoUrl": "用户头像URL",
    "bindNick": "绑定昵称",
    "openId": "用户OpenID",
    "bindQQ": "认证后的QQ" //可能为null，意味着该用户没有绑定QQ
  },
  "group": {
    "openId": "群组OpenID"
  }
}
```

- **key**: 触发命令的关键词。
- **runParams**: 命令执行时传递的参数列表。
- **author**: 发送命令的用户信息。
    - **qlogoUrl**: 用户头像 URL。
    - **bindNick**: 用户绑定的昵称。
    - **openId**: 用户的 OpenID。
    - **bindQQ**: 认证后的QQ
- **group**: 群组信息。
    - **openId**: 群组的 OpenID。
