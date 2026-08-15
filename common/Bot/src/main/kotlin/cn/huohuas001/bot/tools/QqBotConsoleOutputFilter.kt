package cn.huohuas001.bot.tools

import java.io.PrintStream

/**
 * 屏蔽 QQ Bot SDK 直接写入 System.out 的输出。
 *
 * 过滤依据是当前调用栈中的类名，不会根据输出文本猜测来源，因此不会误伤服务器或
 * 其他插件正常写入控制台的内容。
 */
internal object QqBotConsoleOutputFilter {
    private const val BLOCKED_PACKAGE_PREFIX = "io.github.kloping.qqbot."

    private var originalOutput: PrintStream? = null
    private var filteredOutput: PackageFilteringPrintStream? = null

    @Synchronized
    fun install() {
        if (filteredOutput != null) return

        val currentOutput = System.out
        val replacement = PackageFilteringPrintStream(
            delegate = currentOutput,
            blockedPackagePrefix = BLOCKED_PACKAGE_PREFIX
        )

        System.setOut(replacement)
        originalOutput = currentOutput
        filteredOutput = replacement
    }

    @Synchronized
    fun uninstall() {
        val replacement = filteredOutput ?: return
        val original = originalOutput

        replacement.flush()
        if (System.out === replacement && original != null) {
            System.setOut(original)
        }

        filteredOutput = null
        originalOutput = null
    }
}

private class PackageFilteringPrintStream(
    private val delegate: PrintStream,
    private val blockedPackagePrefix: String
) : PrintStream(delegate, true) {

    override fun write(value: Int) {
        if (!isBlockedCaller()) {
            delegate.write(value)
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (!isBlockedCaller()) {
            delegate.write(buffer, offset, length)
        }
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        // System.out 的原始输出流属于运行环境，只刷新但不关闭。
        flush()
    }

    override fun checkError(): Boolean = delegate.checkError()

    private fun isBlockedCaller(): Boolean = Thread.currentThread()
        .stackTrace
        .any { frame -> frame.className.startsWith(blockedPackagePrefix) }
}
