package agent.tools

/** Removes Minecraft §/& formatting so QQ readers never see raw colour tokens. */
object McColours {
    private val legacy = Regex("[§&][0-9a-fk-orA-FK-OR]")
    private val hex = Regex("[§&]x(?:[§&][0-9a-fA-F]){6}")

    fun strip(text: String): String = if ('§' !in text && '&' !in text) text
    else legacy.replace(hex.replace(text, ""), "")
}
