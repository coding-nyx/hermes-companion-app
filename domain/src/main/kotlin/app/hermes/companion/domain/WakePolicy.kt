package app.hermes.companion.domain

/** [origin] is the host the ping belongs to: from the payload (`origin` / `host`) or the topic it arrived on. */
data class WakePing(
    val type: String,
    val sessionId: String,
    val profile: String,
    val origin: String = "",
    /** Room pings (A22.5) carry the room instead of a thread. */
    val roomId: String = "",
    val title: String = "",
)

/** Parsed `hermes-companion://open` link. [origin] empty = "whatever host is active" (pre-A8.5 links). */
data class DeepLink(val sessionId: String, val profile: String, val origin: String = "", val roomId: String = "")

/** ntfy/gotify pings. Payload is type + session_id + profile only — never transcript. */
object WakePolicy {
    const val SCHEME = "hermes-companion"
    val TYPES = setOf("approval.request", "clarify", "run.completed", "error")
    val ROOM_TYPES = setOf("room.quiet", "room.paused", "room.approval", "room.mention")

    fun parse(raw: String, origin: String = ""): WakePing? {
        val blob = unwrap(raw)
        val type = field(blob, "type") ?: return null
        if (type !in TYPES && type !in ROOM_TYPES) return null
        val session = field(blob, "session_id") ?: return null
        val profile = field(blob, "profile") ?: return null
        val host = field(blob, "origin") ?: field(blob, "host") ?: origin
        val room = if (type in ROOM_TYPES) (field(blob, "room_id") ?: session) else ""
        return WakePing(type = type, sessionId = session, profile = profile, origin = host.trim(), roomId = room,
            title = field(blob, "title").orEmpty())
    }

    fun isRoom(type: String): Boolean = type in ROOM_TYPES

    /** Short human label for a wake notification title. */
    fun label(type: String): String = when (type) {
        "room.quiet" -> "room went quiet"
        "room.paused" -> "room paused"
        "room.approval" -> "room needs approval"
        "room.mention" -> "you were mentioned"
        else -> type
    }

    fun sseUrl(topic: String): String? {
        val t = topic.trim().trimEnd('/')
        if (t.isBlank()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null
        return if (t.endsWith("/sse")) t else "$t/sse"
    }

    fun deepLink(sessionId: String, profile: String, origin: String = "", roomId: String = ""): String {
        var base = "$SCHEME://open?session=${enc(sessionId.trim())}&profile=${enc(profile.trim())}"
        if (roomId.isNotBlank()) base += "&room=${enc(roomId.trim())}"
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
        return DeepLink(sessionId = session, profile = profile, origin = parts["host"].orEmpty().trim(), roomId = parts["room"].orEmpty().trim())
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
