package cn.huohuas001.huhobotPenguin.spigot.inventory

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
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
    private val directory = File(plugin.dataFolder, "inventory/snapshots")
    private val names = ConcurrentHashMap<String, UUID>()
    private val current = ConcurrentHashMap<UUID, InventorySnapshot>()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PenguinAgent-InventorySnapshots").apply { isDaemon = true }
    }
    private var periodicTask: BukkitTask? = null

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
        periodicTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { captureAllOnline() }, 6000L, 6000L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        save(event.player)
    }

    /** Must run on the server thread. */
    fun find(playerName: String): InventorySnapshot? {
        if (!validName(playerName)) return null
        val online = plugin.server.getPlayerExact(playerName)
        if (online != null && online.isOnline) return InventorySnapshot.capture(online)
        val uuid = names[playerName.lowercase()] ?: return null
        current[uuid]?.let { if (it.playerName.equals(playerName, true)) return it }
        val file = File(directory, "$uuid.yml")
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
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

    fun close() {
        periodicTask?.cancel()
        periodicTask = null
        captureAllOnline()
        writer.shutdown()
        try {
            if (!writer.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.logger.warning("等待离线背包快照写入超时")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            plugin.logger.warning("等待离线背包快照写入被中断")
        }
    }

    private fun captureAllOnline() {
        plugin.server.onlinePlayers.forEach(::save)
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
            val bytes = yaml.saveToString().toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_FILE_BYTES) {
                plugin.logger.warning("背包快照过大，跳过 ${snapshot.playerName}")
                return
            }
            names[snapshot.playerName.lowercase()] = snapshot.playerUuid
            current[snapshot.playerUuid] = snapshot
            writer.execute { writeAtomically(snapshot.playerUuid, bytes) }
        } catch (error: Exception) {
            plugin.logger.warning("保存 ${player.name} 的背包快照失败: ${error.message}")
        }
    }

    private fun writeAtomically(uuid: UUID, bytes: ByteArray) {
        var temporary: File? = null
        try {
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
