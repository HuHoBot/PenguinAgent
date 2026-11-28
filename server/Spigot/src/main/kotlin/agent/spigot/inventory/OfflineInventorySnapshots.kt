package agent.spigot.inventory

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerJoinEvent
import agent.spigot.manager.FoliaScheduler
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read-only offline queries from plugin-owned snapshots; never modifies vanilla playerdata. */
class OfflineInventorySnapshots(private val plugin: JavaPlugin) : Listener {
    private val directory = File(plugin.dataFolder, "data/inventory/snapshots")
    private val names = ConcurrentHashMap<String, UUID>()
    private val current = ConcurrentHashMap<UUID, InventorySnapshot>()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PenguinAgent-InventorySnapshots").apply { isDaemon = true }
    }
    private var periodicTask: agent.tools.Cancelable? = null

    fun start() {
        if (!directory.exists() && !directory.mkdirs()) {
            plugin.logger.warning("无法创建离线背包快照目录: ${directory.absolutePath}")
        }
        directory.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.forEach { file ->
                try {
                    if (file.length() > MAX_FILE_BYTES) return@forEach
                    val yaml = YamlConfiguration.loadConfiguration(file)
                    if (yaml.getInt("schema-version") != SCHEMA_VERSION) return@forEach
                    val name = yaml.getString("player.name") ?: return@forEach
                    val uuid = UUID.fromString(yaml.getString("player.uuid") ?: return@forEach)
                    if (file.nameWithoutExtension.equals(uuid.toString(), true) && validName(name)) {
                        names[name.lowercase()] = uuid
                    }
                } catch (error: Exception) {
                    plugin.logger.warning("跳过无效背包快照 ${file.name}: ${error.message}")
                }
            }
        plugin.server.pluginManager.registerEvents(this, plugin)
        periodicTask =
            agent.spigot.manager.FoliaScheduler.runTimer(
                plugin, 6000L, 6000L, Runnable { captureAllOnline() }
            )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) { save(event.player) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        save(event.player)
    }

    /** Must run on the server thread. */
    fun find(playerName: String): InventorySnapshot? {
        if (!validName(playerName)) return null
        val online = plugin.server.getPlayerExact(playerName)
        if (online != null && online.isOnline) {
            if (FoliaScheduler.owns(online)) return InventorySnapshot.capture(online)
            if (!FoliaScheduler.isTickThread()) return FoliaScheduler.entityFuture(plugin, online) { InventorySnapshot.capture(online) }.get(5, TimeUnit.SECONDS)
            current[online.uniqueId]?.let { return it }
            return null
        }
        val uuid = names[playerName.lowercase()] ?: resolvePlayedUuid(playerName) ?: return null
        current[uuid]?.let { if (it.playerName.equals(playerName, true)) return it }
        val file = File(directory, "$uuid.yml")
        if (!file.isFile || file.length() > MAX_FILE_BYTES) {
            // The plugin may have been installed after the player last joined. In that case
            // the vanilla playerdata is the authoritative read-only source; never write it.
            return readVanillaSnapshot(playerName, uuid)?.also { current[uuid] = it }
        }
        return try {
            val yaml = YamlConfiguration.loadConfiguration(file)
            if (yaml.getInt("schema-version") != SCHEMA_VERSION ||
                yaml.getString("player.uuid") != uuid.toString() ||
                !yaml.getString("player.name").equals(playerName, true)
            ) return null
            InventorySnapshot(
                playerName = yaml.getString("player.name")!!,
                playerUuid = uuid,
                capturedAt = yaml.getLong("captured-at"),
                storage = readItems(yaml, "storage", 36),
                armor = readItems(yaml, "armor", 4),
                offhand = yaml.getItemStack("offhand"),
                enderChest = readItems(yaml, "ender-chest", 27),
                skinProfile = ServerSkinProfile.fromUrl(
                    yaml.getString("skin.texture-url"), yaml.getBoolean("skin.slim")
                )
            ).also { current[uuid] = it }
        } catch (error: Exception) {
            plugin.logger.warning("读取离线背包快照失败 ${file.name}: ${error.message}")
            null
        }
    }


    /** Bukkit's hasPlayedBefore prevents generated OfflinePlayer UUIDs from becoming fake inventories. */
    private fun resolvePlayedUuid(playerName: String): UUID? = runCatching {
        val offline = plugin.server.getOfflinePlayer(playerName)
        if (!offline.hasPlayedBefore()) null else offline.uniqueId
    }.getOrNull()

    /**
     * Read a vanilla playerdata file through reflection so the plugin remains compilable against
     * the 1.16 API while working with Mojang-mapped NBT on 1.18–1.21. The entire path is read-only.
     */
    private fun readVanillaSnapshot(playerName: String, uuid: UUID): InventorySnapshot? = runCatching {
        val data = plugin.server.worlds.asSequence().map { File(it.worldFolder, "playerdata/$uuid.dat") } +
            sequenceOf(File(plugin.server.worldContainer, "world/playerdata/$uuid.dat"))
        val file = data.firstOrNull { it.isFile && it.length() in 1..MAX_FILE_BYTES } ?: return@runCatching null
        val root = readNbt(file) ?: return@runCatching null
        val storage = MutableList<ItemStack?>(36) { null }
        val armor = MutableList<ItemStack?>(4) { null }
        val ender = MutableList<ItemStack?>(27) { null }
        var offhand: ItemStack? = null
        val inventory = compoundList(root, "Inventory")
        for (index in 0 until inventorySize(inventory)) {
            val item = inventoryCompound(inventory, index) ?: continue
            val slot = compoundByte(item, "Slot", -127).toInt()
            val converted = deserializeItem(item) ?: continue
            when (slot) {
                in 0..35 -> storage[slot] = converted
                100 -> armor[3] = converted // feet
                101 -> armor[2] = converted // legs
                102 -> armor[1] = converted // chest
                103 -> armor[0] = converted // head
                -106 -> offhand = converted
            }
        }
        val enderItems = compoundList(root, "EnderItems")
        for (index in 0 until inventorySize(enderItems)) {
            val item = inventoryCompound(enderItems, index) ?: continue
            val slot = compoundByte(item, "Slot", -1).toInt()
            if (slot !in 0..26) continue
            ender[slot] = deserializeItem(item)
        }
        InventorySnapshot(
            playerName = playerName,
            playerUuid = uuid,
            capturedAt = file.lastModified(),
            storage = storage,
            armor = armor,
            offhand = offhand,
            enderChest = ender,
            skinProfile = null
        )
    }.onFailure { error ->
        plugin.logger.fine("读取原版玩家数据失败（$playerName）：${error.javaClass.simpleName}")
    }.getOrNull()

    private fun readNbt(file: File): Any? {
        val nbtIo = Class.forName("net.minecraft.nbt.NbtIo")
        val accounterClass = Class.forName("net.minecraft.nbt.NbtAccounter")
        val accounter = runCatching { accounterClass.getMethod("unlimitedHeap").invoke(null) }.getOrElse {
            accounterClass.getMethod("create", Long::class.javaPrimitiveType).invoke(null, 16L * 1024L * 1024L)
        }
        val pathMethod = nbtIo.methods.firstOrNull { method ->
            method.name == "readCompressed" && method.parameterCount == 2 &&
                method.parameterTypes[0] == java.nio.file.Path::class.java
        }
        if (pathMethod != null) return pathMethod.invoke(null, file.toPath(), accounter)
        java.io.FileInputStream(file).use { input ->
            val streamMethod = nbtIo.methods.first { method ->
                method.name == "readCompressed" && method.parameterCount == 2 &&
                    java.io.InputStream::class.java.isAssignableFrom(method.parameterTypes[0])
            }
            return streamMethod.invoke(null, input, accounter)
        }
    }

    private fun compoundList(root: Any, key: String): Any? =
        root.javaClass.methods.firstOrNull { it.name == "getListOrEmpty" && it.parameterCount == 1 }
            ?.invoke(root, key)

    private fun inventorySize(list: Any?): Int {
        if (list == null) return 0
        return runCatching { list.javaClass.getMethod("size").invoke(list) as Int }.getOrDefault(0)
    }

    private fun inventoryCompound(list: Any?, index: Int): Any? =
        list?.javaClass?.methods?.firstOrNull { it.name == "getCompoundOrEmpty" && it.parameterCount == 1 }
            ?.invoke(list, index)

    private fun compoundByte(compound: Any, key: String, fallback: Byte): Byte = runCatching {
        val method = compound.javaClass.methods.firstOrNull {
            it.name == "getByteOr" && it.parameterCount == 2
        } ?: return@runCatching fallback
        method.invoke(compound, key, fallback) as Byte
    }.getOrDefault(fallback)

    private fun deserializeItem(compound: Any): ItemStack? = runCatching {
        val copy = compound.javaClass.methods.first { it.name == "copy" && it.parameterCount == 0 }.invoke(compound)
        copy.javaClass.getMethod("remove", String::class.java).invoke(copy, "Slot")
        val magic = Class.forName("org.bukkit.craftbukkit.util.CraftMagicNumbers")
        val instance = magic.getField("INSTANCE").get(null)
        val method = magic.declaredMethods.first {
            it.name == "deserializeItem" && it.parameterCount == 1 &&
                it.parameterTypes[0].name.endsWith("CompoundTag")
        }.apply { isAccessible = true }
        method.invoke(instance, copy) as? ItemStack
    }.getOrNull()

    fun close() {
        periodicTask?.cancel()
        periodicTask = null
        if (!FoliaScheduler.isFolia()) captureAllOnline()
        org.bukkit.event.HandlerList.unregisterAll(this)
        writer.shutdown()
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) {
                plugin.logger.warning("等待离线背包快照写入超时")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            plugin.logger.warning("等待离线背包快照写入被中断")
        }
    }

    private fun captureAllOnline() {
        plugin.server.onlinePlayers.forEach { player ->
            if (FoliaScheduler.owns(player)) save(player)
            else FoliaScheduler.runForEntity(plugin,player,Runnable { save(player) })
        }
    }

    private fun save(player: Player) {
        try {
            val snapshot = InventorySnapshot.capture(player)
            val yaml = YamlConfiguration()
            yaml.set("schema-version", SCHEMA_VERSION)
            yaml.set("player.name", snapshot.playerName)
            yaml.set("player.uuid", snapshot.playerUuid.toString())
            yaml.set("captured-at", snapshot.capturedAt)
            writeItems(yaml, "storage", snapshot.storage)
            writeItems(yaml, "armor", snapshot.armor)
            yaml.set("offhand", snapshot.offhand)
            writeItems(yaml, "ender-chest", snapshot.enderChest)
            yaml.set("skin.texture-url", snapshot.skinProfile?.textureUrl)
            yaml.set("skin.slim", snapshot.skinProfile?.slim)
            names[snapshot.playerName.lowercase()] = snapshot.playerUuid
            current[snapshot.playerUuid] = snapshot
            writer.execute { writeAtomically(snapshot.playerUuid, yaml) }
        } catch (error: Exception) {
            plugin.logger.warning("保存 ${player.name} 的背包快照失败: ${error.message}")
        }
    }

    private fun writeAtomically(uuid: UUID, yaml: YamlConfiguration) {
        var temporary: File? = null
        try {
            val bytes = yaml.saveToString().toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_FILE_BYTES) {
                plugin.logger.warning("背包快照过大，跳过 ${yaml.getString("player.name") ?: uuid}")
                return
            }
            if (!directory.exists()) directory.mkdirs()
            temporary = Files.createTempFile(directory.toPath(), "$uuid-", ".tmp").toFile()
            Files.write(temporary.toPath(), bytes)
            val destination = File(directory, "$uuid.yml").toPath()
            try {
                Files.move(temporary.toPath(), destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            plugin.logger.warning("写入背包快照 $uuid 失败: ${error.message}")
        } finally {
            temporary?.delete()
        }
    }

    private fun writeItems(yaml: YamlConfiguration, path: String, items: List<ItemStack?>) {
        items.forEachIndexed { index, item -> yaml.set("$path.$index", item) }
    }

    private fun readItems(yaml: YamlConfiguration, path: String, size: Int): List<ItemStack?> =
        (0 until size).map { yaml.getItemStack("$path.$it") }

    private fun validName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9_]{1,16}"))

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_FILE_BYTES = 4L * 1024L * 1024L
    }
}
