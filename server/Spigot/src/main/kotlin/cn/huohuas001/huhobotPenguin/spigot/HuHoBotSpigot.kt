package cn.huohuas001.huhobotPenguin.spigot

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.addon.Addon
import cn.huohuas001.bot.addon.AddonManager
import cn.huohuas001.bot.agent.AgentCommandMode
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.spigot.commands.AtCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.QqBindCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.BukkitConsoleSender
import cn.huohuas001.huhobotPenguin.spigot.commands.CommandOutputAppender
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.HybridCommandExecutor
import cn.huohuas001.huhobotPenguin.spigot.events.GameChat
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotCommand
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotRecvMsg
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import cn.huohuas001.huhobotPenguin.spigot.inventory.InventoryRenderer
import cn.huohuas001.huhobotPenguin.adapter.api.MsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.toMsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.withCommand
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class HuHoBotSpigot : JavaPlugin(), HuHoBot {
    companion object {
        private var instance: HuHoBotSpigot? = null
        fun getInstance(): HuHoBotSpigot? = instance
    }

    private lateinit var configManager: ConfigManager

    override fun onEnable() {
        instance = this
        configManager = ConfigManager(this)
        configManager.initialize()
        initializeRuntime()
        logCommandExecutor()
        val command = HuHoBotCommand(this)
        getCommand("huhobot")?.apply {
            setExecutor(command)
            tabCompleter = command
        } ?: log_error("无法注册 /huhobot 命令，请检查 plugin.yml")
        server.pluginManager.registerEvents(GameChat(), this)
        val atCommand = AtCommand()
        getCommand("at")?.apply {
            setExecutor(atCommand)
            tabCompleter = atCommand
        }
        val qqBindCommand = QqBindCommand()
        getCommand("qqbind")?.apply {
            setExecutor(qqBindCommand)
        } ?: log_error("无法注册 /qqbind 命令，请检查 plugin.yml")
        InventoryRenderer.init()
        log_info("HuHoBot Penguin 已加载")
    }

    override fun onDisable() {
        instance = null
        shutdownRuntime()
        CommandOutputAppender.removeInstance()
    }

    private fun logCommandExecutor() {
        val useHybridExecutor = configManager.commandSender().equals("Hybrid", ignoreCase = true)
        if (useHybridExecutor) {
            log_info("已启用混合控制台命令执行器")
        } else {
            log_info("已启用模拟控制台命令执行器")
        }
    }

    override fun reloadPluginConfig() {
        configManager.reload()
        reloadRuntimeConfig()
        logCommandExecutor()
    }

    override fun createCommandExecutor(): HExecution =
        if (configManager.commandSender().equals("Hybrid", ignoreCase = true)) {
            HybridCommandExecutor(this)
        } else {
            BukkitConsoleSender(this)
        }

    override fun broadcastMessage(msg: String) {
        server.scheduler.runTask(this, Runnable { Bukkit.broadcastMessage(msg) })
    }

    override fun broadcastMessage(msg: String, highlightedPlayers: List<String>) {
        server.scheduler.runTask(this, Runnable {
            Bukkit.broadcastMessage(msg)
            if (highlightedPlayers.isNotEmpty()) {
                for (playerName in highlightedPlayers) {
                    val player = server.getPlayer(playerName) ?: continue
                    if (player.isOnline) {
                        player.playSound(
                            player.location,
                            org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,
                            1.0f,
                            1.0f
                        )
                    }
                }
            }
        })
    }

    override fun onBotReceivedGroupMessage(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence)
        val botEvent = OnBotRecvMsg(
            msgPack = msgPack,
            replyTextAction = { text ->
                QClient.replyText(msgPack.groupOpenId, msgPack.messageId, msgPack.messageSequence, text)
            },
            replyMarkdownAction = { markdown, keyboard ->
                QClient.replyMarkdown(
                    msgPack.groupOpenId,
                    msgPack.messageId,
                    msgPack.messageSequence,
                    markdown,
                    keyboard
                )
            }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    override fun onBotCommand(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence).withCommand(event.rawMessage.content.orEmpty())
        val botEvent = OnBotCommand(
            msgPack = msgPack,
            replyTextAction = { text ->
                QClient.replyText(msgPack.groupOpenId, msgPack.messageId, msgPack.messageSequence, text)
            },
            replyMarkdownAction = { markdown, keyboard ->
                QClient.replyMarkdown(
                    msgPack.groupOpenId,
                    msgPack.messageId,
                    msgPack.messageSequence,
                    markdown,
                    keyboard
                )
            }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    private fun <T : org.bukkit.event.Event> callSyncEvent(event: T): T {
        if (server.isPrimaryThread) {
            server.pluginManager.callEvent(event)
            return event
        }
        return try {
            server.scheduler.callSyncMethod(this) {
                server.pluginManager.callEvent(event)
                event
            }.get()
        } catch (error: Exception) {
            log_error("同步触发 Bukkit 事件失败: ${error.message}")
            event
        }
    }

    /**
     * 注册运行时自定义命令，并按 pushMenu 更新 QQ 命令面板。
     *
     * **旧规范**：仅传 key/command，命令不会归属到任何扩展。
     * 新代码应使用 [registerAddon] + [registerBotCommand] 四参数重载。
     */
    @JvmOverloads
    fun registerBotCommand(
        key: String,
        command: String,
        permission: Int = 0,
        pushMenu: Boolean = true
    ): Boolean {
        val registered = CustomCommandRegistry.register(
            CustomCommandDetail(key, command, permission, pushMenu)
        )
        if (!registered) return false
        QClient.syncGroupPanels()
        return true
    }

    /**
     * 注册运行时自定义命令并关联到指定扩展。
     *
     * @param addonName 已通过 [registerAddon] 注册的扩展名称
     * @param key       命令 key（QQ 群中用 `/执行 <key>` 或 `/ <key>` 触发）
     * @param command   执行的服务器命令模板
     * @param permission 权限级别，0 = 公开
     * @param pushMenu  是否同步到 QQ 命令面板
     */
    @JvmOverloads
    fun registerBotCommand(
        addonName: String,
        key: String,
        command: String,
        permission: Int = 0,
        pushMenu: Boolean = true
    ): Boolean {
        if (addonName !in AddonManager) {
            log_error("registerBotCommand 失败：扩展 '$addonName' 未注册，请先调用 registerAddon")
            return false
        }
        val registered = CustomCommandRegistry.register(
            CustomCommandDetail(key, command, permission, pushMenu)
        )
        if (!registered) return false
        AddonManager.addCommand(
            addonName,
            RegisteredCommand(
                command = key,
                describe = command,
                onlyAdmin = permission > 0,
                source = addonName
            )
        )
        QClient.syncGroupPanels()
        return true
    }

    /**
     * 注册当前插件为 HuHoBot 扩展。
     * 必须在 [registerBotCommand] 之前调用，否则命令无法归属到该扩展。
     *
     * @param name        扩展名称（建议与 plugin.yml 中 name 一致）
     * @param version     版本号
     * @param description 描述
     * @param author      作者
     * @return true 表示注册成功
     */
    @JvmOverloads
    fun registerAddon(
        name: String,
        version: String = "1.0.0",
        description: String = "",
        author: String = ""
    ): Boolean {
        if (name.isBlank()) {
            log_error("registerAddon 失败：扩展名称不能为空")
            return false
        }
        AddonManager.register(Addon(name, version, description, author))
        return true
    }

    /** 注销运行时自定义命令，并按需刷新 QQ 命令面板。 */
    fun unregisterBotCommand(key: String): Boolean {
        val removed = CustomCommandRegistry.unregister(key)
        if (removed) QClient.syncGroupPanels()
        return removed
    }

    /** 向配置中的所有 QQ 群发送普通文本。 */
    fun sendBotText(text: String) = sendText(text)

    /** 主动向指定 QQ 群发送普通文本。 */
    fun sendBotText(groupOpenId: String, text: String): Boolean =
        QClient.sendTextToGroup(groupOpenId, text).let { it != null }

    /** 向配置中的所有 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(markdown: String, keyboard: Keyboard? = null) = sendMarkdown(markdown, keyboard)

    /** 主动向指定 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(
        groupOpenId: String,
        markdown: String,
        keyboard: Keyboard? = null
    ): Boolean = QClient.sendMarkdownToGroup(groupOpenId, markdown, keyboard).let { it != null }

    override fun isAuthenticationEnabled(): Boolean = configManager.isAuthenticationEnabled()
    override fun getCommandMenuList(): Map<String, Boolean> = configManager.commandMenuSwitches()

    override fun submit(task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTask(this, task))

    override fun submitAsync(task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskAsynchronously(this, task))

    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskLater(this, task, delay))

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskTimer(this, task, delay, period))

    override fun getOnlineList(): List<String> = server.onlinePlayers.map { it.name }.toMutableList()

    override fun getPlayerInventory(playerName: String): String? {
        val player = server.getPlayerExact(playerName) ?: return null
        if (!player.isOnline) return null
        val inv = player.inventory
        val lines = mutableListOf<String>()

        // 护甲
        val armorNames = mapOf(
            "头盔" to inv.helmet,
            "胸甲" to inv.chestplate,
            "护腿" to inv.leggings,
            "靴子" to inv.boots
        )
        val armorLine = armorNames.map { (slot, item) ->
            "$slot: ${item?.let { formatItem(it) } ?: "空"}"
        }.joinToString(" | ")
        lines.add("=== 护甲 ===")
        lines.add(armorLine)

        // 副手
        lines.add("=== 副手 ===")
        lines.add("副手: ${inv.itemInOffHand.let { formatItem(it) }}")

        // 物品栏（3行9列）
        lines.add("=== 物品栏 ===")
        val storage = inv.storageContents
        for (row in 0 until 3) {
            val rowItems = (0 until 9).map { col ->
                val idx = row * 9 + col
                storage.getOrNull(idx)?.let { formatItem(it) } ?: "---"
            }
            lines.add(rowItems.joinToString(" | "))
        }

        // 快捷栏
        lines.add("=== 快捷栏 ===")
        val hotbar = (0 until 9).map { idx ->
            storage.getOrNull(idx)?.let { formatItem(it) } ?: "---"
        }
        lines.add(hotbar.joinToString(" | "))

        return lines.joinToString("\n")
    }

    override fun getPlayerInventoryImage(playerName: String): ByteArray? {
        val player = server.getPlayerExact(playerName) ?: return null
        if (!player.isOnline) return null
        return try {
            InventoryRenderer.render(player)
        } catch (e: Exception) {
            log_error("背包渲染失败: ${e.message}")
            null
        }
    }

    private fun formatItem(item: org.bukkit.inventory.ItemStack): String {
        if (item.type.isAir) return "---"
        val meta = item.itemMeta
        val rawName = when {
            meta?.hasDisplayName() == true -> meta.displayName
            else -> MATERIAL_CN[item.type] ?: item.type.name.replace("_", " ").lowercase()
                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
        // 去除 Minecraft § 颜色/格式码
        val name = rawName.replace(Regex("§[0-9a-fk-or]"), "")
        val amount = item.amount
        val isDamageable = item.type.maxDurability > 0
        val durability = if (isDamageable) {
            val dmg = (meta as? org.bukkit.inventory.meta.Damageable)?.damage ?: 0
            if (dmg > 0) " (${item.type.maxDurability - dmg}/${item.type.maxDurability})" else ""
        } else ""
        val enchant = if (meta?.hasEnchants() == true) " *" else ""
        return when {
            amount > 1 && durability.isNotEmpty() -> "$name x$amount$durability$enchant"
            amount > 1 -> "$name x$amount$enchant"
            durability.isNotEmpty() -> "$name$durability$enchant"
            else -> "$name$enchant"
        }
    }

    private val MATERIAL_CN = mapOf(
        org.bukkit.Material.DIAMOND_SWORD to "钻石剑",
        org.bukkit.Material.DIAMOND_PICKAXE to "钻石镐",
        org.bukkit.Material.DIAMOND_AXE to "钻石斧",
        org.bukkit.Material.DIAMOND_SHOVEL to "钻石锹",
        org.bukkit.Material.DIAMOND_HOE to "钻石锄",
        org.bukkit.Material.IRON_SWORD to "铁剑",
        org.bukkit.Material.IRON_PICKAXE to "铁镐",
        org.bukkit.Material.IRON_AXE to "铁斧",
        org.bukkit.Material.IRON_SHOVEL to "铁锹",
        org.bukkit.Material.IRON_HOE to "铁锄",
        org.bukkit.Material.GOLDEN_SWORD to "金剑",
        org.bukkit.Material.GOLDEN_PICKAXE to "金镐",
        org.bukkit.Material.GOLDEN_AXE to "金斧",
        org.bukkit.Material.GOLDEN_SHOVEL to "金锹",
        org.bukkit.Material.GOLDEN_HOE to "金锄",
        org.bukkit.Material.STONE_SWORD to "石剑",
        org.bukkit.Material.STONE_PICKAXE to "石镐",
        org.bukkit.Material.STONE_AXE to "石斧",
        org.bukkit.Material.STONE_SHOVEL to "石锹",
        org.bukkit.Material.STONE_HOE to "石锄",
        org.bukkit.Material.WOODEN_SWORD to "木剑",
        org.bukkit.Material.WOODEN_PICKAXE to "木镐",
        org.bukkit.Material.WOODEN_AXE to "木斧",
        org.bukkit.Material.WOODEN_SHOVEL to "木锹",
        org.bukkit.Material.WOODEN_HOE to "木锄",
        org.bukkit.Material.NETHERITE_SWORD to "下界合金剑",
        org.bukkit.Material.NETHERITE_PICKAXE to "下界合金镐",
        org.bukkit.Material.NETHERITE_AXE to "下界合金斧",
        org.bukkit.Material.NETHERITE_SHOVEL to "下界合金锹",
        org.bukkit.Material.NETHERITE_HOE to "下界合金锄",
        org.bukkit.Material.BOW to "弓",
        org.bukkit.Material.CROSSBOW to "弩",
        org.bukkit.Material.TRIDENT to "三叉戟",
        org.bukkit.Material.SHIELD to "盾牌",
        org.bukkit.Material.FISHING_ROD to "钓鱼竿",
        org.bukkit.Material.FLINT_AND_STEEL to "打火石",
        org.bukkit.Material.SHEARS to "剪刀",
        org.bukkit.Material.END_ROD to "末地烛",
        org.bukkit.Material.ARROW to "箭矢",
        org.bukkit.Material.SPECTRAL_ARROW to "光灵箭",
        org.bukkit.Material.TIPPED_ARROW to "药水箭",
        org.bukkit.Material.BONE to "骨头",
        org.bukkit.Material.BONE_MEAL to "骨粉",
        org.bukkit.Material.DIAMOND to "钻石",
        org.bukkit.Material.EMERALD to "绿宝石",
        org.bukkit.Material.GOLD_INGOT to "金锭",
        org.bukkit.Material.IRON_INGOT to "铁锭",
        org.bukkit.Material.NETHERITE_INGOT to "下界合金锭",
        org.bukkit.Material.NETHERITE_SCRAP to "下界合金碎片",
        org.bukkit.Material.COAL to "煤炭",
        org.bukkit.Material.CHARCOAL to "木炭",
        org.bukkit.Material.REDSTONE to "红石",
        org.bukkit.Material.LAPIS_LAZULI to "青金石",
        org.bukkit.Material.DIAMOND_HELMET to "钻石头盔",
        org.bukkit.Material.DIAMOND_CHESTPLATE to "钻石胸甲",
        org.bukkit.Material.DIAMOND_LEGGINGS to "钻石护腿",
        org.bukkit.Material.DIAMOND_BOOTS to "钻石靴子",
        org.bukkit.Material.IRON_HELMET to "铁头盔",
        org.bukkit.Material.IRON_CHESTPLATE to "铁胸甲",
        org.bukkit.Material.IRON_LEGGINGS to "铁护腿",
        org.bukkit.Material.IRON_BOOTS to "铁靴子",
        org.bukkit.Material.GOLDEN_HELMET to "金头盔",
        org.bukkit.Material.GOLDEN_CHESTPLATE to "金胸甲",
        org.bukkit.Material.GOLDEN_LEGGINGS to "金护腿",
        org.bukkit.Material.GOLDEN_BOOTS to "金靴子",
        org.bukkit.Material.LEATHER_HELMET to "皮革头盔",
        org.bukkit.Material.LEATHER_CHESTPLATE to "皮革胸甲",
        org.bukkit.Material.LEATHER_LEGGINGS to "皮革护腿",
        org.bukkit.Material.LEATHER_BOOTS to "皮革靴子",
        org.bukkit.Material.CHAINMAIL_HELMET to "锁链头盔",
        org.bukkit.Material.CHAINMAIL_CHESTPLATE to "锁链胸甲",
        org.bukkit.Material.CHAINMAIL_LEGGINGS to "锁链护腿",
        org.bukkit.Material.CHAINMAIL_BOOTS to "锁链靴子",
        org.bukkit.Material.NETHERITE_HELMET to "下界合金头盔",
        org.bukkit.Material.NETHERITE_CHESTPLATE to "下界合金胸甲",
        org.bukkit.Material.NETHERITE_LEGGINGS to "下界合金护腿",
        org.bukkit.Material.NETHERITE_BOOTS to "下界合金靴子",
        org.bukkit.Material.TURTLE_HELMET to "海龟头盔",
        org.bukkit.Material.ELYTRA to "鞘翅",
        org.bukkit.Material.TOTEM_OF_UNDYING to "不死图腾",
        org.bukkit.Material.EXPERIENCE_BOTTLE to "经验瓶",
        org.bukkit.Material.ENDER_PEARL to "末影珍珠",
        org.bukkit.Material.ENDER_EYE to "末影之眼",
        org.bukkit.Material.ENDER_CHEST to "末影箱",
        org.bukkit.Material.BREAD to "面包",
        org.bukkit.Material.COOKED_BEEF to "熟牛排",
        org.bukkit.Material.COOKED_PORKCHOP to "熟猪排",
        org.bukkit.Material.COOKED_MUTTON to "熟羊肉",
        org.bukkit.Material.COOKED_CHICKEN to "熟鸡肉",
        org.bukkit.Material.COOKED_RABBIT to "熟兔肉",
        org.bukkit.Material.COOKED_COD to "熟鳕鱼",
        org.bukkit.Material.COOKED_SALMON to "熟鲑鱼",
        org.bukkit.Material.GOLDEN_APPLE to "金苹果",
        org.bukkit.Material.ENCHANTED_GOLDEN_APPLE to "附魔金苹果",
        org.bukkit.Material.APPLE to "苹果",
        org.bukkit.Material.CARROT to "胡萝卜",
        org.bukkit.Material.GOLDEN_CARROT to "金胡萝卜",
        org.bukkit.Material.POTATO to "土豆",
        org.bukkit.Material.BAKED_POTATO to "烤土豆",
        org.bukkit.Material.MUSHROOM_STEW to "蘑菇煲",
        org.bukkit.Material.BEETROOT to "甜菜根",
        org.bukkit.Material.BEETROOT_SOUP to "甜菜汤",
        org.bukkit.Material.MELON_SLICE to "西瓜片",
        org.bukkit.Material.PUMPKIN_PIE to "南瓜派",
        org.bukkit.Material.COOKIE to "曲奇",
        org.bukkit.Material.CAKE to "蛋糕",
        org.bukkit.Material.WITHER_SKELETON_SPAWN_EGG to "凋灵骷髅刷怪蛋",
        org.bukkit.Material.ZOMBIE_SPAWN_EGG to "僵尸刷怪蛋",
        org.bukkit.Material.SKELETON_SPAWN_EGG to "骷髅刷怪蛋",
        org.bukkit.Material.CREEPER_SPAWN_EGG to "苦力怕刷怪蛋",
        org.bukkit.Material.SPIDER_SPAWN_EGG to "蜘蛛刷怪蛋",
        org.bukkit.Material.PIG_SPAWN_EGG to "猪刷怪蛋",
        org.bukkit.Material.COW_SPAWN_EGG to "牛刷怪蛋",
        org.bukkit.Material.CHICKEN_SPAWN_EGG to "鸡刷怪蛋",
        org.bukkit.Material.SHEEP_SPAWN_EGG to "羊刷怪蛋",
        org.bukkit.Material.PLAYER_HEAD to "玩家头颅",
        org.bukkit.Material.SKELETON_SKULL to "骷髅头颅",
        org.bukkit.Material.WITHER_SKELETON_SKULL to "凋灵骷髅头颅",
        org.bukkit.Material.ZOMBIE_HEAD to "僵尸头颅",
        org.bukkit.Material.CREEPER_HEAD to "苦力怕头颅",
        org.bukkit.Material.DRAGON_HEAD to "龙头",
        org.bukkit.Material.END_CRYSTAL to "末地水晶",
        org.bukkit.Material.BEDROCK to "基岩",
        org.bukkit.Material.BARRIER to "屏障",
        org.bukkit.Material.COMMAND_BLOCK to "命令方块",
        org.bukkit.Material.KNOWLEDGE_BOOK to "知识之书",
        org.bukkit.Material.ENCHANTED_BOOK to "附魔书",
        org.bukkit.Material.WRITTEN_BOOK to "成书"
    )
    override fun getConfigFile(): File = configManager.configFile
    override fun getBotAppId(): String = configManager.botAppId()
    override fun getBotSecret(): String = configManager.botSecret()
    override fun getChatFormat(): ChatFormat = configManager.chatFormat()
    override fun getPlayerEventFormat(): PlayerEventFormat = configManager.playerEventFormat()
    override fun getMarkdownFiles(): Map<String, String> = configManager.markdownFiles()
    override fun getMotd(): Motd = configManager.motd()
    override fun getWhiteList(): WhiteList = configManager.whiteList()
    override fun getFilterRegexList(): List<String> = configManager.filterRegexList()
    override fun getAdminMode(): AdminMode = configManager.adminMode()
    override fun getAdminList(): List<String> = configManager.adminOpenIds()
    override fun getGroupOpenIdList(): List<String> = configManager.groupOpenIds()
    override fun shouldSuppressQqBotConsoleOutput(): Boolean =
        configManager.suppressQqBotConsoleOutput()

    override fun getFullAmount(): Boolean = configManager.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = configManager.commandSwitches()
    override fun getAuditBaseUrl(): String? = configManager.auditBaseUrl()
    override fun getAuditApiKey(): String? = configManager.auditApiKey()
    override fun getAuditModel(): String? = configManager.auditModel()
    override fun getCustomCommands(): List<CustomCommandDetail> = configManager.customCommands()
    override fun getBotName(): String = configManager.botName()
    override fun getServerName(): String = configManager.serverName()
    override fun getPlatform(): String = "Spigot"
    override fun getPluginVersion(): String = description.version
    override fun getServerVersion(): String = server.version

    override fun getAgentEnabled(): Boolean = configManager.agentEnabled()
    override fun getAgentBaseUrl(): String? = configManager.agentBaseUrl()
    override fun getAgentApiKey(): String? = configManager.agentApiKey()
    override fun getAgentModel(): String? = configManager.agentModel()
    override fun getAgentCommandMode(): AgentCommandMode = configManager.agentCommandMode()

    override fun getBindingRequireGameVerification(): Boolean = configManager.bindingRequireGameVerification()

    override fun getCommandBlacklist(): List<String> = configManager.commandBlacklist()

    override fun getWebUiConfigValues(): Map<String, Any?> =
        config.getValues(true)

    override fun applyWebUiConfigChanges(changes: JSONObject): Boolean {
        return try {
            changes.forEach { (path, value) ->
                config.set(path, convertJsonValue(value))
            }
            saveConfig()
            reloadPluginConfig()
            true
        } catch (error: Exception) {
            log_error("WebUI 保存配置失败: ${error.message}")
            false
        }
    }

    /** 将 fastjson 值转换为 Bukkit 配置可接受的 Java 类型。 */
    private fun convertJsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.entries.associate { (k, v) -> k to convertJsonValue(v) }
        is JSONArray -> value.map { convertJsonValue(it) }
        else -> value
    }

    override fun getServerPluginList(): List<String> =
        server.pluginManager.plugins.map { it.name }.sorted()

    override fun getServerCommandHelp(pluginName: String?, commandName: String?): String {
        return when {
            commandName != null -> formatSingleCommand(commandName)
            pluginName != null -> formatPluginCommands(pluginName)
            else -> formatAllCommands()
        }
    }

    private fun formatSingleCommand(commandName: String): String {
        val cmd = findCommand(commandName)
            ?: return "未找到命令: /$commandName"
        return formatCommandDetail(cmd)
    }

    /** 查找命令：优先插件命令，其次命令表（兼容原版命令与别名）。 */
    private fun findCommand(commandName: String): Command? {
        server.getPluginCommand(commandName)?.let { return it }
        val commandMap = resolveCommandMapObject() ?: return null
        try {
            (commandMap as? CommandMap)?.getCommand(commandName)?.let { return it }
        } catch (_: Exception) {
        }
        return readKnownCommands(commandMap)?.let { known ->
            known[commandName.lowercase()]
                ?: known.values.firstOrNull { it.name.equals(commandName, true) }
        }
    }

    /** 统一格式化命令详情，兼容插件命令与原版（默认）命令。 */
    private fun formatCommandDetail(cmd: Command): String = buildString {
        appendLine("命令：/${cmd.name}")
        cmd.aliases.takeIf { it.isNotEmpty() }?.let { appendLine("别名：${it.joinToString(", ")}") }
        if (!cmd.description.isNullOrBlank()) appendLine("描述：${cmd.description}")
        val usage = cmd.usage?.trim()
        if (!usage.isNullOrBlank() && usage != "/<command>") appendLine("用法：$usage")
        cmd.permission?.takeIf { it.isNotBlank() }?.let { appendLine("权限：$it") }
        val pluginCommand = cmd as? PluginCommand
        if (pluginCommand != null) {
            pluginCommand.plugin?.let { appendLine("所属插件：${it.name}") }
        } else {
            appendLine("类型：原版命令（服务器版本：${server.version}）")
            appendLine("提示：原版命令的具体参数与语法请按服务器版本使用，可在游戏内执行 /help <命令> 查看。")
        }
    }.trimEnd()

    private fun formatPluginCommands(pluginName: String): String {
        val plugin = server.pluginManager.getPlugin(pluginName)
            ?: return "未找到插件：$pluginName（可用插件：${getServerPluginList().joinToString(", ")}）"

        val commands = plugin.description.commands
        if (commands.isEmpty()) return "插件 ${plugin.name} 没有注册命令。"
        return buildString {
            appendLine("插件 ${plugin.name} 注册的命令：")
            commands.toSortedMap().forEach { (label, meta) ->
                val desc = meta["description"]?.toString()?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                appendLine("/$label$desc")
            }
        }.trimEnd()
    }

    private fun formatAllCommands(): String {
        val commandMap = resolveCommandMapObject()
        val known = commandMap?.let { readKnownCommands(it) }
        val perPlugin = sortedMapOf<String, MutableList<String>>()

        if (known != null) {
            val seen = mutableSetOf<String>()
            known.values.forEach { cmd ->
                val label = cmd.name ?: return@forEach
                if (!seen.add(label)) return@forEach
                val owner = (cmd as? PluginCommand)?.plugin?.name ?: "原版命令"
                perPlugin.getOrPut(owner) { mutableListOf() }.add(label)
            }
        } else {
            server.pluginManager.plugins.forEach { plugin ->
                val labels = plugin.description.commands.keys
                if (labels.isNotEmpty()) {
                    perPlugin.getOrPut(plugin.name) { mutableListOf() }.addAll(labels)
                }
            }
        }

        if (perPlugin.isEmpty()) return "服务器没有可查询的命令。"
        return buildString {
            appendLine("服务器命令概览：")
            perPlugin.forEach { (owner, labels) ->
                appendLine("- $owner：${labels.sorted().joinToString(", ")}")
            }
            if (known == null) appendLine("（仅列出插件命令，原版命令表访问失败）")
        }.trimEnd()
    }

    /** 从命令表对象中读取全部命令映射，兼容不同版本字段名。 */
    private fun readKnownCommands(commandMap: Any): Map<String, Command>? {
        for (fieldName in listOf("knownCommands", "commandMap")) {
            try {
                val field = commandMap.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val map = field.get(commandMap) as? Map<String, Command>
                if (map != null) return map
            } catch (error: Exception) {
                log_debug("Agent 读取 $fieldName 字段失败: ${error.message}")
            }
        }
        try {
            val method = commandMap.javaClass.getMethod("getKnownCommands")
            @Suppress("UNCHECKED_CAST")
            val map = method.invoke(commandMap) as? Map<String, Command>
            if (map != null) return map
        } catch (error: Exception) {
            log_debug("Agent 调用 getKnownCommands 失败: ${error.message}")
        }
        return null
    }

    private fun resolveCommandMapObject(): Any? {
        // 1) 直接调用 getCommandMap()
        try {
            val method = server.javaClass.getMethod("getCommandMap")
            return method.invoke(server) ?: run {
                log_debug("Agent getCommandMap() 返回 null")
                null
            }
        } catch (error: Exception) {
            log_debug("Agent 反射 getCommandMap 失败: ${error.message}")
        }
        // 2) 遍历方法，找返回 CommandMap 的无参方法
        try {
            for (method in server.javaClass.methods) {
                if (method.parameterCount == 0 && CommandMap::class.java.isAssignableFrom(method.returnType)) {
                    val result = method.invoke(server)
                    if (result != null) return result
                }
            }
        } catch (error: Exception) {
            log_debug("Agent 遍历命令表方法失败: ${error.message}")
        }
        log_warning("Agent 无法访问服务器命令表，AI 命令帮助功能将不可用")
        return null
    }

    override fun getServerLogs(lines: Int?, keyword: String?): String {
        val logFile = resolveLogFile() ?: return "无法定位服务端日志文件（logs/latest.log）"
        val allLines = try {
            logFile.readLines(Charsets.UTF_8)
        } catch (error: Throwable) {
            return "读取服务端日志失败：${error.message}"
        }

        val lineCount = (lines ?: 50).coerceIn(1, 500)
        val kw = keyword?.trim()?.takeIf(String::isNotEmpty)
        val context = 5

        val selected: List<String>
        val matchCount: Int?
        if (kw != null) {
            val indices = allLines.indices.filter { allLines[it].contains(kw, ignoreCase = true) }
            if (indices.isEmpty()) {
                return "未在服务端日志中找到包含「$kw」的内容。"
            }
            matchCount = indices.size
            val range = indices.first()..indices.last()
            val from = (range.first - context).coerceAtLeast(0)
            val to = (range.last + context).coerceAtMost(allLines.size - 1)
            selected = allLines.subList(from, to)
        } else {
            matchCount = null
            selected = allLines.takeLast(lineCount)
        }

        val content = selected.joinToString("\n").trimEnd()
        val tip = matchCount?.let { "\n\n(按关键词「$kw」过滤，共匹配 $it 处)" } ?: ""
        return content + tip
    }

    /** 定位服务端日志文件 logs/latest.log。 */
    private fun resolveLogFile(): File? {
        val candidates = listOfNotNull(
            server.getWorldContainer().resolve("logs").resolve("latest.log"),
            server.getWorldContainer().parentFile?.resolve("logs")?.resolve("latest.log"),
            File("logs/latest.log")
        )
        return candidates.firstOrNull { it.isFile }
    }

    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warning(msg)
    override fun log_error(msg: String) = logger.severe(msg)
}
