package app.hermes.companion.domain

/** Explicit selection, content minimization and bounded lifetime for notification forwarding. */
object NotificationCapturePolicy {
    const val MAX_AGE_MS = 120_000L
    fun selected(pkg: String, apps: Set<String>, category: String, calls: Boolean): Boolean =
        pkg in apps && (calls || category !in setOf("call", "missed_call"))

    // Conservative best-effort suppression, not a claim to detect every secret or OTP.
    private val sensitive = Regex("(?i)\\b(otp|one[ -]?time|verification|verify|passcode|password|security code|authentication|auth code|2fa)\\b")
    private val code = Regex("\\b[0-9]{4,8}\\b")
    fun content(title: String, text: String, mode: String): Pair<String, String> {
        if (mode == "metadata" || mode !in setOf("title", "full")) return "" to ""
        if (sensitive.containsMatchIn(title + " " + text) || code.containsMatchIn(title + " " + text)) return "" to ""
        return title.take(240) to if (mode == "full") text.take(480) else ""
    }
    fun fresh(capturedAt: Long, now: Long, ttlMs: Long = MAX_AGE_MS): Boolean = now >= capturedAt && now - capturedAt <= ttlMs.coerceIn(MAX_AGE_MS, 3_600_000L)
}
