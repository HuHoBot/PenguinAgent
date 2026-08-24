# HuHoBot Websocket 协议-请求

## 握手包(shakeHand)
- 消息类型(type): `"shakeHand"`
- 说明：握手包，用于客户端与服务器建立连接
- 请求Body
    ```json5
    {
        "serverId":"", // 服务器ID(由客户端自行生成，格式应为32个字符长度的十六进制字符串，不可为空)
        "hashKey":"", // 服务器密钥(由服务端绑定后生成，可为空)
        "name":"", // 服务器名称(在"/在线服务器"中显示的名称，可为空)
        "version":"", // 插件版本(自行定义字符串，格式为"x.x.x",不可为空)
        "platform":"", // 插件平台(自定义平台名称，不可为空)
    }
    ```
- 回报Body: 该包体带有`shaked`事件，请前往[HuHoBot Websocket 协议-事件](events.md#shaked)查看

- 服务器连接成功后第一件事情就是发送握手包，否则服务器无法标记该客户端
- 如果是一个新的平台，请与管理员联系注册平台，否则会无法连接，但可以先设置版本为`"dev"`便可正常连接

## 反馈包(respone)
- 消息类型(type): `"success"`或`"error"`
- 说明：反馈信息
- 请求Body
    ```json5
    {
        "msg":"", //反馈信息，可填写Json字符串(见下文)
    }
    ```
- 回报Body: 无
- 反馈信息可填写的Json字符串格式
    ```json5
    {
        "text": "这是返回的文本消息",//可留空
        "imgUrl": "https://example.com/image.jpg"//可留空
    }
    ```
- 该请求需要与对应事件的`id`相同才能正常使用(请见[事件](events.md))

## 查询白名单返回结果(queryWl)
- 消息类型(type): `"queryWl"`
- 说明：反馈白名单结果
- 请求Body
    ```json5
    {
        "list":"", //查询结果
    }
    ```
- 回报Body: 无
- 该请求需要与对应事件的`id`相同才能正常使用(请见[事件](events.md))

## 查询在线玩家返回结果(queryOnline)
- 消息类型(type): `"queryOnline"`
- 说明：反馈白名单结果
- 请求Body
    ```json5
    {
        "msg":"", //查询结果
        "url":"", //服务器进服IP:端口（如：127.0.0.1:25565）
        "imgUrl":"", //服务器Motd消息中的图片链接(若不填则使用机器人默认地址)
        "post_img":"", //是否在Motd消息中显示图片
        "serverType":"", //服务器类型（bedrock或java）
    }
    ```
- 回报Body: 无
- 该请求需要与对应事件的`id`相同才能正常使用(请见[事件](events.md))

## 绑定确认(bindConfirm)
- 消息类型(type): `"bindConfirm"`
- 说明：绑定请求确认
- 请求Body
    ```json5
    {}
    ```
- 回报Body: 无
- 该请求需要与对应事件的`id`相同才能正常使用(请见[事件](events.md))

## 发送消息(chat)
- 消息类型(type): `"chat"`
- 说明：用于回复`/发消息`的消息
- 请求Body
    ```json5
    {
        "serverId":"", //服务器的serverid
        "msg":"", //组合好的消息
        "msgType":"" //发送消息的类型
    }
    ```
- 回报Body: 无
