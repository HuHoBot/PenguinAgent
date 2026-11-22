package agent.spigot.commands

import agent.web.WebUiPassword
import agent.web.WebUiServer
import agent.spigot.HuHoBotSpigot
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
        val action=args.firstOrNull()?.lowercase(java.util.Locale.ROOT)
        if (!sender.hasPermission(permissionFor(action))) {
            sender.sendMessage("${ChatColor.RED}你没有执行此命令的权限。")
            return true
        }
        if (action in GameCommandRoutes.movable && action !in plugin.getHandoverSubcommands()) {
            sender.sendMessage("用法：${plugin.getGameCommandPath(action!!)} ${usageFor(action)}")
            return true
        }
        when (action) {
            "reload" -> {
                plugin.fullRestart()
                sender.sendMessage(ChatColor.GOLD.toString() + "已提交后台重载；资源准备完成后会在控制台提示。")
            }

            "info" -> sender.sendMessage(
                "平台: ${plugin.getPlatform()}\n版本: ${plugin.getPluginVersion()}"
            )

            "password" -> handlePassword(sender, args)
            "scripts" -> handleScripts(sender, args)
            "modules" -> handleModules(sender,args)
            "webui" -> {
                val port = plugin.getWebUiPort()
                sender.sendMessage(
                    "WebUI 地址: http://localhost:${port}\n" +
                        "WebUI 密码: ${currentPasswordHint()}"
                )
            }

            // 指令冲突回退：原命令被其他插件占用时，改用 /huhobot <子命令>
            "qqbind" -> QqBindCommand().onCommand(sender, _command, "$label qqbind", args.drop(1).toTypedArray())
            "at" -> AtCommand().onCommand(sender, _command, "$label at", args.drop(1).toTypedArray())
            "send" -> SendCommand(plugin).onCommand(sender, _command, "$label send", args.drop(1).toTypedArray())

            else -> sendHelp(sender, label)
        }
        return true
    }

    private fun handlePassword(sender: CommandSender, args: Array<out String>) {
        val newPassword = args.getOrNull(1)
        if (newPassword == null) {
            sender.sendMessage("用法: ${plugin.getGameCommandPath("huhobot")} password <新密码>")
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
    private fun handleModules(sender:CommandSender,args:Array<out String>){
        if(args.size<5||args[1]!="install"){
            sender.sendMessage("用法: ${plugin.getGameCommandPath("huhobot")} modules install <font-名字> <HTTPS地址[,镜像]> <SHA256>");return
        }
        sender.sendMessage("大模块正在后台流式下载；不会在主线程解压或启用，完成后需重启")
        plugin.submitAsync {
            val message=runCatching { agent.spigot.scripting.ModuleDownloads.stage(plugin.getDataDirectory() ?: java.io.File(plugin.dataFolder,"data"),args[2],plugin.getPluginVersion(),args[3].split(','),args[4]) }
                .getOrElse { "模块下载未启用: ${it.message}" }
            if(plugin.isEnabled)runCatching { plugin.submit { sender.sendMessage(message) } }
        }
    }

    private fun handleScripts(sender: CommandSender, args: Array<out String>) {
        val action = args.getOrNull(1)?.lowercase()
        if(action=="install") {
            if(args.size<5){sender.sendMessage("用法: ${plugin.getGameCommandPath("huhobot")} scripts install <目录名> <HTTPS地址[,镜像地址]> <SHA256>");return}
            val name=args[2];val urls=args[3].split(',').filter(String::isNotBlank);val sha=args[4]
            sender.sendMessage("开始校验并下载扩展；下载完成不会自动执行")
            plugin.submitAsync {
                val message=runCatching {
                    agent.spigot.scripting.AddonSources.installZip(java.io.File(plugin.dataFolder,"addons"),name,urls,sha)
                    "扩展已下载并校验；确认可信后执行 ${plugin.getGameCommandPath("huhobot")} scripts reload $name"
                }.getOrElse { "扩展安装未完成: ${it.message}" }
                runCatching { plugin.submit { sender.sendMessage(message) } }
            }
            return
        }
        if (action != "reload") {
            sender.sendMessage("用法: ${plugin.getGameCommandPath("huhobot")} scripts reload [插件名]")
            return
        }
        val loader = plugin.getScriptAddonLoader()
        if (loader == null) {
            sender.sendMessage("${ChatColor.RED}脚本加载器未初始化")
            return
        }
        val start=System.currentTimeMillis();val target=args.getOrNull(2)
        sender.sendMessage("扩展正在后台准备；主线程仍可处理其它命令")
        val future=if(target==null)loader.reloadAllAsync()else loader.reloadOneAsync(target).thenApply { listOf(it) }
        future.whenComplete { results,error ->
            if(plugin.isEnabled)runCatching { plugin.submit {
                if(error!=null)sender.sendMessage("扩展重载失败: ${error.message}")
                else {
                    sender.sendMessage("扩展重载完成：${results.count { it.success() }} 成功，${results.count { !it.success()&&!it.skipped() }} 失败，${System.currentTimeMillis()-start}ms")
                    results.filter { !it.success() }.forEach { sender.sendMessage(it.formatted()) }
                }
            } }
        }
    }

    /** 密码文件不存在时返回"启动时自动生成"，否则提示查看配置文件。 */
    private fun currentPasswordHint(): String {
        return if (WebUiPassword.isConfigured()) "已设置（可用 ${plugin.getGameCommandPath("huhobot")} password 修改）" else "首次启动时自动生成"
    }

    override fun onTabComplete(
        _sender: CommandSender,
        _command: Command,
        _alias: String,
        args: Array<out String>
    ): List<String> {
        val prefix = args.firstOrNull()?.lowercase(java.util.Locale.ROOT).orEmpty()
        val available=(SUBCOMMANDS+plugin.getHandoverSubcommands()).filter { _sender.hasPermission(permissionFor(it)) }
        if (args.size<=1) return available.filter { it.startsWith(prefix) }
        if (prefix !in available) return emptyList()
        if (prefix=="at") return AtCommand().onTabComplete(_sender,_command,_alias,args.drop(1).toTypedArray())
        if (args.size == 2 && prefix == "scripts") {
            return listOf("reload","install").filter { it.startsWith(args[1].lowercase()) }
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
        if (sender.hasPermission("huhobot.command")) {
            sender.sendMessage("/$label reload - 重载配置和有修改的功能")
            sender.sendMessage("/$label info - 查看版本信息")
            sender.sendMessage("/$label password <新密码> - 修改 WebUI 登录密码")
            sender.sendMessage("/$label webui - 查看 WebUI 地址")
            sender.sendMessage("/$label scripts reload [扩展名] - 后台重载扩展")
            sender.sendMessage("/$label modules install <模块名> <HTTPS地址> <SHA256> - 后台下载字体资源")
        }
        for (key in plugin.getHandoverSubcommands()) {
            if (sender.hasPermission(permissionFor(key))) sender.sendMessage("/$label $key ${usageFor(key)}（原指令被占用）")
        }
    }

    companion object {
        private val SUBCOMMANDS = listOf("reload", "info", "password", "webui", "modules", "scripts", "help")
        internal fun permissionFor(action: String?) = when(action) {
            "qqbind" -> "huhobot.qqbind"
            "send" -> "huhobot.send"
            else -> "huhobot.command"
        }
        private fun usageFor(action: String) = when(action) {
            "qqbind" -> "<验证码>"
            "at" -> "<群成员昵称> <消息内容>"
            else -> "<消息内容>"
        }
    }
}
