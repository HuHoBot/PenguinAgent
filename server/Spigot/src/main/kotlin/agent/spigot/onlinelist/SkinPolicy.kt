package agent.spigot.onlinelist

/** Decides whether remote skin lookups are meaningful for this server configuration. */
object SkinPolicy {
    fun remoteLookupsEnabled(configured: Boolean, serverOnlineMode: Boolean, offlineDefaultSkins: Boolean): Boolean =
        configured && !(offlineDefaultSkins && !serverOnlineMode)
}
