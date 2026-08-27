package cn.huohuas001.bot.provider

/**
 * 配置文件自动升级系统
 *
 * 各平台 ConfigManager 通过本工具注册"字段路径 → 默认值"映射，
 * 启动加载配置时自动检测并补全缺失字段（如新版本新增的配置项），
 * 同时由各平台维护配置版本号，实现旧配置文件的自动升级。
 */
object ConfigUpgrader {

    /**
     * 自动补全缺失的配置字段
     *
     * @param defaults 字段路径 → 默认值 的映射表
     * @param has 判断字段是否存在的回调
     * @param set 写入字段默认值的回调
     * @return 是否补全了至少一个字段（调用方据此决定保存配置）
     */
    fun fillMissing(
        defaults: Map<String, Any>,
        has: (String) -> Boolean,
        set: (String, Any) -> Unit
    ): Boolean {
        var changed = false
        for ((path, defaultValue) in defaults) {
            if (!has(path)) {
                set(path, defaultValue)
                changed = true
            }
        }
        return changed
    }

    /**
     * 根据版本号升级已有配置项的值
     *
     * @param currentVersion 当前配置文件的版本号
     * @param upgrades 版本升级列表，每个条目包含目标版本和要覆盖的 key→value 映射
     * @param get 获取当前配置值的回调
     * @param set 写入配置值的回调
     * @return 是否修改了至少一个字段
     */
    fun upgradeValues(
        currentVersion: Int,
        upgrades: List<VersionedUpgrade>,
        get: (String) -> Any?,
        set: (String, Any) -> Unit
    ): Boolean {
        var changed = false
        for (upgrade in upgrades) {
            if (currentVersion < upgrade.toVersion) {
                for ((path, newValue) in upgrade.values) {
                    val current = get(path)
                    // 仅在值不同时更新
                    if (current != newValue) {
                        set(path, newValue)
                        changed = true
                    }
                }
            }
        }
        return changed
    }
}

/**
 * 版本化升级条目
 *
 * @property toVersion 目标版本号（当前配置版本 < toVersion 时执行升级）
 * @property values    要覆盖的 key → 新值 映射
 */
data class VersionedUpgrade(
    val toVersion: Int,
    val values: Map<String, Any>
)
