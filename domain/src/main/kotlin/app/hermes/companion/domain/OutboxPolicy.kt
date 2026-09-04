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

    fun backoffMs(attempts: Int): Long =
        (1_000L shl attempts.coerceIn(0, 5)).coerceAtMost(30_000L)

    fun giveUp(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS
}
