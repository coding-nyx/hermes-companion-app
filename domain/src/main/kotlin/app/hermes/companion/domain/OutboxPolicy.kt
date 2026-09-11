package app.hermes.companion.domain

enum class SendFate { BUSY, OFFLINE, FAILED }

object OutboxPolicy {
    const val MAX_ATTEMPTS = 5

    fun classify(code: String, message: String): SendFate {
        if (isBusy(code, message)) return SendFate.BUSY
        if (isOffline(code, message)) return SendFate.OFFLINE
        return SendFate.FAILED
    }

    fun isBusy(code: String, message: String): Boolean {
        val c = code.lowercase()
        val m = message.lowercase()
        return c.endsWith("409") || c.endsWith("4009") || "4009" in c ||
            "session busy" in m || m == "busy" || " busy" in m
    }

    fun isOffline(code: String, message: String): Boolean {
        val c = code.lowercase()
        val m = message.lowercase()
        return c == "ws_closed" || c == "ws_failed" || c == "ws_send" ||
            c.startsWith("ws_") || c.startsWith("http_5") || c == "http_408" ||
            "timeout" in m || "failed to connect" in m || "unable to resolve" in m ||
            "unknownhost" in m || "connectexception" in m || "sockettimeoutexception" in m
    }

    /**
     * How far back to look for a retried message's echo. A just-submitted turn lands at the end
     * of the transcript, so a narrow window keeps an identical message the user sent long ago
     * from being mistaken for this one's echo.
     */
    const val RECONCILE_TAIL = 12

    /**
     * Which pending items already landed on the host, so a retry does not send them twice.
     *
     * The transport is at-least-once and carries no client key (see `client_id`, the real fix):
     * when a stream drops mid-turn the client cannot tell "the host never got it" from "the host
     * got it and is answering". Re-submitting on that guess is what produces duplicate user
     * messages and duplicate agent turns.
     *
     * Matching is by text, one-for-one, against the tail of the transcript, and **only for items
     * that have already failed at least once**. A first attempt is never suppressed, so the
     * failure mode of this heuristic is an occasional duplicate on retry — never a silently
     * dropped message the user believes they sent.
     *
     * @param pending id, text and attempt count per queued item, oldest first.
     * @param serverUserTexts USER message texts from the host, oldest first.
     */
    /**
     * @param requireAttempt when true (the default) only items that already failed are checked,
     *   so a blind first send is never suppressed. Pass false when the caller *knows* the turn
     *   was submitted — a cancelled stream, for instance — because there the question is not
     *   "did it maybe arrive" but "the host has it, is it in the transcript yet".
     */
    fun landedIds(
        pending: List<Triple<String, String, Int>>,
        serverUserTexts: List<String>,
        requireAttempt: Boolean = true,
    ): Set<String> {
        if (pending.isEmpty() || serverUserTexts.isEmpty()) return emptySet()
        val window = serverUserTexts.takeLast(RECONCILE_TAIL).toMutableList()
        val landed = LinkedHashSet<String>()
        for ((id, text, attempts) in pending) {
            if (requireAttempt && attempts <= 0) continue // never second-guess a blind first send
            val key = text.trim()
            if (key.isEmpty()) continue
            val at = window.indexOfFirst { it.trim() == key }
            if (at >= 0) {
                window.removeAt(at) // consume, so two identical pendings need two echoes
                landed.add(id)
            }
        }
        return landed
    }

    /**
     * Whether an item has exhausted its retries and should stop blocking the queue.
     *
     * Previously only the BUSY path consulted [giveUp]; an OFFLINE item retried forever, and
     * because the flush loop always took the oldest pending item and returned on failure, one
     * wedged item stopped every later message from being sent at all.
     */
    fun exhausted(attempts: Int): Boolean = giveUp(attempts)

    fun backoffMs(attempts: Int): Long =
        (1_000L shl attempts.coerceIn(0, 5)).coerceAtMost(30_000L)

    fun giveUp(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS
}
