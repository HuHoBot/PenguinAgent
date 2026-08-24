# HuHoBot Websocket 协议

## 接口地址
- 请向管理员(HuoHuas001)获取接口地址。

## 接口说明

!!! note 
    本说明以[HuHoBot Spigot Adapter](../Adapter/Spigot.md) (Java) 为例.

- 请求包体结构
    ```json5
    {
        "header":{
            "type":"", // 消息类型
            "id":"", // 消息ID (许多回执命令依靠这个ID,与ServerId生成器相同)
        },
        "body":{
            //见各个消息类型的body字段
        }
    }
    ```

- 返回包体结构
    ```json5
    {
        "header":{
            "type":"", // 消息类型
            "id":"", // 消息ID (许多回执命令依靠这个ID,与ServerId生成器相同)
        },
        "body":{
            //见各个消息类型的body字段
        }
    }
    ```

- 心跳包
    - 需要注意的是，服务器会每隔`15s`检查客户端是否发送过心跳包，如果超过`15s`没有发送心跳包，服务器会认为客户端已经断开连接，并断开连接。