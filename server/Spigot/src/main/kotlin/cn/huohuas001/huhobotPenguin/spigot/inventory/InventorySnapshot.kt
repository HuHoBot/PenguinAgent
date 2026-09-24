package cn.huohuas001.huhobotPenguin.spigot.inventory

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Detached inventory data; Bukkit player state is copied only on the server thread. */
data class InventorySnapshot(
    val playerName: String,
    val playerUuid: UUID,
    val capturedAt: Long,
    val storage: List<ItemStack?>,
    val armor: List<ItemStack?>,
    val offhand: ItemStack?,
    val enderChest: List<ItemStack?>,
    val skinProfile: ServerSkinProfile? = null
) {
    val armorVisuals: ArmorEquipmentSet = ArmorEquipmentSet.from(armor)

    companion object {
        fun capture(player: Player): InventorySnapshot {
            val inventory = player.inventory
            return InventorySnapshot(
                playerName = player.name,
                playerUuid = player.uniqueId,
                capturedAt = System.currentTimeMillis(),
                storage = inventory.storageContents.take(36).map { it?.clone() },
                armor = listOf(inventory.helmet, inventory.chestplate, inventory.leggings, inventory.boots)
                    .map { it?.clone() },
                offhand = inventory.itemInOffHand.clone(),
                enderChest = player.enderChest.contents.take(27).map { it?.clone() },
                skinProfile = ServerSkinProfile.capture(player)
            )
        }
    }
}
