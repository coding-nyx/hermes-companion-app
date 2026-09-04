package app.hermes.companion.domain

data class WakePing(val type: String, val sessionId: String, val profile: String)

/** ntfy/gotify pings. Payload is type + session_id + profile only — never transcript. */
object WakePolicy {
    const val SCHEME = "hermes-companion"
    val TYPES = setOf("approval.request", "clarify", "run.completed", "error")

    fun parse(raw: String): WakePing? {
        val blob = unwrap(raw)
        val type = field(blob, "type") ?: return null
        if (type !in TYPES) return null
        val session = field(blob, "session_id") ?: return null
        val profile = field(blob, "profile") ?: return null
        return WakePing(type = type, sessionId = session, profile = profile)
    }

    fun sseUrl(topic: String): String? {
        val t = topic.trim().trimEnd('/')
        if (t.isBlank()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null
        return if (t.endsWith("/sse")) t else "$t/sse"
    }

    fun deepLink(sessionId: String, profile: String): String =
        "$SCHEME://open?session=${sessionId.trim()}&profile=${profile.trim()}"

    fun parseDeepLink(uri: String): Pair<String, String>? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("$SCHEME://open")) return null
        val query = trimmed.substringAfter('?', "")
        val parts = query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
        }.toMap()
        val session = parts["session"]?.takeIf { it.isNotBlank() } ?: return null
        val profile = parts["profile"]?.takeIf { it.isNotBlank() } ?: return null
        return session to profile
    }

    private fun unwrap(raw: String): String {
        val message = field(raw, "message") ?: return raw
        return if (message.contains("session_id") || message.contains("\"type\"")) message else raw
    }

    private fun field(raw: String, key: String): String? {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(raw)
        return match?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.takeIf { it.isNotBlank() }
    }
}
