package agent.provider

import agent.tools.Cancelable
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

    /**
     * 延迟执行任务。
     *
     * @param delay 延迟，单位 **tick**（20 tick = 1 秒）。需要毫秒时请自行换算，
     *              例如 `submitLater(ms / 50)`。
     */
    fun submitLater(delay: Long, task: Runnable): Cancelable

    /**
     * 周期执行任务。
     *
     * @param delay 首次延迟，单位 **tick**（20 tick = 1 秒）
     * @param period 周期间隔，单位 **tick**
     */
    fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable
}