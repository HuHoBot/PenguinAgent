package cn.huohuas001.huhobotPenguin.spigot.onlinelist

import java.time.LocalDate
import java.time.Month

/**
 * 内置节日 / 事件主题：命中的日期会在查在线图片顶部显示节日横幅，
 * 让特殊日子（春节、中秋、国庆、圣诞、以及 APEC China 2026 等大型事件）更有氛围。
 *
 * 纯内置、零依赖；将来可接 CloudFlare Worker 下发自定义事件与横幅。
 */
object EventTheme {

    data class Event(
        val id: String,
        val banner: String,
        /** 起止日期（含首尾）。 */
        val start: LocalDate,
        val end: LocalDate
    )

    private val EVENTS: List<Event> = listOf(
        // 大型国际事件（示例：APEC China 2026，深圳）
        Event("apec2026", "🌏 APEC China 2026 · 深圳 · 11.18-11.19", LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 19)),
        Event("newyear26", "🎉 元旦快乐", LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 2)),
        // 固定公历节日
        Event("newyear", "🎉 元旦快乐", LocalDate.of(2027, 12, 31), LocalDate.of(2028, 1, 2)),
        Event("labor", "🛠 劳动节快乐", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5)),
        Event("national", "🇨🇳 国庆快乐", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)),
        Event("christmas", "🎄 圣诞快乐", LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 26)),
        Event("halloween", "🎃 万圣节快乐", LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31)),
        // 农历节日（2026-2028 常用值，逐年补充即可）
        Event("cny26", "🧧 新春快乐 · 马年大吉", LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 24)),
        Event("cny27", "🧧 新春快乐", LocalDate.of(2027, 2, 6), LocalDate.of(2027, 2, 13)),
        Event("midautumn26", "🥮 中秋快乐", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 25)),
        Event("midautumn27", "🥮 中秋快乐", LocalDate.of(2027, 9, 15), LocalDate.of(2027, 9, 15))
    )

    /** 今天命中的事件（多个时取最长的事件名）。 */
    fun current(today: LocalDate = LocalDate.now()): Event? =
        EVENTS.filter { !today.isBefore(it.start) && !today.isAfter(it.end) }
            .maxByOrNull { it.banner.length }

    /** 用于配置校验/自检：列出全部内置事件。 */
    fun all(): List<Event> = EVENTS
}
