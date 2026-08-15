package cn.huohuas001.bot.events.commands

/**
 * 指令注解
 *
 * 标记在 [BaseCommand] 子类的方法上,声明该方法可以处理的指令名称。
 * 一个方法可以同时声明多个指令名,如 `@Commands("发信息", "send")`。
 *
 * 收到群消息后,[BaseCommand.handleMessage] 会去掉 @提及 与前导 `/`,
 * 若消息以某个指令名开头则调用对应方法,并将指令后面的内容作为参数传入。
 *
 * 方法签名约定(按需取前几个参数,顺序固定):
 * ```
 * @Commands("发信息")
 * fun sendGameMessage(api: HuHoBot, message: GroupMessageEvent, params: String?)
 * ```
 *
 * @param value 指令名列表
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Commands(vararg val value: String)
