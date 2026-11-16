package cn.huohuas001.bot.provider

import cn.huohuas001.bot.tools.Cancelable
import java.util.concurrent.CompletableFuture


interface SchedulerProvider {
    fun submit(task: Runnable): Cancelable

    /**
     * 提交不应阻塞服务器主线程的任务。
     * 平台端可以覆盖为自身的异步调度器；默认使用 JVM 公共线程池。
     */
    fun submitAsync(task: Runnable): Cancelable {
        val future = CompletableFuture.runAsync(task)
        return object : Cancelable {
            override fun cancel() {
                future.cancel(true)
            }
        }
    }

    fun submitLater(delay: Long, task: Runnable): Cancelable

    fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable
}