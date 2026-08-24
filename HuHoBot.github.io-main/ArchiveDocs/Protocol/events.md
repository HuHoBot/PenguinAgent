# HuHoBot Websocket 协议-事件

## 回执消息
- 服务器会向客户端发送回执消息，用于部分交互
- 收到的`packId`需要与向服务端发送回去的`packId`相同，否则不会生效
- 在下文中会有`回执消息`的提示来告诉你这是否是一个回执消息

## 下发配置(sendConfig)
- 消息类型(type): `"sendConfig"`
- 说明：服务器向客户端下发配置
- 事件Body
    ```json5
    {
        "serverId":"", // 服务器ID
        "hashKey":"", // 密钥(请将此密钥保存至配置文件中，用于下一次握手时使用)
    }
    ```
- 回执消息： `否`
- `注：下发完成后请自行重新连接并握手`

## 握手反馈(shaked)
- 消息类型(type): `"shaked"`
- 说明：客户端向服务器反馈握手情况
- 事件Body
    ```json5
    {
        "code": 0, // 状态码(见下文解读)
        "msg": "", // 状态信息
    }
    ```
    + 状态码解读

        |  状态码 | 状态 |
        | :----: | :------: |
        |1 | 握手成功 |
        |2 | 带有附加消息的握手成功 |
        |3 | 客户端密钥(hashKey)不匹配 |
        |4 | 客户端版本(version)不匹配 |
        |5 | BotClient尚未连接，等待握手成功 |
        |6 | 等待绑定 |
        |7 | IP未授权 |
        |8 | 服务器被Ban |
        |其他| 根据状态信息决定|

- 回执消息： `否`

## 聊天信息(chat)
- 消息类型(type): `"chat"`
- 说明：聊天信息发送至服务器
- 事件body
    ```json5
    {
        "nick":"", //发送者昵称
        "msg":"", //发送的消息
    }
    ```
- 回执消息： `是`
    - 使用请求中的[发送消息(chat)](requests.md#chat)来回复这个消息，发送到群里

## 添加白名单(add)
- 消息类型(type): `"add"`
- 说明：为玩家添加白名单
- 事件body
    ```json5
    {
        "xboxid":"", //玩家名
    }
    ```
- 回执消息： `是`
    - 使用请求中的[反馈包(respone)](requests.md#respone)回复是否添加成功的消息


## 删除白名单(delete)
- 消息类型(type): `"delete"`
- 说明：为玩家删除白名单
- 事件body
    ```json5
    {
        "xboxid":"", //玩家名
    }
    ```
- 回执消息： `是`
    - 使用请求中的[反馈包(respone)](requests.md#respone)回复是否删除成功的消息

## 执行命令(cmd)
- 消息类型(type): `"cmd"`
- 说明：向控制台发送命令
- 事件body
    ```json5
    {
        "cmd":"", //命令
    }
    ```
- 回执消息： `是`
    - 使用请求中的[反馈包(respone)](requests.md#respone)回复命令执行结果

## 查询白名单(queryList)
- 消息类型(type): `"queryList"`
- 说明：查询服务器内白名单文件
- 事件body
    ```json5
    {
        "key":"", //查询关键词
        "page": 0 //页码
    }
    ```
- 注: 传入的包体可能是`key`或者`page`两者其一，需要自行判断并做出选择
- 回执消息： `是`
    - 见请求中的[查询白名单返回结果(queryWl)](requests.md#querywl)

## 查询在线玩家(queryOnline)
- 消息类型(type): `"queryOnline"`
- 说明：查询服务器内在线玩家
- 事件body
    ```json5
    {}
    ```
- 回执消息： `是`
    - 见请求中的[查询在线玩家返回结果(queryOnline)](requests.md#queryonline)

## 服务端命令关闭连接(shutdown)
- 消息类型(type): `"shutdown"`
- 说明：服务端命令客户端关闭连接
- 事件body
    ```json5
    {
        "msg":"" //原因
    }
    ```
- 回执消息： `否`

## 执行自定义命令(run)
- 消息类型(type): `"run"`
- 说明：使用`/执行`执行自定义命令
- 事件body
    ```json5
    {
        "key": "关键字",
        "runParams": [
            "参数1",
            "参数2"
        ],
        "author": {
            "qlogoUrl": "用户头像URL",
            "bindNick": "绑定昵称",
            "openId": "用户OpenID"
        },
        "group": {
            "openId": "群组OpenID"
        }
    }
    ```
- 回执消息： `是`
    - 使用请求中的[反馈包(respone)](requests.md#respone)回复命令执行结果

## 管理员执行自定义命令(runAdmin)
- 消息类型(type): `"runAdmin"`
- 说明：使用`/管理员执行`执行自定义命令
- 事件body
    ```json5
    {
        "key": "关键字",
        "runParams": [
            "参数1",
            "参数2"
        ],
        "author": {
            "qlogoUrl": "用户头像URL",
            "bindNick": "绑定昵称",
            "openId": "用户OpenID"
        },
        "group": {
            "openId": "群组OpenID"
        }
    }
    ```
- 回执消息： `是`
    - 使用请求中的[反馈包(respone)](requests.md#respone)回复命令执行结果

- 若使用与上文`执行自定义命令(run)`同样的代码，请注意判断权限问题

## 心跳包返回(heart)
- 消息类型(type): `"heart"`
- 说明：心跳包返回
- 事件body
    ```json5
    {}
    ```
- 回执消息： `否`

## 绑定请求(bindRequest)
- 消息类型(type): `"bindRequest"`
- 说明：绑定请求
- 事件body
    ```json5
    {
        "bindCode":"", // 绑定码
    }
    ```
- 回执消息： `是`
    - 使用请求中的[绑定确认(bindConfirm)](requests.md#bindconfirm)进行确认



