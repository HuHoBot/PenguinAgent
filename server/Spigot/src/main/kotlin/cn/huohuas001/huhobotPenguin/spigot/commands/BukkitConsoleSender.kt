package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.conversations.Conversation
import org.bukkit.conversations.ConversationAbandonedEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.permissions.PermissionAttachmentInfo
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class BukkitConsoleSender(private val plugin: HuHoBotSpigot) : ConsoleCommandSender, HExecution {
    private val messages = CopyOnWriteArrayList<String>()

    private val spigotSender = object : CommandSender.Spigot() {
        override fun sendMessage(component: BaseComponent) {
            messages.add(component.toLegacyText())
        }

        override fun sendMessage(vararg components: BaseComponent) {
            components.forEach { component ->
                messages.add(component.toLegacyText())
            }
        }
    }

    fun clearMessages() {
        messages.clear()
    }

    fun getAndClearMessages(): List<String> {
        val result = messages.toList()
        messages.clear()
        return result
    }

    override fun isOp(): Boolean = true

    override fun setOp(value: Boolean) = Unit

    override fun isPermissionSet(name: String): Boolean = false

    override fun isPermissionSet(permission: Permission): Boolean = false

    override fun hasPermission(name: String): Boolean = true

    override fun hasPermission(permission: Permission): Boolean = true

    override fun addAttachment(
        plugin: Plugin,
        name: String,
        value: Boolean
    ): PermissionAttachment = throw UnsupportedOperationException()

    override fun addAttachment(plugin: Plugin): PermissionAttachment =
        throw UnsupportedOperationException()

    override fun addAttachment(
        plugin: Plugin,
        name: String,
        value: Boolean,
        ticks: Int
    ): PermissionAttachment? = throw UnsupportedOperationException()

    override fun addAttachment(plugin: Plugin, ticks: Int): PermissionAttachment? =
        throw UnsupportedOperationException()

    override fun removeAttachment(attachment: PermissionAttachment) = Unit

    override fun recalculatePermissions() = Unit

    override fun getEffectivePermissions(): MutableSet<PermissionAttachmentInfo> = mutableSetOf()

    override fun sendMessage(message: String) {
        messages.add(message)
    }

    override fun sendMessage(vararg messages: String?) {
        messages.filterNotNull().forEach(this.messages::add)
    }

    override fun sendMessage(sender: UUID?, message: String) {
        messages.add(message)
    }

    override fun sendMessage(sender: UUID?, vararg messages: String?) {
        messages.filterNotNull().forEach(this.messages::add)
    }

    override fun getServer(): Server = Bukkit.getServer()

    override fun getName(): String = "CONSOLE"

    override fun spigot(): CommandSender.Spigot = spigotSender

    override fun isConversing(): Boolean = false

    override fun acceptConversationInput(input: String) = Unit

    override fun beginConversation(conversation: Conversation): Boolean = false

    override fun abandonConversation(conversation: Conversation) = Unit

    override fun abandonConversation(
        conversation: Conversation,
        details: ConversationAbandonedEvent
    ) = Unit

    override fun sendRawMessage(message: String) {
        messages.add(message)
    }

    override fun sendRawMessage(sender: UUID?, message: String) {
        messages.add(message)
    }

    override fun getRawString(): String = messages.joinToString("\n")

    override fun execute(command: String): CompletableFuture<HExecution> {
        val result = CompletableFuture<HExecution>()
        clearMessages()

        plugin.submit {
            try {
                Bukkit.dispatchCommand(this, command)
                completeAfterCommandOutput(result)
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }

        return result
    }

    private fun completeAfterCommandOutput(result: CompletableFuture<HExecution>) {
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable { result.complete(this) },
            COMMAND_OUTPUT_DELAY_TICKS
        )
    }

    private companion object {
        const val COMMAND_OUTPUT_DELAY_TICKS = 40L
    }
}
