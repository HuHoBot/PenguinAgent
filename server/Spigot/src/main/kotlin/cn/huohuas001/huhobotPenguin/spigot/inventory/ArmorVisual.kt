package cn.huohuas001.huhobotPenguin.spigot.inventory

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import java.util.EnumMap
import java.util.Locale

enum class ArmorSlot { HEAD, CHEST, LEGS, FEET }

data class ArmorVisualDescriptor(
    val slot: ArmorSlot,
    val equipmentModelKey: String,
    val trimPatternKey: String?,
    val trimMaterialKey: String?,
    val leatherColor: Int?,
    val glint: Boolean
) {
    fun hasTrim(): Boolean = trimPatternKey != null && trimMaterialKey != null
    fun hasGlint(): Boolean = glint
}

class ArmorEquipmentSet private constructor(
    private val equipment: EnumMap<ArmorSlot, ArmorVisualDescriptor>
) {
    fun get(slot: ArmorSlot): ArmorVisualDescriptor? = equipment[slot]
    fun isEmpty(): Boolean = equipment.isEmpty()

    companion object {
        @JvmStatic fun empty(): ArmorEquipmentSet = ArmorEquipmentSet(EnumMap(ArmorSlot::class.java))

        @JvmStatic fun from(items: List<ItemStack?>): ArmorEquipmentSet {
            val equipment = EnumMap<ArmorSlot, ArmorVisualDescriptor>(ArmorSlot::class.java)
            val slots = arrayOf(ArmorSlot.HEAD, ArmorSlot.CHEST, ArmorSlot.LEGS, ArmorSlot.FEET)
            slots.forEachIndexed { index, slot ->
                ArmorVisualResolver.resolve(items.getOrNull(index), slot)?.let { equipment[slot] = it }
            }
            return ArmorEquipmentSet(equipment)
        }
    }
}

private object ArmorVisualResolver {
    private val supported = setOf("leather", "chainmail", "iron", "gold", "diamond", "netherite", "turtle_scute", "copper")

    fun resolve(item: ItemStack?, slot: ArmorSlot): ArmorVisualDescriptor? {
        if (item == null || item.type.isAir) return null
        val name = item.type.name.lowercase(Locale.ROOT)
        val expected = when (slot) {
            ArmorSlot.HEAD -> name.endsWith("_helmet")
            ArmorSlot.CHEST -> name.endsWith("_chestplate")
            ArmorSlot.LEGS -> name.endsWith("_leggings")
            ArmorSlot.FEET -> name.endsWith("_boots")
        }
        if (!expected) return null
        val family = when {
            name == "turtle_helmet" -> "turtle_scute"
            name.startsWith("golden_") -> "gold"
            else -> name.substringBefore('_')
        }
        val meta = item.itemMeta ?: return null
        val model = equippableModel(meta)?.substringAfter(':') ?: family
        if (model !in supported) return null
        val trim = trimKeys(meta)
        val glintOverride = runCatching {
            meta.javaClass.getMethod("getEnchantmentGlintOverride").invoke(meta) as? Boolean
        }.getOrNull()
        return ArmorVisualDescriptor(
            slot = slot,
            equipmentModelKey = "minecraft:$model",
            trimPatternKey = trim?.first,
            trimMaterialKey = trim?.second,
            leatherColor = (meta as? LeatherArmorMeta)?.color?.asRGB(),
            glint = glintOverride ?: item.enchantments.isNotEmpty()
        )
    }

    private fun equippableModel(meta: Any): String? = runCatching {
        val component = meta.javaClass.getMethod("getEquippable").invoke(meta) ?: return@runCatching null
        component.javaClass.getMethod("getModel").invoke(component)?.toString()
            ?.lowercase(Locale.ROOT)
    }.getOrNull()

    private fun trimKeys(meta: Any): Pair<String, String>? = runCatching {
        val trim = meta.javaClass.getMethod("getTrim").invoke(meta) ?: return@runCatching null
        val pattern = trim.javaClass.getMethod("getPattern").invoke(trim)
        val material = trim.javaClass.getMethod("getMaterial").invoke(trim)
        val patternKey = pattern.javaClass.getMethod("getKey").invoke(pattern).toString()
        val materialKey = material.javaClass.getMethod("getKey").invoke(material).toString()
        patternKey to materialKey
    }.getOrNull()
}
