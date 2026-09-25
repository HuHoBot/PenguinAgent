package cn.huohuas001.huhobotPenguin.spigot.integration

import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.Bukkit
import java.lang.reflect.Method

/**
 * PlaceholderAPI 反射接入。
 *
 * 其它插件通过 PlaceholderAPI 注册的 %占位符% 会由本类代为解析；
 * 未安装或未启用 PlaceholderAPI 时文本原样返回，不引入编译期依赖。
 */
object PlaceholderApiSupport {
    private const val PLUGIN_NAME = "PlaceholderAPI"
    private const val CLASS_NAME = "me.clip.placeholderapi.PlaceholderAPI"

    @Volatile
    private var setPlaceholders: Method? = null

    val available: Boolean get() = setPlaceholders != null

    /** 插件启动与 /hb reload 后调用，重新探测 PlaceholderAPI。 */
    fun setup(plugin: HuHoBotSpigot) {
        if (!plugin.isPlaceholderApiEnabled()) {
            setPlaceholders = null
            return
        }
        val dependency = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME)
        if (dependency == null || !dependency.isEnabled) {
            setPlaceholders = null
            plugin.logger.info("未检测到 PlaceholderAPI，占位符将原样保留")
            return
        }
        setPlaceholders = try {
            val type = Class.forName(CLASS_NAME)
            val playerClass = Class.forName("org.bukkit.OfflinePlayer")
            type.getMethod("setPlaceholders", playerClass, String::class.java)
        } catch (error: Throwable) {
            plugin.logger.warning("PlaceholderAPI 接入失败: ${error.message}")
            null
        }
        if (setPlaceholders != null) {
            plugin.logger.info("已接入 PlaceholderAPI（$PLUGIN_NAME）")
        }
    }

    /**
     * 解析文本中的 PlaceholderAPI 占位符。
     *
     * @param playerName 玩家名；为空时按无玩家上下文解析（只支持全局占位符）
     */
    fun apply(playerName: String?, text: String): String {
        val method = setPlaceholders ?: return text
        if (text.isEmpty() || !text.contains('%')) return text
        return try {
            val online = playerName?.takeIf { it.isNotBlank() }?.let { Bukkit.getPlayerExact(it) }
            val player = online ?: playerName?.takeIf { it.isNotBlank() }?.let { Bukkit.getOfflinePlayer(it) }
            method.invoke(null, player, text) as? String ?: text
        } catch (error: Throwable) {
            text
        }
    }
}
