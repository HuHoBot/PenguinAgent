package cn.huohuas001.bot.provider


import java.util.concurrent.CompletableFuture

interface CommandProvider {
    /** 各平台实现的原生命令执行方法，不处理 HuHoBot 自定义命令。 */
    fun dispatchCommand(command: String): CompletableFuture<HExecution>
}
