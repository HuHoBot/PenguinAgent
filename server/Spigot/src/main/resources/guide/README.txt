由 HuHoBot Agent 自动生成：1.23 配置与目录导航

本 guide/ 下的 .example 文件只用于教学，不会自动启用权限或执行命令。
修改前备份，YAML 用空格缩进，不用Tab。只复制所需列表项，不新建第二个同名根键。
主配置在插件目录 config.yml，运行数据在 data/，这两类路径不要混淆。

data/permission-groups.yml
见 permission-groups.example.yml。成员用 member_openid，群用 group_openid，两者不可互换。
admin 只授予插件权限，QQ管理员角色和平台白名单能力另行授权。

data/timed-tasks.yml
见 timed-tasks.example.yml。不启用写 tasks: []。持续保存/播报任务优先用它，Agent sleep 不跨重启恢复。

data/sensitive-words/*.txt
UTF-8文本，每行一个词，不需要YAML列表符号；复制 guide/sensitive-words.example.txt 后再改名。按活动审核配置使用，不要把词表上传到公共仓库。

data/groups/<group_openid>/
群专属配置与面板覆盖。优先用WebUI群设置，不清空整个目录修复个别群。

data/Markdown/
自定义QQ卡片模板。保留原模板中的占位符；修改前复制备份，不把API密钥写进卡片。

data/addons/config/<扩展名>.json 与 data/addons/data/<扩展名>/
分别保存扩展配置与持久数据，源码在插件根目录 addons/<目录名>/，重载不删数据。
见 addons-README.txt 与 addons-js.example、addons-lua.example、addons-java.example、metadata.example.yaml。

data/inventory/snapshots/
插件维护的离线背包只读快照，不要手改物品序列化内容。查图失败先核对日志，不要删除玩家原版存档。

data/logs/
QQ SDK按日日志及插件内容日志按启用配置写入。反馈问题时先移除密钥、openid和聊天正文。

data/modules/、data/fonts/、data/release-state.json
可选字体及更新状态。校验失败不加载；不要自行编辑待安装校验值或清空更新状态。

data/ 的数据库与WAL/SHM、绑定和认证文件
含运行状态。停服后做一致性备份，不在运行中删除WAL/SHM，也不要发布这些文件。

配置命令路径请以插件当前帮助为准：仅在短指令被其他插件占用时，才改用根命令下的同名子命令。
其他排错、QQ指令面板恢复与槽位说明见 data/Q&A.txt。
