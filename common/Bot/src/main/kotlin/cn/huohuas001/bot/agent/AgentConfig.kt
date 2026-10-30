package cn.huohuas001.bot.agent

/**
 * Agent 上下文（对话历史）的管理配置。
 *
 * 对话历史默认只按条数裁剪，一条服务器日志就可能上万字符，实际体积很容易撑爆模型上下文窗口，
 * 因此这里改成按 token 数管理：压缩把中间的历史换成一份摘要，截断直接丢掉最早的消息。
 */
class AgentContextConfig(
    /** 压缩后的目标 token 数，同时作为压缩结果的校验上限 */
    val compressTarget: Int,
    /** 压缩时原样保留的最近消息条数 */
    val compressKeep: Int,
    /** 压缩用的提示词，留空则用 [AgentPrompts.CONTEXT_COMPRESS] */
    val compressPrompt: String,
    /** /删除信息 默认删除的条数 */
    val truncateDrop: Int
)

/** AI Agent 的强类型配置。 */
class AgentConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val commandMode: AgentCommandMode,
    /** 深度思考：原生推理模型用，开启后才向接口附加思考参数（开启思考引导后不生效） */
    val think: Boolean,
    /** 思考程度档位：none/minimal/low/medium/high/xhigh/max，或整数 token 预算 */
    val thinkLevel: String,
    /** 思考参数名，按模型厂商选择 */
    val thinkParam: String,
    /** 思考过程往哪儿送：只写日志 / 只发群 / 都发 */
    val thinkVisibility: AgentThinkVisibility,
    /** 思考引导：非推理模型用提示词加 [思考] 模拟深度思考；与深度思考互斥，开启后深度思考相关设置不生效 */
    val thinkGuide: Boolean,
    /** 思考引导提示词，留空则用 [AgentPrompts.THINK_GUIDE] */
    val thinkGuidePrompt: String,
    /** 上下文压缩相关配置 */
    val context: AgentContextConfig
) {
    /** 是否具备调用 AI 的前提条件（已启用且配置了接口地址与密钥）。 */
    val usable: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /**
     * 是否真的向接口附加原生思考参数。
     *
     * 深度思考（原生推理模型）与思考引导（提示词模拟）互斥：一旦开启思考引导，
     * 深度思考相关设置（开关 / 推理设置 / 参数名）全部不生效，避免两种思考方式叠加。
     */
    val effectiveThink: Boolean
        get() = think && !thinkGuide

    /** 实际使用的思考引导提示词。 */
    val effectiveThinkGuidePrompt: String
        get() = thinkGuidePrompt.trim().ifEmpty { AgentPrompts.THINK_GUIDE }

    /** 实际使用的压缩提示词；{target} 会被替换成目标 token 数。 */
    fun effectiveCompressPrompt(): String {
        val template = context.compressPrompt.trim().ifEmpty { AgentPrompts.CONTEXT_COMPRESS }
        return template.replace("{target}", context.compressTarget.toString())
    }
}
