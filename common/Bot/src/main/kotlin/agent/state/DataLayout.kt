package agent.state

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 插件运行数据布局：配置文件仍在插件根目录，运行状态、缓存和可恢复数据统一放在 data/。
 * 旧版本根目录文件只在目标不存在时迁移，迁移失败则保留原文件并继续运行。
 */
object DataLayout {
    private val movable = listOf(
        "command-state.ini", "command-state.properties", "permission-groups.yml", "timed-tasks.yml",
        "nicknames.dat", "installed-addons.json", "group-names.dat", "webui-password",
        "server-notify-state.properties", "online-history.json", "release-state.json", "tts-cache", "tts-voices",
        "sensitive-words", "groups", "inventory", "fonts", "modules", "engines", "logs", "backups", "Markdown", "translations", "storage.db", "storage.db-wal", "storage.db-shm", "Q&A.txt", "Q&A.txt.new",
        "bindings.json", "bindings.yml", "player-bindings.json", "pending-bindings.json", "bind-codes.json",
        "bot-state.json", "group-dirs.json", "whitelist-commands.yml", "addon-registry.json",
        "avatar-cache", "skin-cache", "chat-log.dat", "chatlog", "panel-state.json", "menu-state.json"
    )
    private val addonData = listOf("config", "data", "files")

    @JvmStatic
    fun directory(configDirectory: File?): File? = configDirectory?.resolve("data")

    @JvmStatic
    fun migrate(configDirectory: File?, log: (String) -> Unit = {}) {
        val root = configDirectory ?: return
        val data = directory(root) ?: return
        if (!data.exists()) data.mkdirs()
        val databaseConflict = root.resolve("storage.db").exists() && data.resolve("storage.db").exists()
        val moves = movable.map { it to it } + addonData.map { "addons/$it" to "addons/$it" }
        for ((oldName, targetName) in moves) {
            if (databaseConflict && oldName in listOf("storage.db-wal", "storage.db-shm")) continue
            val old = root.resolve(oldName)
            val target = data.resolve(targetName)
            if (!old.exists()) continue
            if (!target.exists()) {
                try {
                    target.parentFile?.mkdirs()
                    Files.move(old.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    log("已将旧数据迁移到 data/$targetName")
                    continue
                } catch (_: Exception) {
                    // Filesystems without atomic directory renames fall through to safe per-file migration.
                }
            }
            if (old.isDirectory && target.isDirectory) {
                mergeDirectoryWithoutOverwrite(old, target, log)
            } else if (target.exists()) {
                // Never overwrite a newer file in data/. Keep the legacy copy for manual recovery.
                log("data/$targetName 已存在；旧文件 $oldName 保留为备份，未覆盖任何一方")
            } else {
                try {
                    target.parentFile?.mkdirs()
                    Files.move(old.toPath(), target.toPath())
                    log("已将旧数据迁移到 data/$targetName")
                } catch (error: Exception) {
                    log("数据迁移失败，保留原路径 $oldName：${error.message}")
                }
            }
        }
        migrateLooseAddons(root, log)
    }

    /**
     * 极老版本（1.14~1.20 时代）的扩展是 addons/ 根上直接放 main.js / xxx.lua 单文件；
     * 现在只认目录插件。这里自动把它们逐个搬进同名目录并生成 metadata.yaml，
     * 老扩展零改动即可被 1.24 加载。
     */
    private fun migrateLooseAddons(root: File, log: (String) -> Unit) {
        val addons = root.resolve("addons")
        if (!addons.isDirectory) return
        for (file in addons.listFiles().orEmpty()) {
            if (file.isDirectory) continue
            val name = file.name
            if (name.endsWith(".metadata.yaml") || name == "metadata.yaml") continue
            val ext = listOf(".js", ".lua", ".py", ".java").firstOrNull { name.lowercase().endsWith(it) } ?: continue
            val base = name.removeSuffix(ext).ifBlank { return }
            val dir = addons.resolve(base)
            if (dir.exists()) { log("扩展 $name 无法自动目录化：addons/$base/ 已存在，请手动整理"); continue }
            runCatching {
                dir.mkdirs()
                Files.move(file.toPath(), dir.resolve("main$ext").toPath(), StandardCopyOption.ATOMIC_MOVE)
                val metadata = dir.resolve("metadata.yaml")
                if (!metadata.exists()) {
                    val safe = base.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    metadata.writeText("name: $safe\nversion: '1.0.0'\nauthor: 'migrated'\ndescription: '由旧版单文件扩展自动迁移'\nentry: main$ext\n")
                }
                log("旧版单文件扩展 $name 已自动迁移为目录插件 addons/$base/")
            }.onFailure { log("迁移扩展 $name 失败，原文件保留：${it.message}") }
        }
    }

    private fun mergeDirectoryWithoutOverwrite(old: File, target: File, log: (String) -> Unit) {
        val stack = ArrayDeque<File>()
        stack.add(old)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles().orEmpty()
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) { log("保留旧数据符号链接，未递归迁移：${child.path}"); continue }
                val relative = old.toPath().relativize(child.toPath())
                val destination = target.toPath().resolve(relative)
                if (child.isDirectory) {
                    runCatching { Files.createDirectories(destination); stack.add(child) }
                        .onFailure { log("目录冲突，保留旧数据：${child.path}") }
                } else if (!Files.exists(destination)) {
                    runCatching {
                        Files.createDirectories(destination.parent)
                        Files.move(child.toPath(), destination, StandardCopyOption.ATOMIC_MOVE)
                    }.onFailure { log("迁移数据文件失败，旧文件保留：${child.path} (${it.message})") }
                } else {
                    log("发现同名数据，未覆盖：${destination}")
                }
            }
        }
    }

}
