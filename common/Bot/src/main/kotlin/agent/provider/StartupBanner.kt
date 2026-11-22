package agent.provider

/** Log-friendly, fixed-width HuHoBot wordmark, shared by the server and proxy adapters. */
object StartupBanner {
    private val glyphs = mapOf(
        'H' to listOf("#   #", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"),
        'u' to listOf("     ", "     ", "#   #", "#   #", "#   #", "#  ##", " ## #"),
        'o' to listOf("     ", "     ", " ### ", "#   #", "#   #", "#   #", " ### "),
        'B' to listOf("#### ", "#   #", "#   #", "#### ", "#   #", "#   #", "#### "),
        't' to listOf("  #  ", "  #  ", "#####", "  #  ", "  #  ", "  #  ", "   ##")
    )
    private const val NAME = "HuHoBot"

    fun lines(version: String, adapter: String, reloading: Boolean): List<String> {
        val rows = (0 until 7).map { row -> NAME.map { glyphs.getValue(it)[row] }.joinToString(" ").replace('#', '█') }
        val line = "+" + "-".repeat(rows.first().length + 4) + "+"
        val action = if (reloading) "重载" else "启动"
        return listOf(line) + rows.map { "|  $it  |" } + listOf(line, "HuHoBot Penguin v$version · $adapter · $action")
    }
}
