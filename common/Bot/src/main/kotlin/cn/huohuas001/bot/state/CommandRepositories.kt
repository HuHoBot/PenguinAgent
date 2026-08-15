package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.StoredCommandSettings
import java.io.File

/**
 * 群命令运行状态的统一入口。
 *
 * 对外按业务含义暴露三个仓库；磁盘格式和保存时机只在这里管理，命令类不需要
 * 知道状态保存在哪个文件、使用什么格式。
 */
object CommandRepositories {
    private val stateFile = HumanReadableStateFile()

    val administrators = AdministratorRepository(::save)
    val authentication = AuthenticationRepository(::save)
    val groupSettings = GroupSettingsRepository(::save)

    /** 在插件数据目录中加载状态；没有数据目录时退化为内存存储。 */
    @Synchronized
    fun initialize(dataDirectory: File?) {
        val snapshot = stateFile.initialize(dataDirectory)
        administrators.replaceAll(snapshot.administrators)
        authentication.replaceAll(snapshot.authenticatedUsers)
        groupSettings.replaceAll(snapshot.administratorModes, snapshot.fullForwarding)
    }

    @Synchronized
    private fun save() {
        stateFile.save(
            StoredCommandSettings(
                administrators = administrators.snapshot(),
                authenticatedUsers = authentication.snapshot(),
                administratorModes = groupSettings.administratorModeSnapshot(),
                fullForwarding = groupSettings.fullForwardingSnapshot()
            )
        )
    }
}
