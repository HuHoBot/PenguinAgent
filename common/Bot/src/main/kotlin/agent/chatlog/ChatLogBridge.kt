package agent.chatlog

/** Platform bridge for recent public-chat images; absent handlers make commands answer honestly. */
object ChatLogBridge {
    @Volatile var handler: ChatLogHandler? = null
    fun available(): Boolean = handler != null
    fun handle(groupOpenId: String, playerFilter: String?, page: Int, reply: ChatLogReply): Boolean {
        val active = handler ?: return false
        return try { active.handle(groupOpenId, playerFilter, page, reply) } catch (_: Throwable) { false }
    }
}

interface ChatLogHandler {
    fun handle(groupOpenId: String, playerFilter: String?, page: Int, reply: ChatLogReply): Boolean
}

interface ChatLogReply {
    fun image(png: ByteArray, caption: String)
    fun text(message: String)
    fun busy()
    fun outOfRange(page: Int, totalPages: Int)
    fun failed(message: String)
}
