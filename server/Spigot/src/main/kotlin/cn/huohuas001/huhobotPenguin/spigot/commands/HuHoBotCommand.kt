package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.bot.web.WebUiPassword
import cn.huohuas001.bot.web.WebUiServer
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

class HuHoBotCommand(private val plugin: HuHoBotSpigot) : TabExecutor {
    override fun onCommand(
        sender: CommandSender,
        _command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                plugin.fullRestart()
                sender.sendMessage(ChatColor.GOLD.toString() + "HuHoBot 已完整重启。")
            }

            "info" -> sender.sendMessage(
                "平台: ${plugin.getPlatform()}\n版本: ${plugin.getPluginVersion()}"
            )

            "password" -> handlePassword(sender, args)
            "scripts" -> handleScripts(sender, args)
            "webui" -> {
                val port = plugin.getWebUiPort()
                sender.sendMessage(
                    "WebUI 地址: http://localhost:${port}\n" +
                        "WebUI 密码: ${currentPasswordHint()}"
                )
            }

            else -> sendHelp(sender, label)
        }
        return true
    }

    private fun handlePassword(sender: CommandSender, args: Array<out String>) {
        val newPassword = args.getOrNull(1)
        if (newPassword == null) {
            sender.sendMessage("用法: /hb password <新密码>")
            return
        }
        if (WebUiPassword.changePassword(newPassword)) {
            WebUiServer.invalidateAllTokens()
            sender.sendMessage(ChatColor.GREEN.toString() + "WebUI 密码已修改。")
        } else {
            sender.sendMessage(ChatColor.RED.toString() + "密码修改失败（密码需至少 6 位）。")
        }
    }

    /**
     * `/huhobot scripts reload [插件名]`
     * 重载 `plugins/HuHoBotPenguin/addons` 下的目录插件。
     * 不带名字时全部重载；带名字时只重载那个目录（大小写不敏感）。
     */
    private fun handleScripts(sender: CommandSender, args: Array<out String>) {
        val action = args.getOrNull(1)?.lowercase()
        if (action != "reload") {
            sender.sendMessage("用法: /huhobot scripts reload [插件名]")
            return
        }
        val loader = plugin.getScriptAddonLoader()
        if (loader == null) {
            sender.sendMessage("${ChatColor.RED}脚本加载器未初始化")
            return
        }
        val start = System.currentTimeMillis()
        val target = args.getOrNull(2)
        if (target != null) {
            val took = System.currentTimeMillis() - start
            sender.sendMessage(
                String.format(
                    "%s %s(%dms)",
                    loader.reloadOne(target).formatted(),
                    "${ChatColor.GRAY}",
                    took
                )
            )
            return
        }
        val results = loader.reloadAll()
        val took = System.currentTimeMillis() - start
        if (results.isEmpty()) {
            sender.sendMessage(
                "${ChatColor.YELLOW}${loader.getScriptsFolder().path} 下没有脚本插件目录" +
                    "（每个插件建一个目录，放 main.lua / main.py / main.js）"
            )
            return
        }
        val okCount = results.count { it.success() }
        // _enabled=false 是有意的跳过，不算失败。
        val failCount = results.count { !it.success() && !it.skipped() }
        val skipCount = results.count { it.skipped() }
        if (failCount == 0 && skipCount == 0) {
            sender.sendMessage("${ChatColor.GREEN}脚本重载完成: 全部 $okCount 个成功 ${ChatColor.GRAY}(${took}ms)")
        } else {
            val summary = StringBuilder("${ChatColor.YELLOW}脚本重载完成: ${ChatColor.GREEN}$okCount 成功 ${ChatColor.GRAY}/ ")
            if (failCount > 0) summary.append("${ChatColor.RED}$failCount 失败 ${ChatColor.GRAY}/ ")
            if (skipCount > 0) summary.append("${ChatColor.YELLOW}$skipCount 跳过 ${ChatColor.GRAY}/ ")
            summary.append("(${took}ms)")
            sender.sendMessage(summary.toString())
            results.filter { !it.success() }.forEach { sender.sendMessage("  ${it.formatted()}") }
        }
    }

    /** 密码文件不存在时返回"启动时自动生成"，否则提示查看配置文件。 */
    private fun currentPasswordHint(): String {
        return if (WebUiPassword.isConfigured()) "已设置（可用 /hb password 修改）" else "首次启动时自动生成"
    }

    override fun onTabComplete(
        _sender: CommandSender,
        _command: Command,
        _alias: String,
        args: Array<out String>
    ): List<String> {
        val prefix = args[0].lowercase()
        if (args.size == 1) return SUBCOMMANDS.filter { it.startsWith(prefix) }
        if (args.size == 2 && prefix == "scripts") {
            return listOf("reload").filter { it.startsWith(args[1].lowercase()) }
        }
        if (args.size == 3 && prefix == "scripts" && args[1].equals("reload", ignoreCase = true)) {
            val partial = args[2].lowercase()
            return plugin.getScriptAddonLoader()?.listScriptFileNames()
                ?.filter { it.lowercase().startsWith(partial) }
                .orEmpty()
        }
        return emptyList()
    }

    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage("/$label reload - 重载配置文件")
        sender.sendMessage("/$label info - 查看适配器信息")
        sender.sendMessage("/$label password <新密码> - 修改 WebUI 登录密码")
        sender.sendMessage("/$label webui - 查看 WebUI 地址")
        sender.sendMessage("/$label scripts reload [插件名] - 重载 addons/ 下的目录插件")
        sender.sendMessage("/send <消息> - 向 QQ 群发送消息")
    }

    private companion object {
        val SUBCOMMANDS = listOf("reload", "info", "password", "webui", "scripts", "help")
    }
}
