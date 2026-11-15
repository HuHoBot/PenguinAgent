package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.AdministratorAccessMode
import cn.huohuas001.bot.datapack.BindingInfo
import cn.huohuas001.bot.datapack.StoredCommandSettings
import cn.huohuas001.bot.storage.SqliteStateStore
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties



/**
 * 读写 command-state.ini。
 *
 * 文件按职责分段并稳定排序，方便服主直接检查和手动维护。首次启动时会兼容导入
 * 旧版 command-state.properties，但不会删除旧文件。
 */
internal class HumanReadableStateFile {
    private var target: File? = null

    fun initialize(dataDirectory: File?, log: (String) -> Unit = {}): StoredCommandSettings {
        if (dataDirectory == null) {
            target = null
            return StoredCommandSettings()
        }

        target = dataDirectory.resolve("command-state.ini")

        // 可选 SQLite 后端：库里有数据以库为准；首次切换时把文件数据迁入库。
        // INI 仍然照常落盘（人工可读 + 库损坏时的兜底），任何 SQLite 失败都回退纯文件模式。
        val sqliteEnabled = runCatching {
            cn.huohuas001.bot.provider.BotShared.getPluginOrNull()
                ?.getStorageType()?.equals("sqlite", ignoreCase = true) == true
        }.getOrDefault(false)
        if (sqliteEnabled && SqliteStateStore.open(dataDirectory)) {
            val fromDb = loadFromSqlite()
            val snapshot = fromDb ?: (if (target!!.isFile) readIni(target!!, log) else StoredCommandSettings())
            writeSqlite(snapshot)
            save(snapshot)
            if (fromDb == null) log("SQLite 存储已启用，文件数据已迁移入库")
            return snapshot
        }

        if (target!!.isFile) return readIni(target!!, log)

        val legacyFile = dataDirectory.resolve("command-state.properties")
        if (!legacyFile.isFile) return StoredCommandSettings()

        val migrated = readLegacyProperties(legacyFile)
        save(migrated)
        return migrated
    }

    /** 从 SQLite 装载状态；库为空时返回 null（走文件迁移）。 */
    private fun loadFromSqlite(): StoredCommandSettings? {
        if (!SqliteStateStore.isAvailable()) return null
        val bindings = SqliteStateStore.readBindings()
        if (bindings.isEmpty()) return null
        return StoredCommandSettings(
            administrators = readSectionAsUsers("administrators"),
            authenticatedUsers = readSectionAsUsers("authenticated-users"),
            administratorModes = readSectionAsModes("administrator-modes"),
            fullForwarding = readSectionAsBooleans("full-forwarding"),
            bindings = bindings.mapValues { (_, row) ->
                cn.huohuas001.bot.datapack.BindingInfo(
                    playerNames = row.playerNames,
                    qqDisplayNameMode = row.qqMode,
                    mcDisplayNameMode = row.mcMode,
                    qqUsername = row.qqUsername,
                    lockedPlayerNames = row.lockedNames
                )
            },
            memberRoles = SqliteStateStore.readSection("member-roles")
        )
    }

    private fun writeSqlite(snapshot: StoredCommandSettings) {
        SqliteStateStore.writeSection("administrators", snapshot.administrators.mapValues { it.value.joinToString(",") })
        SqliteStateStore.writeSection("authenticated-users", snapshot.authenticatedUsers.mapValues { it.value.joinToString(",") })
        SqliteStateStore.writeSection("administrator-modes", snapshot.administratorModes.mapValues { it.value.name })
        SqliteStateStore.writeSection("full-forwarding", snapshot.fullForwarding.mapValues { it.value.toString() })
        SqliteStateStore.writeSection("member-roles", snapshot.memberRoles)
        SqliteStateStore.writeBindings(
            snapshot.bindings.mapValues { (_, info) ->
                SqliteStateStore.BindingRow(
                    info.playerNames, info.qqDisplayNameMode, info.mcDisplayNameMode,
                    info.qqUsername, info.lockedPlayerNames
                )
            }
        )
    }

