package cn.huohuas001.bot.provider

interface LoggerProvider {
    fun log_info(msg: String)
    fun log_warning(msg: String)
    fun log_error(msg: String)

    /** SDK 调试日志；平台没有单独调试级别时可默认归入普通信息日志。 */
    fun log_debug(msg: String) = log_info(msg)
}