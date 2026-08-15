package cn.huohuas001.huhobotPenguin.nukkit.tools

import cn.huohuas001.huhobotPenguin.nukkit.HuHoBotNukkit
import cn.nukkit.Server
import cn.nukkit.command.CommandSender
import cn.nukkit.command.ConsoleCommandSender
import cn.nukkit.lang.CommandOutputContainer
import cn.nukkit.lang.TextContainer
import cn.nukkit.lang.TranslationContainer
import cn.nukkit.level.GameRule
import cn.nukkit.permission.Permission
import cn.nukkit.permission.PermissionAttachment
import cn.nukkit.permission.PermissionAttachmentInfo
import cn.nukkit.plugin.Plugin

class NukkitConsoleSender(private val console: ConsoleCommandSender, private val plugin: HuHoBotNukkit) : CommandSender {
    val output = StringBuilder()
    override fun sendMessage(message: String) { message.lines().filter(String::isNotEmpty).forEach { output.append(it).append('\n'); plugin.log_info(it) } }
    override fun sendMessage(message: TextContainer) = sendMessage(console.server.language.translate(message))
    override fun sendCommandOutput(container: CommandOutputContainer) {
        container.messages.forEach { sendMessage(console.server.language.translate(TranslationContainer(it.messageId, *it.parameters))) }
    }
    override fun getServer(): Server = console.server
    override fun getName(): String = "CONSOLE"
    override fun isPlayer(): Boolean = false
    override fun isPermissionSet(s: String): Boolean = console.isPermissionSet(s)
    override fun isPermissionSet(permission: Permission): Boolean = console.isPermissionSet(permission)
    override fun hasPermission(s: String): Boolean = console.hasPermission(s)
    override fun hasPermission(permission: Permission): Boolean = console.hasPermission(permission)
    override fun addAttachment(plugin: Plugin): PermissionAttachment = console.addAttachment(plugin)
    override fun addAttachment(plugin: Plugin, name: String): PermissionAttachment = console.addAttachment(plugin, name)
    override fun addAttachment(plugin: Plugin, name: String, value: Boolean?): PermissionAttachment = console.addAttachment(plugin, name, value)
    override fun removeAttachment(attachment: PermissionAttachment) = console.removeAttachment(attachment)
    override fun recalculatePermissions() = console.recalculatePermissions()
    override fun getEffectivePermissions(): Map<String, PermissionAttachmentInfo> = console.effectivePermissions
    override fun isOp(): Boolean = true
    override fun setOp(value: Boolean) = Unit
}
