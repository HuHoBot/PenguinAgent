package agent.spigot.onlinelist

/** Built-in variables for title/suffix templates; PlaceholderAPI runs afterwards. */
object PlaceholderText {
    fun expand(input: String, name: String, display: String, world: String, ping: Int, server: String, online: Int, max: Int): String =
        input.replace("{name}", name)
            .replace("{display}", display)
            .replace("{world}", world)
            .replace("{ping}", if (ping < 0) "—" else ping.toString())
            .replace("{server}", server)
            .replace("{online}", online.toString())
            .replace("{max}", max.toString())
}
