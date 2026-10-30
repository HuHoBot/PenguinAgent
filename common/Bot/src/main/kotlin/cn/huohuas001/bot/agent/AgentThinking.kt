package cn.huohuas001.bot.agent

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject

/**
 * 深度思考的请求侧适配。
 *
 * 各家模型厂商开启推理的方式差别很大：有的用 `reasoning_effort` 字符串档位，
 * 有的用 `thinking.budget_tokens` 整数预算，有的只要一个 `enable_thinking` 开关。
 * 这里把「思考程度 + 参数名」翻译成请求体里真正要加的字段，档位值写错时返回一句
 * 人类可读的提示交给调用方记日志，而不是让接口直接报 400。
 */
object AgentThinking {

    /** 会被当成"关闭思考"的档位写法。 */
    private val OFF_LEVELS = setOf("none", "off", "no", "false", "disabled", "disable", "0", "关闭", "关")

    /** 字符串档位，各家推理模型基本都只用这几个值。 */
    private val NAMED_LEVELS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    /**
     * 会被识别为思考块的标签对，按厂商差异支持多种写法。
     *
     * 内置提示词用 `[思考]` / `[/思考]`：部分厂商的接口会把 `<think>` / `<thinking>`
     * 当成特殊标记直接剥掉（例如 AgnesAI），方括号标记则原样保留。英文标签仍保留兼容，
     * 方便沿用旧自定义提示词。
     */
    private val THINK_PATTERNS = listOf(
        Regex("\\[思考\\](.*?)\\[/思考\\]", RegexOption.DOT_MATCHES_ALL),
        Regex("<thinking>(.*?)</thinking>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        Regex("<think>(.*?)</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    )

    /**
     * 按参数名与档位写入思考相关字段。
     *
     * @param level 档位：none/minimal/low/medium/high/xhigh/max，或整数 token 预算，或 true/false
     * @param param 参数名：reasoning_effort / effort / thinking_level / reasoning.effort /
     *              thinking.type / thinking_budget / enable_thinking
     * @return 无法表达时的提示文案；正常写入返回 null
     */
    fun applyRequest(body: JSONObject, level: String, param: String): String? {
        val raw = level.trim()
        if (raw.isEmpty()) return null
        val normalized = normalizeLevel(raw)
        val isOff = normalized in OFF_LEVELS
        val budget = normalized.toIntOrNull()
        val boolean = when (normalized) {
            "true", "on", "yes", "enabled", "enable", "开" -> true
            "false", "off", "no", "disabled", "disable", "关" -> false
            else -> null
        }
        // 档位归一到三选一：字符串档位 / 整数 token 预算 / 开关值；"关"的写法一律落到 none
        val named = NAMED_LEVELS.firstOrNull { it == normalized }
            ?: boolean?.let { if (it) "medium" else "none" }
            ?: if (isOff) "none" else null
        val namedHint = NAMED_LEVELS.joinToString("/")
        val namedMismatch = "思考程度 $raw 无法用于参数 $param（该参数只接受 $namedHint），已忽略"

        // 参数名归一化：忽略大小写与 - _ 分隔，便于用户随手写成 thinkingBudget / thinking-budget
        return when (param.trim().lowercase().replace("-", "").replace("_", "")) {
            "reasoningeffort", "effort" -> {
                if (named == null) {
                    namedMismatch
                } else {
                    body.put("reasoning_effort", named)
                    null
                }
            }

            "thinkinglevel" -> {
                if (named == null) {
                    namedMismatch
                } else {
                    body.put("thinking_level", named)
                    null
                }
            }

            "reasoning.effort" -> {
                if (named == null) {
                    namedMismatch
                } else {
                    val reasoning = JSONObject()
                    reasoning.put("effort", named)
                    body.put("reasoning", reasoning)
                    null
                }
            }

            "thinking.type" -> when {
                budget != null -> {
                    val thinking = JSONObject()
                    thinking.put("type", "enabled")
                    thinking.put("budget_tokens", budget)
                    body.put("thinking", thinking)
                    null
                }

                named != null -> {
                    // Anthropic 风格只认 type，medium 之类的档位统一映射成开启/关闭
                    val thinking = JSONObject()
                    thinking.put("type", if (named == "none") "disabled" else "enabled")
                    body.put("thinking", thinking)
                    null
                }

                else -> "思考程度 $raw 无法用于参数 $param，请填写档位或整数 token 预算，已忽略"
            }

            "thinkingbudget" -> when {
                budget != null -> {
                    body.put("thinking_budget", budget)
                    null
                }

                isOff -> {
                    body.put("thinking_budget", 0)
                    null
                }

                else -> "思考程度 $raw 无法用于参数 $param（该参数只接受整数 token 预算），已忽略"
            }

            "enablethinking" -> {
                body.put("enable_thinking", if (isOff) false else (boolean ?: true))
                null
            }

            else -> "未知的思考参数名 $param，本次未附加思考参数（可选：reasoning_effort、effort、thinking_level、reasoning.effort、thinking.type、thinking_budget、enable_thinking）"
        }
    }

    /**
     * 拆出正文里的思考块。
     *
     * 思考引导会让非推理模型把分析写进标签里，这里把标签内的内容取出来当思考过程、
     * 从正文中剔除，标签之外的内容才是给用户看的答复。
     *
     * 支持多种标签写法：部分厂商的接口会把 `<think>` / `<thinking>` 当成特殊标记直接
     * 剥掉（例如 AgnesAI），所以内置提示词改用 `[思考]` / `[/思考]`，
     * 同时保留对英文标签的兼容，方便用户自定义提示词。
     *
     * 只有出现闭合标签时才剥离——没闭合说明模型根本没写完，贸然截断会把答复一起丢掉。
     *
     * @return 第一个元素是思考内容（没有则为 null），第二个元素是去掉思考块后的正文
     */
    fun splitThinkBlocks(content: String?): Pair<String?, String> {
        val text = content?.takeIf { it.isNotBlank() } ?: return null to content.orEmpty()
        val blocks = ArrayList<String>()
        var visible = text
        for (pattern in THINK_PATTERNS) {
            val matches = pattern.findAll(visible).toList()
            if (matches.isEmpty()) continue
            matches.map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .forEach { blocks.add(it) }
            visible = pattern.replace(visible, "")
        }
        return blocks.joinToString("\n\n").takeIf { it.isNotEmpty() } to visible.trim()
    }

    /** 档位归一化：去空格、转小写，中文开关写法一并识别。 */
    private fun normalizeLevel(level: String): String {
        val text = level.trim().lowercase()
        return when (text) {
            "开", "开启", "启用" -> "true"
            "关", "关闭", "禁用" -> "false"
            else -> text
        }
    }

    /** 引导注入用：把思考引导提示词追加到 system 消息末尾，返回新的消息列表。 */
    fun withGuide(messages: List<JSONObject>, guidePrompt: String): List<JSONObject> {
        if (guidePrompt.isBlank()) return messages
        val result = ArrayList<JSONObject>(messages.size + 1)
        var injected = false
        for (message in messages) {
            if (!injected && message.getString("role") == "system") {
                val copy = JSON.parseObject(message.toJSONString())
                val origin = copy.getString("content").orEmpty()
                copy.put("content", origin.trimEnd() + "\n\n" + guidePrompt.trim())
                result.add(copy)
                injected = true
            } else {
                result.add(message)
            }
        }
        if (!injected) {
            val system = JSONObject()
            system.put("role", "system")
            system.put("content", guidePrompt.trim())
            result.add(0, system)
        }
        return result
    }
}
