package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import java.awt.Color
import java.time.LocalDate

/**
 * 内置节日 / 事件主题：命中的日期会在查在线图片顶部显示节日横幅，
 * 让特殊日子（春节、中秋、国庆、圣诞、以及 APEC China 2026 等大型事件）更有氛围。
 *
 * 纯内置、零依赖；将来可接 CloudFlare Worker 下发自定义事件与横幅。
 */
object EventTheme {

    /** 节日配色：命中事件时整张图切换成该风格。 */
    data class Palette(
        val bg1: Color, val bg2: Color, val bg3: Color,
        val accent: Color, val accent2: Color
    )

    data class Event(
        val id: String,
        val banner: String,
        /** 起止日期（含首尾）。 */
        val start: LocalDate,
        val end: LocalDate,
        /** 可选节日配色（null = 使用默认主题色）。 */
        val palette: Palette? = null
    )

    private val CHINA_RED = Palette(
        bg1 = Color(28, 6, 9), bg2 = Color(58, 10, 16), bg3 = Color(18, 4, 6),
        accent = Color(232, 61, 47), accent2 = Color(255, 210, 74)
    )
    private val SPRING_FESTIVAL = Palette(
        bg1 = Color(38, 6, 10), bg2 = Color(80, 14, 18), bg3 = Color(24, 4, 6),
        accent = Color(240, 80, 60), accent2 = Color(255, 214, 92)
    )
    private val MID_AUTUMN = Palette(
        bg1 = Color(24, 18, 8), bg2 = Color(52, 36, 12), bg3 = Color(16, 12, 6),
        accent = Color(240, 170, 62), accent2 = Color(255, 236, 170)
    )
    private val CHRISTMAS = Palette(
        bg1 = Color(10, 24, 16), bg2 = Color(16, 44, 28), bg3 = Color(8, 16, 10),
        accent = Color(72, 190, 120), accent2 = Color(232, 76, 84)
    )
    private val HALLOWEEN = Palette(
        bg1 = Color(22, 10, 28), bg2 = Color(44, 18, 52), bg3 = Color(14, 6, 18),
        accent = Color(240, 138, 36), accent2 = Color(150, 90, 210)
    )

    private val EVENTS: List<Event> = listOf(
        // 大型国际事件（示例：APEC China 2026，深圳）
        Event("apec2026", "🌏 APEC China 2026 · 深圳 · 11.18-11.19", LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 19), CHINA_RED),
        Event("newyear26", "🎉 元旦快乐", LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 2), CHINA_RED),
        Event("newyear", "🎉 元旦快乐", LocalDate.of(2027, 12, 31), LocalDate.of(2028, 1, 2), CHINA_RED),
        Event("labor", "🛠 劳动节快乐", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5), CHINA_RED),
        Event("national", "🇨🇳 国庆快乐", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), CHINA_RED),
        Event("christmas", "🎄 圣诞快乐", LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 26), CHRISTMAS),
        Event("halloween", "🎃 万圣节快乐", LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31), HALLOWEEN),
        Event("cny26", "🧧 新春快乐 · 马年大吉", LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 24), SPRING_FESTIVAL),
        Event("cny27", "🧧 新春快乐", LocalDate.of(2027, 2, 6), LocalDate.of(2027, 2, 13), SPRING_FESTIVAL),
        Event("midautumn26", "🥮 中秋快乐", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 25), MID_AUTUMN),
        Event("midautumn27", "🥮 中秋快乐", LocalDate.of(2027, 9, 15), LocalDate.of(2027, 9, 15), MID_AUTUMN)
    )

    /** 今天命中的事件（多个时取最长的事件名）。 */
    fun current(today: LocalDate = LocalDate.now()): Event? =
        EVENTS.filter { !today.isBefore(it.start) && !today.isAfter(it.end) }
            .maxByOrNull { it.banner.length }

    /** 用于配置校验/自检：列出全部内置事件。 */
    fun all(): List<Event> = EVENTS
}
