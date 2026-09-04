package app.hermes.companion.domain

/** [origin] is the host the ping belongs to: from the payload (`origin` / `host`) or the topic it arrived on. */
data class WakePing(val type: String, val sessionId: String, val profile: String, val origin: String = "")

/** Parsed `hermes-companion://open` link. [origin] empty = "whatever host is active" (pre-A8.5 links). */
data class DeepLink(val sessionId: String, val profile: String, val origin: String = "")

/** ntfy/gotify pings. Payload is type + session_id + profile only — never transcript. */
object WakePolicy {
    const val SCHEME = "hermes-companion"
    val TYPES = setOf("approval.request", "clarify", "run.completed", "error")

    fun parse(raw: String, origin: String = ""): WakePing? {
        val blob = unwrap(raw)
        val type = field(blob, "type") ?: return null
        if (type !in TYPES) return null
        val session = field(blob, "session_id") ?: return null
        val profile = field(blob, "profile") ?: return null
        val host = field(blob, "origin") ?: field(blob, "host") ?: origin
        return WakePing(type = type, sessionId = session, profile = profile, origin = host.trim())
    }

    fun sseUrl(topic: String): String? {
        val t = topic.trim().trimEnd('/')
        if (t.isBlank()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null
        return if (t.endsWith("/sse")) t else "$t/sse"
    }

    fun deepLink(sessionId: String, profile: String, origin: String = ""): String {
        val base = "$SCHEME://open?session=${enc(sessionId.trim())}&profile=${enc(profile.trim())}"
        return if (origin.isBlank()) base else "$base&host=${enc(origin.trim())}"
    }

    fun parseDeepLink(uri: String): DeepLink? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("$SCHEME://open")) return null
        val query = trimmed.substringAfter('?', "")
        val parts = query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to dec(part.substring(i + 1))
        }.toMap()
        val session = parts["session"]?.takeIf { it.isNotBlank() } ?: return null
        val profile = parts["profile"]?.takeIf { it.isNotBlank() } ?: return null
        return DeepLink(sessionId = session, profile = profile, origin = parts["host"].orEmpty().trim())
    }

    private fun enc(v: String): String =
        java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun dec(v: String): String =
        runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)

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
