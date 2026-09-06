package app.hermes.companion.domain

/**
 * Denylist-only forward filter for the live notification stream (A13.2).
 * Built-in protected packages ∪ custom sticky rules ∪ companion self package / FGS channels.
 */
object NotificationStreamPolicy {
    /** Channels Companion posts that must never echo into the stream. */
    val SELF_CHANNELS = setOf("stay", "hands", "wake", "hermes_voice_channel", "notif-stream")

    fun shouldForward(
        packageName: String,
        extraProtected: Set<String> = emptySet(),
        selfPackage: String = "app.hermes.companion",
        channelId: String? = null,
        ongoing: Boolean = false,
    ): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isBlank()) return false
        if (pkg == selfPackage.trim().lowercase()) return false
        if (!channelId.isNullOrBlank() && channelId in SELF_CHANNELS) return false
        // Companion FGS / service notifs are always ongoing on our channels; belt-and-suspenders.
        if (ongoing && pkg == selfPackage.trim().lowercase()) return false
        return !DeviceLanePolicy.isProtected(pkg, extraProtected)
    }
}
