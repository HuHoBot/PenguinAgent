# 皮肤来源合同

服务端 PlayerProfile/PlayerTextures 的 getSkin() 可以直接返回 java.net.URL。旧版错误地在 URL 对象上寻找 getUrl()，现已回归测试锁住。
Authlib 的 Property 同时兼容旧 getValue() 与 record 的 value()。JSON 只取 textures.SKIN，不取 CAPE。
SkinsRestorer 当前 PlayerStorage API: getSkinOfPlayer(UUID) 返回 Optional<SkinProperty>，getSkinIdOfPlayer(UUID) 返回 Optional<SkinIdentifier>；getSkinForPlayer(UUID,String) 是可选的恢复路径，不把 SkinIdentifier 当 String 强转。
来源：https://api.github.com/repos/SkinsRestorer/SkinsRestorer/contents/api/src/main/java/net/skinsrestorer/api/storage/PlayerStorage.java
插件未加载、PAPI 变量未解析、返回为空、图片畸形时继续后备链。
CSL 支持 skins.default / skins.slim 哈希 + root/textures；LittleSkin 使用该格式。
Ely 返回 SKIN.url；Mojang 先查账号 ID 再查 session profile 的 BASE64 属性；avatar 类型直接用图。
远程输入限制大小、分辨率、协议、重定向与私网地址。网络读取只在皮肤线程池执行，并设整张图等待上限。
默认头像是 Mojang 原版 9 种，非玩家自定义材质的证明；客户端私有皮肤不能自动保证与同名站点相同。
