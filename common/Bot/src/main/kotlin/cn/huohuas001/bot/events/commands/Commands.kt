package cn.huohuas001.bot.events.commands

/**
 * 指令注解
 *
 * 标记在 [BaseCommand] 子类的方法上，声明该方法可以处理的指令名称与面板描述。
 *
 * 收到群消息后,[BaseCommand.handleMessage] 会去掉 @提及 与前导 `/`,
 * 若消息以某个指令名开头则调用对应方法,并将指令后面的内容作为参数传入。
 *
 * 方法签名约定(按需取前几个参数,顺序固定):
 * ```
 * @Commands("发信息", "发送消息到游戏")
 * fun sendGameMessage(api: HuHoBot, message: GroupMessageEvent, params: String?)
 * ```
 *
 * @param command 指令名，同时作为 QQ 指令面板中的名称
 * @param describe 指令说明，同时作为 QQ 指令面板中的描述
 * @param onlyAdmin 是否仅管理员可用，同时写入 QQ 指令面板的 `only_admin`
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Commands(
    val command: String,
    val describe: String,
    val onlyAdmin: Boolean = false
)

/** 已完成注册、可用于消息分发及 QQ 指令面板同步的指令元数据。 */
data class RegisteredCommand(
    val command: String,
    val describe: String,
    val onlyAdmin: Boolean = false
)
