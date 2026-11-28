package agent.spigot.manager

/** How this server actually schedules work. Detection is runtime-based, never inferred from plugin.yml. */
enum class ServerMode { PAPER, FOLIA, HYBRID;

    companion object {
        fun parse(raw: String?): ServerMode? = when (raw?.trim()?.lowercase()) {
            "paper", "spigot", "bukkit", "常规", "普通" -> PAPER
            "folia" -> FOLIA
            "hybrid", "mixed", "兼容", "混合" -> HYBRID
            else -> null
        }
    }
}

/**
 * Detects the scheduler flavour from classes that exist on the running server.
 *
 * - Regionised scheduler classes present => FOLIA semantics at minimum.
 * - No regionised classes => PAPER (the plugin still loads on Spigot/Paper; nothing is assumed
 *   from the declarative folia-supported flag).
 * - Hybrid forks cannot be told apart reliably by class probing, so they are selected explicitly
 *   with compatibility.server-mode: hybrid and then use regionised APIs first with a guarded fallback.
 */
object ServerModeDetector {
    const val REGION_CLASS = "io.papermc.paper.threadedregions.RegionizedServer"

    fun detect(classPresent: (String) -> Boolean): ServerMode =
        if (classPresent(REGION_CLASS)) ServerMode.FOLIA else ServerMode.PAPER
}
