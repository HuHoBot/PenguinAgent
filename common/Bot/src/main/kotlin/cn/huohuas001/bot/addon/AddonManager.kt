package cn.huohuas001.bot.addon

import cn.huohuas001.bot.events.commands.RegisteredCommand

/**
 * HuHoBot 扩展注册中心。
 *
 * 第三方插件通过 [register] 注册扩展及其命令，运行时可通过
 * [allAddons] 查询已安装扩展列表。
 */
object AddonManager {
    private val addons = mutableMapOf<String, Addon>()
    private val addonCommands = mutableMapOf<String, MutableList<RegisteredCommand>>()

    /** 已注册的扩展数量。 */
    val size: Int get() = addons.size

    /**
     * 注册一个扩展。
     *
     * @param addon    扩展元数据
     * @param commands 该扩展提供的命令列表
     */
    fun register(addon: Addon, commands: List<RegisteredCommand> = emptyList()) {
        addons[addon.name] = addon
        if (commands.isNotEmpty()) {
            addonCommands.getOrPut(addon.name) { mutableListOf() }.addAll(commands)
        }
    }

    /** 获取所有已注册扩展（按名称排序）。 */
    fun allAddons(): List<Addon> = addons.values.sortedBy { it.name }

    /** 获取指定扩展的命令列表。 */
    fun commandsOf(addonName: String): List<RegisteredCommand> =
        addonCommands[addonName]?.toList().orEmpty()

    /** 向已注册扩展追加命令（若扩展未注册则忽略；按 command 去重）。 */
    fun addCommand(addonName: String, command: RegisteredCommand) {
        if (addonName !in addons) return
        val list = addonCommands.getOrPut(addonName) { mutableListOf() }
        if (list.none { it.command == command.command }) {
            list.add(command)
        }
    }

    /** 检查扩展是否已注册（支持 `name in AddonManager` 语法）。 */
    operator fun contains(name: String): Boolean = name in addons
}
