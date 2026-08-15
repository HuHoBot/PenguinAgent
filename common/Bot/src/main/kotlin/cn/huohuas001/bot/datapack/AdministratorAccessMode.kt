package cn.huohuas001.bot.datapack

import cn.huohuas001.bot.provider.AdminMode

/** 机器人管理员的判定方式。 */
enum class AdministratorAccessMode {
    QQ,
    MANUAL,
    BOTH;

    companion object {
        fun fromConfig(mode: AdminMode): AdministratorAccessMode = when (mode) {
            AdminMode.QQ -> QQ
            AdminMode.CONFIG -> MANUAL
            AdminMode.BOTH -> BOTH
        }
    }
}