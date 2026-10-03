package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.AdministratorAccessMode
import cn.huohuas001.bot.datapack.BindingInfo
import cn.huohuas001.bot.datapack.StoredCommandSettings
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
        if (target!!.isFile) return readIni(target!!, log)

        val legacyFile = dataDirectory.resolve("command-state.properties")
        if (!legacyFile.isFile) return StoredCommandSettings()

        val migrated = readLegacyProperties(legacyFile)
        save(migrated)
        return migrated
    }

    @Synchronized
    fun save(snapshot: StoredCommandSettings) {
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
                "user-bindings" -> {
                    // openId = playerName:qqMode:mcMode:qqUsername
                    val parts = value.split(':', limit = 4)
                    if (parts.size >= 3) {
                        bindings[key.trim()] = BindingInfo(
                            playerName = parts[0].trim(),
                            qqDisplayNameMode = parts[1].trim(),
                            mcDisplayNameMode = parts[2].trim(),
                            qqUsername = if (parts.size == 4) parts[3].trim() else ""
                        )
                    }
                }
                "bindings" -> {
                    // 旧格式：groupId = openId:playerName:qqUsername,openId:playerName:qqUsername
                    val groupBindings = legacyBindings.getOrPut(key.trim()) { mutableMapOf() }
                    value.split(',').filter { it.isNotBlank() }.forEach { entry ->
                        val parts = entry.split(':', limit = 3)
                        if (parts.size >= 2) {
                            val qqUsername = if (parts.size == 3) parts[2].trim() else ""
                            groupBindings[parts[0].trim()] = BindingInfo(parts[1].trim(), qqUsername = qqUsername)
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
                    } else if (!existing.playerName.equals(info.playerName, ignoreCase = true)) {
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

        log("已加载绑定数据：${bindings.size} 条（按 openid 全局共享，所有 QQ 群通用）")
        return StoredCommandSettings(administrators, authenticatedUsers, administratorModes, fullForwarding, bindings)
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
     * 绑定段：`openId = playerName:qqMode:mcMode:qqUsername`。
     *
     * openid 相同即同一个人，跨群共享同一条记录；qqUsername 放最后以便昵称里含冒号也不会解析错。
     */
    private fun writeUserBindingsSection(
        writer: java.io.BufferedWriter,
        bindings: Map<String, BindingInfo>
    ) {
        writer.appendLine("[user-bindings]")
        writer.appendLine("# openid = MC玩家名:QQ显示名模式:MC显示名模式:QQ昵称")
        bindings.toSortedMap().forEach { (openId, info) ->
            writer.appendLine("$openId = ${info.playerName}:${info.qqDisplayNameMode}:${info.mcDisplayNameMode}:${info.qqUsername}")
        }
        writer.appendLine()
    }
}
