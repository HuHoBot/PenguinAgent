package cn.huohuas001.bot.tools

import java.util.UUID

private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[;\d]*[ -/]*[@-~]""")

fun getPackID(): String {
    val guid: UUID = UUID.randomUUID()
    return guid.toString().replace("-", "")
}

fun stripAnsiEscape(text: String): String {
    return text.replace(ANSI_ESCAPE_REGEX, "")
}

fun filterTextByRegex(text: String, patterns: List<String>, removeAnsiEscape: Boolean = false): String {
    val source = if (removeAnsiEscape) stripAnsiEscape(text) else text
    if (patterns.isEmpty()) {
        return source
    }

    var result = source
    for (pattern in patterns) {
        if (pattern.isBlank()) {
            continue
        }
        result = result.replace(Regex(pattern), "")
    }
    return result
}

fun containsRegexMatch(text: String, patterns: List<String>, removeAnsiEscape: Boolean = false): Boolean {
    val source = if (removeAnsiEscape) stripAnsiEscape(text) else text
    return patterns
        .asSequence()
        .filter { it.isNotBlank() }
        .any { Regex(it).containsMatchIn(source) }
}
