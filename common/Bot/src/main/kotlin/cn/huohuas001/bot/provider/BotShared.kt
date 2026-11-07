package cn.huohuas001.bot.provider

import cn.huohuas001.bot.HuHoBot

object BotShared {
    private lateinit var plugin: HuHoBot
    //var customCommandMap: MutableMap<String, CustomCommandDetail> = ConcurrentHashMap()

    fun setInstance(instance: HuHoBot){
        plugin = instance
    }

    fun isInitialized(): Boolean = ::plugin.isInitialized

    fun getPluginOrNull(): HuHoBot? = if (isInitialized()) plugin else null

    fun getPlugin(): HuHoBot {
        return plugin
    }
}