package cn.huohuas001.bot.agent

/** AI Agent 的强类型配置。 */
class AgentConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val commandMode: AgentCommandMode
) {
    /** 是否具备调用 AI 的前提条件（已启用且配置了接口地址与密钥）。 */
    val usable: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}
