package cn.huohuas001.huhobotPenguin.spigot.manager

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置字体管理。
 *
 * 插件自带一份 Noto Sans CJK SC 子集（ASCII + GB2312 + 常用符号，约 1.8MB），
 * 首次启动时解压到 `fonts/` 并注册进 JVM，所有图片渲染（查在线 / 背包 / 皮肤等）
 * 统一从这里选字体 —— 解决非 Linux/Windows 环境（例如 NAS、Android 容器）没有
 * 中文字体导致渲染出方框的问题（issue #37）。
 *
 * 选字体优先级：用户配置 > 内置字体 > 常见系统中文字体 > SansSerif。
 * 任何失败都只告警并回退，绝不影响插件其余功能。
 */
object FontManager {

    /** jar 内的字体资源路径。 */
    private const val FONT_RESOURCE = "/fonts/HuHoBotSans.ttc"

    /** 解压后的相对路径（相对插件数据目录）。 */
    private const val FONT_TARGET = "fonts/HuHoBotSans.ttc"

    /** 内置字体的 family 名（子集化后保留原名）。 */
    const val BUNDLED_FAMILY = "WenQuanYi Micro Hei"

    private val registered = AtomicBoolean(false)

    @Volatile
    private var bundledReady = false

    /** 启动/重载时调用：解压内置字体并注册到 JVM。 */
    fun initialize(dataFolder: File, logInfo: (String) -> Unit = {}, logWarning: (String) -> Unit = {}) {
        try {
            // 字体内嵌在 jar 中直接注册，不再复制到插件文件夹（减少数据目录文件）
            val resource = FontManager::class.java.getResourceAsStream(FONT_RESOURCE)
            if (resource == null) {
                logWarning("内置字体资源缺失: $FONT_RESOURCE，图片渲染将回退系统字体")
                return
            }
            if (registered.compareAndSet(false, true)) {
                val font = resource.use { Font.createFont(Font.TRUETYPE_FONT, it) }
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
            }
            bundledReady = true
            // 清理旧版本复制到插件目录的字体缓存
            runCatching {
                val legacy = File(dataFolder, FONT_TARGET)
                if (legacy.isFile) legacy.delete()
                legacy.parentFile?.takeIf { it.listFiles()?.isEmpty() == true }?.delete()
            }
            logInfo("内置中文字体已就绪（$BUNDLED_FAMILY），图片渲染不再依赖系统字体")
        } catch (error: Throwable) {
            logWarning("内置字体加载失败，图片渲染将回退系统字体: ${error.message}")
        }
    }

    /** 内置字体是否已注册可用。 */
    fun isBundledReady(): Boolean = bundledReady

    /** 统一选字体：配置 > 内置 > 常见系统中文字体 > SansSerif。 */
    fun chooseFont(configured: String): String {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val cfg = configured.trim()
        if (cfg.isNotEmpty() && available.contains(cfg)) return cfg
        if (bundledReady && available.contains(BUNDLED_FAMILY)) return BUNDLED_FAMILY
        val candidates = arrayOf(
            "Microsoft YaHei", "微软雅黑",
            "Noto Sans CJK SC", "Source Han Sans SC", "思源黑体",
            "WenQuanYi Micro Hei", "文泉驿微米黑",
            "SimSun", "宋体", "NSimSun", "SansSerif"
        )
        for (candidate in candidates) if (available.contains(candidate)) return candidate
        return Font.SANS_SERIF
    }

    /** 按 family + 样式 + 字号派生字体；size 非法时兜底 12。 */
    fun font(family: String, style: Int, size: Int): Font =
        Font(family, style, if (size in 1..200) size else 12)
}
