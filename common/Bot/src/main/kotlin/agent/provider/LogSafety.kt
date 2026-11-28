package agent.provider

/** Best-effort redaction of common credentials in SDK diagnostics; never log raw config objects. */
object LogSafety {
    private val value=Regex("""(?i)(["']?(?:access[_-]?token|refresh[_-]?token|authorization|api[_-]?key|app[_-]?secret|secret|password)["']?\s*[:=]\s*["']?)([^"'\s,}&]+)""")
    private val bearer=Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/-]+""")
    fun clean(text:String):String {
        val auth=bearer.replace(text){it.groupValues[1]+"[REDACTED]"}
        return value.replace(auth){it.groupValues[1]+"[REDACTED]"}
    }
}
