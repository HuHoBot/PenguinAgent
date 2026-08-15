package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.datapack.AdministratorAccessMode
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 需要群管理员权限的命令。 */
class AdministrationCommands : CommandSupport() {

    @Commands("查管理")
    fun queryAdmin(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            event.sendMessage("请指定要查询的管理员OpenId")
            return
        }

        val isAdmin = CommandRepositories.administrators.contains(groupId(event), params.trim())
        reply(plugin, event, if (isAdmin) "此人是管理员" else "此人不是管理员")
    }

    @Commands("加管理")
    fun addAdmin(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            event.sendMessage("请指定要添加的管理员OpenId")
            return
        }

        val target = params.trim()
        CommandRepositories.administrators.add(groupId(event), target)
        reply(plugin, event, "已为本群添加管理员:$target")
    }

    @Commands("删管理")
    fun removeAdmin(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            event.sendMessage("请指定要删除的管理员OpenId")
            return
        }

        val target = params.trim()
        CommandRepositories.administrators.remove(groupId(event), target)
        reply(plugin, event, "已为本群删除管理员:$target")
    }

    @Commands("管理方式")
    fun adminMode(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return

        if (params.isBlank()) {
            reply(
                plugin,
                event,
                "当前管理员判定方式：${modeName(effectiveMode(plugin, event))}\n" +
                    "可选方式：QQ / 手动 / 双重"
            )
            return
        }

        val mode = when (params.trim()) {
            "QQ" -> AdministratorAccessMode.QQ
            "手动" -> AdministratorAccessMode.MANUAL
            "双重" -> AdministratorAccessMode.BOTH
            else -> null
        }
        if (mode == null) {
            event.sendMessage("无效的判定方式。可选：QQ / 手动 / 双重")
            return
        }

        CommandRepositories.groupSettings.setAdministratorMode(groupId(event), mode)
        reply(plugin, event, "已将本群管理员判定方式设置为：${modeName(mode)}")
    }

    @Commands("添加白名单")
    fun addWhitelist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            event.sendMessage("参数不正确")
            return
        }
        val command = plugin.getWhiteList().addCommand.replace("{name}", params)
        executeGameCommand(plugin, event, command, direct = true)
    }

    @Commands("删除白名单")
    fun removeWhitelist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            event.sendMessage("参数不正确")
            return
        }
        val command = plugin.getWhiteList().delCommand.replace("{name}", params)
        executeGameCommand(plugin, event, command, direct = true)
    }

    @Commands("查白名单")
    fun queryWhitelist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val suffix = params.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        executeGameCommand(plugin, event, "whitelist list$suffix", direct = true)
    }

    @Commands("执行命令")
    fun runServerCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            event.sendMessage("参数不正确")
            return
        }
        executeGameCommand(plugin, event, params, direct = true)
    }

    @Commands("管理员执行")
    fun runAdminCustomCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            event.sendMessage("参数不正确")
            return
        }
        executeCustomCommand(plugin, event, params, admin = true)
    }

    @Commands("全量")
    fun fullAmount(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return

        val groupId = groupId(event)
        val enabled = when (params.trim().lowercase()) {
            "开", "on", "true" -> true
            "关", "off", "false" -> false
            else -> CommandRepositories.groupSettings.fullForwarding(groupId, plugin.getFullAmount())
        }
        if (params.isNotBlank()) {
            CommandRepositories.groupSettings.setFullForwarding(groupId, enabled)
        }

        reply(plugin, event, "本群全量转发：${if (enabled) "已开启" else "已关闭"}")
    }

    private fun modeName(mode: AdministratorAccessMode): String = when (mode) {
        AdministratorAccessMode.QQ -> "QQ群主/管理员判定"
        AdministratorAccessMode.MANUAL -> "手动添加管理员判定"
        AdministratorAccessMode.BOTH -> "QQ或手动管理员判定"
    }

    private fun effectiveMode(plugin: HuHoBot, event: GroupMessageEvent): AdministratorAccessMode {
        val defaultMode = AdministratorAccessMode.fromConfig(plugin.getAdminMode())
        return CommandRepositories.groupSettings.administratorMode(groupId(event), defaultMode)
    }
}
