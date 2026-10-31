package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.provider.ConfigProvider

/**
 * 命令黑名单匹配。
 *
 * 服务器收到命令后自己会做一轮规范化（去掉斜杠、解析命名空间前缀），黑名单必须按同样的规则比较，
 * 否则随手加个前缀就能绕过：黑名单里有 `plugins`，`/执行命令 bukkit:plugins` 照样能跑。
 *
 * 匹配前统一做这些处理：
 * 1. 去掉首尾空白与开头的 `/`（含 `//`），只取第一个词，忽略后面的参数
 * 2. 去掉命名空间前缀（`bukkit:plugins` / `minecraft:op` / `插件名:命令`）
 * 3. 统一小写
 *
 * 另外还会按服务器解析出的名称再比对一次，覆盖 `/pl` 这类原版别名；平台层拿不到命令表时自动跳过。
 * 命令本身是 `/execute ... run <命令>` 这种包装形式时，把 `run` 后面的真实命令也一并检查。
 */
object CommandBlacklistGuard {

    /** 命令嵌套检查的最大深度，避免 `execute` 无限套娃把栈打穿。 */
    private const val MAX_DEPTH = 4

    /**
     * 把一条命令规范化成命令名：去斜杠、去命名空间、取第一个词、转小写。
     *
     * @return 规范化后的命令名；命令为空时返回空串
     */
    fun normalize(command: String): String {
        val token = command.trim()
            .split(Regex("\\s+"), limit = 2)
            .firstOrNull()
            .orEmpty()
            .trimStart('/')
            .lowercase()
        if (token.isEmpty()) return ""
        // 命名空间前缀：bukkit:plugins / minecraft:give / 插件名:命令
        return token.substringAfterLast(':').ifEmpty { token }
    }

    /** 命令是否命中黑名单。 */
    fun isBlocked(plugin: ConfigProvider, command: String): Boolean {
        val blacklist = plugin.getCommandBlacklist()
        if (blacklist.isEmpty()) return false
        val entries = blacklist.mapNotNull { blocked -> normalize(blocked).takeIf { it.isNotEmpty() } }
        if (entries.isEmpty()) return false
        val candidates = collectCandidates(plugin, command, 0)
        return entries.any { it in candidates }
    }

    /**
     * 收集一条命令里所有可能的命令名。
     *
     * 除了命令本身，还会把服务器解析出的规范名（别名 → 主名）和 `execute ... run` 后面的真实命令加进来。
     */
    private fun collectCandidates(plugin: ConfigProvider, command: String, depth: Int): Set<String> {
        val candidates = linkedSetOf<String>()
        val name = normalize(command)
        if (name.isEmpty()) return candidates
        candidates.add(name)
        plugin.resolveCommandName(name)?.let { resolved ->
            normalize(resolved).takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        }
        if (depth >= MAX_DEPTH) return candidates

        // /execute as @a at @s run op Steve：真正执行的是 run 后面的命令
        val trimmed = command.trim().trimStart('/')
        if (name == "execute") {
            val runIndex = trimmed.indexOf(" run ", ignoreCase = true)
            if (runIndex >= 0) {
                candidates.addAll(collectCandidates(plugin, trimmed.substring(runIndex + 5), depth + 1))
            }
        }
        return candidates
    }
}
