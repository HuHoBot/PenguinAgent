package cn.huohuas001.bot.tools

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/**
 * MiniMessage 与传统颜色码的转换。
 *
 * 配置文件里允许两种写法：
 * - MiniMessage：`<gold>`、`<#FFAA00>`、`<bold>`、`</gold>`
 * - 传统颜色码：`&6`、`§6`
 *
 * 统一转成 Minecraft 原生的 `§` 颜色码后再交给服务端 / 代理端渲染。
 * 解析失败时原样返回，避免一个写错的标签把整条消息吞掉。
 */
object MiniMessageSupport {

    /** 私有区字符，用来暂时保护文本里已有的 § 颜色码，避免被 MiniMessage 当成非法输入。 */
    private const val LEGACY_GUARD = '\uE000'

    private val miniMessage: MiniMessage = MiniMessage.builder()
        .strict(false)
        .build()

    /**
     * 十六进制颜色用 `§x§R§R§G§G§B§B` 格式输出。
     * 本插件最低支持 1.18，原版客户端可以直接识别；不开启时 `<#FFAA00>` 会被降级成最接近的命名颜色。
     */
    private val legacySerializer: LegacyComponentSerializer = LegacyComponentSerializer.builder()
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()

    /**
     * 把 MiniMessage 标签转换成 `§` 颜色码。
     *
     * 文本里没有 `<` 时直接返回，避免普通聊天被误解析；
     * 文本里同时存在 `§6` 这类旧颜色码时先临时保护，解析完再还原。
     */
    fun toLegacy(text: String): String {
        if (text.isEmpty() || !text.contains('<')) return text
        val hasLegacyCodes = text.indexOf('§') >= 0
        val prepared = if (hasLegacyCodes) text.replace('§', LEGACY_GUARD) else text
        val converted = runCatching {
            legacySerializer.serialize(miniMessage.deserialize(prepared))
        }.getOrElse {
            return text
        }
        return if (hasLegacyCodes) converted.replace(LEGACY_GUARD, '§') else converted
    }

    /**
     * 转义文本中的 MiniMessage 标签。
     *
     * 用于把 QQ 用户消息、玩家名等不可信内容塞进配置模板前做处理，
     * 否则用户发一句 `<red>你好` 就会把后面的聊天内容全部染色。
     */
    fun escape(text: String): String =
        if (text.contains('<')) miniMessage.escapeTags(text) else text
}
