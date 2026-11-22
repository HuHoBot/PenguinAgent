由 HuHoBot Agent 自动生成：小型扩展上手教程

1. 插件主包内置 Rhino JavaScript、LuaJ 和 Java源码入口。不是Node.js，也不是浏览器，不能直接用npm/DOM。
2. 在插件根目录 addons/ 下新建一个目录，例如 hello/。一个目录就是一个扩展，不把脚本散放在addons根部。
3. 从本目录复制 addons-js.example 为 addons/hello/main.js；复制 metadata.example.yaml 为 addons/hello/metadata.yaml。
4. 根据语言选择一个入口：main.js / main.lua / Main.java。Lua与Java示例应分别放到hello-lua/和hello-java/；name要匹配示例中注册命令的扩展名。
5. metadata字段：name唯一名称；version版本；author作者；description说明；entry入口。只有这些字段，暂不支持依赖自动安装。
   描述文件使用顶层字符串，注释另起一行。不把密钥写进description或发布包。
6. 可选 _conf_schema.json 用于缺省配置：{"greeting":{"type":"string","default":"你好"}}。
   JS用config.getString('greeting')；Lua用config:getString('greeting')。值写入data/addons/config/<name>.json。
   JSON里加 "_enabled":false 可禁用扩展。kv和DATA_DIR供持久数据使用，源码重载不删除数据。
7. 可选 requirements.txt 每行一项只作依赖提示；不会联网安装npm、pip、Maven包，不提供Python入口。
8. 使用当前根命令的 scripts reload hello 加载，确认日志无错再用 /hello。扩展注册QQ指令后按面板设置显示，不保证每条都占面板位置。
9. scripts install <目录> <HTTPS地址[,镜像]> <SHA256> 只下载校验，不自动执行。确认来源、摘要和代码后再重载。
10. 完整参考见仓库 docs/spigot-script-addons.md；真实API定义见 BirdScriptApi.java。

线程与安全
- tick是游戏时间单位，通常20tick约1秒；网络/文件等耗时操作用后台API，不在游戏回调内sleep。
- 玩家操作用 Bird.runForPlayer；方块操作用 Bird.runAt。异步任务不得直接改世界。
- Bird.fetch 的请求有并发和大小上限，卸载会断开活动连接；回调不可保留后继续改世界。
- 所有语言扩展都有插件级宿主权限，不是安全沙箱。只安装可信源码；不接受群聊文本作为脚本执行。
- JS有解释执行预算但不能阻止Java宿主方法阻塞。自行创建的线程、连接需要在stop中关闭。
- 教程示例不包含网络请求、OP授权或删除操作；guide目录本身不会作为扩展扫描或运行。
