package cn.huohuas001.bot.addon

/**
 * 已注册的 HuHoBot 扩展元数据。
 *
 * @param name        扩展名称（唯一标识）
 * @param version     版本号
 * @param description 简要描述
 * @param author      作者
 */
data class Addon(
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = ""
)
