package agent.spigot.commands

/** Immutable list of paths the server actually routes to HuHoBot. No alias is advertised speculatively. */
internal data class GameCommandRoutes(val root: String = "huhobot", val handover: Set<String> = emptySet()) {
    fun path(command: String): String = when (command) {
        "huhobot", "hb" -> "/$root"
        in handover -> "/$root $command"
        else -> "/$command"
    }

    companion object {
        val movable = listOf("at", "send", "qqbind")
        fun resolve(ownsShort: (String) -> Boolean, fallback: String): GameCommandRoutes =
            GameCommandRoutes(if (ownsShort("huhobot")) "huhobot" else fallback, movable.filterNot(ownsShort).toSet())
    }
}
