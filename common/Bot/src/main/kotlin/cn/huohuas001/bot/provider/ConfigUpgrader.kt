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
}
