package app.hermes.companion.domain

/**
 * Denylist-only forward filter for the live notification stream (A13.2).
 *
 * Blocks:
 * - Companion self package / FGS channels (self-echo always blocked)
 * - Built-in [STREAM_SUPPRESS_PACKAGES] (Telegram clients — stream/wake echo guard only)
 * - Custom sticky rules via [DeviceLanePolicy.isProtected] extras (Hands denylist reuse)
 *
 * Does **not** put Telegram into Hands [DeviceLanePolicy.PROTECTED_PACKAGES] (keep empty).
 *
 * Mirror: hermes-plugin/agent_wake.py `STREAM_SUPPRESS_PACKAGES` / wake skip default —
 * keep both lists in sync when adding clients.
 */
object NotificationStreamPolicy {
    /** Channels Companion posts that must never echo into the stream. */
    val SELF_CHANNELS = setOf("stay", "hands", "wake", "hermes_voice_channel", "notif-stream")

    /**
     * Built-in packages muted from the live notification stream (and aligned with
     * agent wake skip). Stream-only — Hands may still control these apps.
     */
    val STREAM_SUPPRESS_PACKAGES: Set<String> = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.thunderdog.challegram", // Telegram X
    )

    fun isStreamSuppressed(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isBlank()) return false
        return STREAM_SUPPRESS_PACKAGES.any { DeviceLanePolicy.matchesRule(pkg, it) }
    }

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
        if (isStreamSuppressed(pkg)) return false
        return !DeviceLanePolicy.isProtected(pkg, extraProtected)
    }
}
