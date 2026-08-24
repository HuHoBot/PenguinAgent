# 在线列表配置

`/查在线` 用于在群内查询服务器当前在线玩家。服主可以通过 `motd` 配置控制在线列表的显示方式，也可以创建 `online.md`，让查询结果附带自定义 Markdown 内容。

## 基础配置

在线列表相关配置位于 `motd` 下。不同适配器可能使用 `config.json` 或 `config.yaml`，字段含义一致。

### config.json

```json
{
  "motd": {
    "server_ip": "play.example.com",
    "server_port": 19132,
    "api": "http://motd.txssb.cn/api/app_img?ip={server_ip}&port={server_port}&dark=true&lang=zh-CN",
    "text": "共{online}人在线",
    "output_online_list": true,
    "post_img": true,
    "markdown": true,
    "customMarkdown": false
  }
}
```

### config.yaml

```yaml
motd:
  server_ip: "play.hypixel.net"
  server_port: 25565
  api: "http://motd.txssb.cn/api/app_img?ip={server_ip}&port={server_port}&dark=true&lang=zh-CN"
  text: "共{online}人在线"
  output_online_list: true
  post_img: true
  markdown: true
  customMarkdown: false
```

## 字段说明

| 字段                      | 类型   | 说明                                                        |
| ------------------------- | ------ | ----------------------------------------------------------- |
| `motd.server_ip`          | 字符串 | 服务器地址                                                  |
| `motd.server_port`        | 数字   | 服务器端口                                                  |
| `motd.api`                | 字符串 | Motd 图片接口，支持 `{server_ip}` 和 `{server_port}` 占位符 |
| `motd.text`               | 字符串 | 普通文本模式下的提示文本，`{online}` 会替换为当前在线人数   |
| `motd.output_online_list` | 布尔值 | 普通文本模式下是否输出在线玩家列表                          |
| `motd.post_img`           | 布尔值 | 是否发送 Motd 图片                                          |
| `motd.use-markdown`           | 布尔值 | 是否使用 Markdown 模式返回在线玩家列表                      |


## Markdown 模式

当 `motd.use-markdown` 为 `true` 时，HuHoBot 会使用 Markdown 模式返回在线玩家列表。

如果你想使用普通文本模式，可以关闭它：

```json
{
  "motd": {
    "markdown": false
  }
}
```

```yaml
motd:
  use-markdown: false
```

关闭 Markdown 模式后，`motd.output_online_list` 和 `motd.text` 会参与生成普通文本内容。

## 自定义 online.md

如果你想在 `/查在线` 的结果中附带公告、规则、群链接、服务器介绍等内容，可以修改Markdown

```text
plugins/HuHoBotPenguin/Markdown/online.md
```

也就是说，`online.md` 要放在`Markdown`文件夹中。

## online.md 示例

下面是一个可以直接使用的 `online.md` 示例：

```markdown
# {{.server}}查在线结果

---

![Motd #700px #389px]({{.img_url}})

- **当前服内有**`{{.online_num}}`**位玩家**
- **名单如下:**

{{.player}}
```

保存后，下一次群内使用 `/查在线` 时，HuHoBot 会读取 `online.md`，并把文件内容作为自定义 Markdown 返回。

### 可用变量

| 变量              | 说明          |
| ----------------- | ------------- |
| `{{.server}}`     | 服务器名称    |
| `{{.img_url}}`    | Motd 图片链接 |
| `{{.online_num}}` | 当前在线人数  |
| `{{.player}}`     | 在线玩家列表  |

- 本Markdown需遵守QQ开放平台规则，若出现链接、违禁词等会导致无法发送
- Markdown规则可参考[QQ开放平台文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/type/markdown.html)
- 注：图片需要在`[]`内填写图片大小，如示例所示

## 故障排查

### 查询在线时没有自定义内容

请检查：

- `config.json` 或 `config.yaml` 中 `motd.customMarkdown` 是否为 `true`
- `online.md` 是否放在 HuHoBot 配置文件同目录下
- 文件名是否完全为 `online.md`，不要写成 `online.md.txt`
- 修改配置后是否已经重载或重启插件

### 控制台提示无法读取 online.md

如果控制台出现：

```text
无法读取online.md，请检查文件是否存在。
```

读取失败时，普通在线列表仍会正常返回，自定义 Markdown 内容会为空。