    private fun readSectionAsUsers(section: String): LinkedHashMap<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        for ((key, value) in SqliteStateStore.readSection(section)) {
            out[key] = value.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        }
        return out
    }

    private fun readSectionAsModes(section: String): LinkedHashMap<String, AdministratorAccessMode> {
        val out = LinkedHashMap<String, AdministratorAccessMode>()
        for ((key, value) in SqliteStateStore.readSection(section)) {
            AdministratorAccessMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?.let { out[key] = it }
        }
        return out
    }

    private fun readSectionAsBooleans(section: String): LinkedHashMap<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        for ((key, value) in SqliteStateStore.readSection(section)) {
            value.toBooleanStrictOrNull()?.let { out[key] = it }
        }
        return out
    }

    @Synchronized
    fun save(snapshot: StoredCommandSettings) {
        // SQLite 后端可用时同步写库；文件照常落盘作为人工可读兜底。
        runCatching { writeSqlite(snapshot) }
        val outputFile = target ?: return
        outputFile.parentFile?.mkdirs()
        val temporaryFile = File(outputFile.absolutePath + ".tmp")

        temporaryFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("# HuHoBot 群命令运行状态")
            writer.appendLine("# 建议仅在服务器停止时手动编辑此文件。")
            writer.appendLine()
            writeUserSection(writer, "administrators", snapshot.administrators)
            writeUserSection(writer, "authenticated-users", snapshot.authenticatedUsers)
            writeValueSection(writer, "administrator-modes", snapshot.administratorModes.mapValues { it.value.name })
            writeValueSection(writer, "full-forwarding", snapshot.fullForwarding.mapValues { it.value.toString() })
            writeUserBindingsSection(writer, snapshot.bindings)
            writeMemberRolesSection(writer, snapshot.memberRoles)
        }

        try {
            Files.move(
                temporaryFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(temporaryFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readIni(file: File, log: (String) -> Unit): StoredCommandSettings {
        val administrators = linkedMapOf<String, Set<String>>()
        val authenticatedUsers = linkedMapOf<String, Set<String>>()
        val administratorModes = linkedMapOf<String, AdministratorAccessMode>()
        val fullForwarding = linkedMapOf<String, Boolean>()
        val bindings = linkedMapOf<String, BindingInfo>()
        val memberRoles = linkedMapOf<String, String>()
        // openid → 已锁定的角色名，解析完绑定后再合并进 BindingInfo
        val lockedPlayers = linkedMapOf<String, List<String>>()
        // 旧格式：按群分组的绑定与显示名称设置，仅用于迁移
        val legacyBindings = linkedMapOf<String, MutableMap<String, BindingInfo>>()
        val legacySettings = linkedMapOf<String, MutableMap<String, Pair<String, String>>>()
        var section = ""

        file.readLines(Charsets.UTF_8).forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                return@forEach
            }

            val (key, rawValue) = line.split('=', limit = 2).takeIf { it.size == 2 }
                ?: return@forEach
            val value = rawValue.trim()
            when (section) {
                "administrators" -> administrators[key.trim()] = parseUsers(value)
                "authenticated-users" -> authenticatedUsers[key.trim()] = parseUsers(value)
                "administrator-modes" -> AdministratorAccessMode.entries
                    .firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?.let { administratorModes[key.trim()] = it }
                "full-forwarding" -> value.toBooleanStrictOrNull()
                    ?.let { fullForwarding[key.trim()] = it }
                "member-roles" -> memberRoles[key.trim()] = value
                "user-bindings" -> {
                    // openId = playerName[,playerName...]:qqMode:mcMode:qqUsername
                    val parts = value.split(':', limit = 4)
                    if (parts.size >= 3) {
                        val playerNames = parts[0].split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        if (playerNames.isNotEmpty()) {
                            bindings[key.trim()] = BindingInfo(
                                playerNames = playerNames,
                                qqDisplayNameMode = parts[1].trim(),
                                mcDisplayNameMode = parts[2].trim(),
                                qqUsername = if (parts.size == 4) parts[3].trim() else ""
                            )
                        }
                    }
                }
                "user-binding-locks" -> {
                    // openId = playerName[,playerName...]
                    val playerNames = value.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (playerNames.isNotEmpty()) lockedPlayers[key.trim()] = playerNames
                }
                "bindings" -> {
                    // 旧格式：groupId = openId:playerName:qqUsername,openId:playerName:qqUsername
                    val groupBindings = legacyBindings.getOrPut(key.trim()) { mutableMapOf() }
                    value.split(',').filter { it.isNotBlank() }.forEach { entry ->
                        val parts = entry.split(':', limit = 3)
                        if (parts.size >= 2) {
                            val qqUsername = if (parts.size == 3) parts[2].trim() else ""
                            groupBindings[parts[0].trim()] = BindingInfo(
                                playerNames = listOf(parts[1].trim()),
                                qqUsername = qqUsername
                            )
                        }
                    }
                }
                "binding-settings" -> {
                    // 旧格式：groupId = openId:qqMode:mcMode,openId:qqMode:mcMode
                    val groupSettings = legacySettings.getOrPut(key.trim()) { mutableMapOf() }
                    value.split(',').filter { it.isNotBlank() }.forEach { entry ->
                        val parts = entry.split(':', limit = 3)
                        if (parts.size == 3) {
                            groupSettings[parts[0].trim()] = parts[1].trim() to parts[2].trim()
                        }
                    }
                }
            }
        }

        // 合并旧格式的显示名称设置
        legacySettings.values.forEach { settings ->
            settings.forEach { (openId, modes) ->
                val info = legacyBindings.values.firstNotNullOfOrNull { it[openId] } ?: return@forEach
                val updated = info.copy(
                    qqDisplayNameMode = modes.first,
                    mcDisplayNameMode = modes.second
                )
                legacyBindings.values.forEach { group ->
                    if (group[openId] != null) group[openId] = updated
                }
            }
        }

        // 迁移旧格式：同一 openid 在多个群出现时以先出现的为准，不丢数据
        if (legacyBindings.isNotEmpty()) {
            var migrated = 0
            var conflicted = 0
            legacyBindings.values.forEach { group ->
                group.forEach { (openId, info) ->
                    val existing = bindings[openId]
                    if (existing == null) {
                        bindings[openId] = info
                        migrated++
                    } else if (!existing.hasPlayer(info.playerName)) {
                        conflicted++
                    }
                }
            }
            if (migrated > 0 || conflicted > 0) {
                log(
                    "绑定数据已迁移为按 openid 全局共享：导入 $migrated 条" +
                        (if (conflicted > 0) "，$conflicted 条因同一 openid 在不同群绑定了不同角色而保留首次记录" else "")
                )
            }
        }

        // 锁定状态只保留仍属于该 openid 的角色：解绑后遗留的锁不应继续拦住同名新绑定
        lockedPlayers.forEach { (openId, lockedNames) ->
            val info = bindings[openId] ?: return@forEach
            val valid = info.playerNames.filter { name ->
                lockedNames.any { it.equals(name, ignoreCase = true) }
            }
            if (valid.isNotEmpty()) bindings[openId] = info.copy(lockedPlayerNames = valid)
        }

        val accountCount = bindings.values.sumOf { it.playerNames.size }
        log("已加载绑定数据：${bindings.size} 个 QQ、$accountCount 个角色（按 openid 全局共享，所有 QQ 群通用）")
        return StoredCommandSettings(administrators, authenticatedUsers, administratorModes, fullForwarding, bindings, memberRoles)
    }

    private fun readLegacyProperties(file: File): StoredCommandSettings {
        val properties = Properties()
        FileInputStream(file).use(properties::load)
        val administrators = linkedMapOf<String, Set<String>>()
        val authenticatedUsers = linkedMapOf<String, Set<String>>()
        val administratorModes = linkedMapOf<String, AdministratorAccessMode>()
        val fullForwarding = linkedMapOf<String, Boolean>()

        properties.stringPropertyNames().forEach { key ->
            val value = properties.getProperty(key).orEmpty()
            when {
                key.startsWith("admins.") -> administrators[key.removePrefix("admins.")] = parseUsers(value)
                key.startsWith("auth.") -> authenticatedUsers[key.removePrefix("auth.")] = parseUsers(value)
                key.startsWith("mode.") -> legacyMode(value)?.let {
                    administratorModes[key.removePrefix("mode.")] = it
                }
                key.startsWith("full.") -> fullForwarding[key.removePrefix("full.")] = value.toBoolean()
            }
        }
        return StoredCommandSettings(administrators, authenticatedUsers, administratorModes, fullForwarding)
    }

    private fun legacyMode(value: String): AdministratorAccessMode? = when (value) {
        "onlyQQ" -> AdministratorAccessMode.QQ
        "onlyAdd" -> AdministratorAccessMode.MANUAL
        "both" -> AdministratorAccessMode.BOTH
        else -> null
    }

    private fun parseUsers(value: String): Set<String> =
        value.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    private fun writeUserSection(
        writer: java.io.BufferedWriter,
        name: String,
        values: Map<String, Set<String>>
    ) = writeValueSection(writer, name, values.mapValues { (_, users) -> users.sorted().joinToString(", ") })

    private fun writeValueSection(
        writer: java.io.BufferedWriter,
        name: String,
        values: Map<String, String>
    ) {
        writer.appendLine("[$name]")
        values.toSortedMap().forEach { (groupId, value) -> writer.appendLine("$groupId = $value") }
        writer.appendLine()
    }

    /**
     * 绑定段：`openId = playerName[,playerName...]:qqMode:mcMode:qqUsername`。
     *
     * openid 相同即同一个人，跨群共享同一条记录；多个角色用半角逗号分隔，
     * qqUsername 放最后以便昵称里含冒号也不会解析错。
     * 锁定状态单独写在 `[user-binding-locks]`，避免在同一个值里再挤一个字段。
     */
    private fun writeUserBindingsSection(
        writer: java.io.BufferedWriter,
        bindings: Map<String, BindingInfo>
    ) {
        writer.appendLine("[user-bindings]")
        writer.appendLine("# openid = MC玩家名(多个用半角逗号分隔):QQ显示名模式:MC显示名模式:QQ昵称")
        bindings.toSortedMap().forEach { (openId, info) ->
            writer.appendLine(
                "$openId = ${info.playerNames.joinToString(",")}:" +
                    "${info.qqDisplayNameMode}:${info.mcDisplayNameMode}:${info.qqUsername}"
            )
        }
        writer.appendLine()

        writer.appendLine("[user-binding-locks]")
        writer.appendLine("# openid = 已锁定的MC玩家名(多个用半角逗号分隔)，自动维护")
        bindings.toSortedMap().forEach { (openId, info) ->
            if (info.lockedPlayerNames.isNotEmpty()) {
                writer.appendLine("$openId = ${info.lockedPlayerNames.joinToString(",")}")
            }
        }
        writer.appendLine()
    }

    /**
     * 被动角色缓存段：`groupOpenId:memberOpenId = member_role`。
     *
     * 由群消息事件自动写入并实时刷新，无需手动维护。
     */
    private fun writeMemberRolesSection(
        writer: java.io.BufferedWriter,
        memberRoles: Map<String, String>
    ) {
        writer.appendLine("[member-roles]")
        writer.appendLine("# groupOpenId:memberOpenId = member_role(owner/admin/member)，自动维护")
        memberRoles.toSortedMap().forEach { (key, role) -> writer.appendLine("$key = $role") }
        writer.appendLine()
    }
}
