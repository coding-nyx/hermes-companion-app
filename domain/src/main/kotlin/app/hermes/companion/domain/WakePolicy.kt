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

/** Parsed `hermes-companion://pair` link. Origin and code required. */
data class PairDeepLink(val origin: String, val code: String, val profile: String = "default")

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

    /** An external topic publisher cannot change the host of a trusted notification tap. */
    fun parseForSubscription(raw: String, subscribedOrigin: String): WakePing? {
        if (subscribedOrigin.isBlank()) return null
        return parse(raw, subscribedOrigin)?.copy(origin = subscribedOrigin.trim().trimEnd('/'))
    }

    /** Notification ownership includes the reverse-proxy path, unlike fleet/display host keys. */
    fun topicOwnerKey(origin: String): String? = runCatching {
        val trimmed = origin.trim()
        require(trimmed.isNotEmpty())
        // Parse before removing trailing path slashes: "https://" must not become "http://https".
        val uri = java.net.URI(if ("://" in trimmed) trimmed else "http://$trimmed").normalize()
        val scheme = uri.scheme?.lowercase()
        require(scheme in setOf("http", "https") && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val port = uri.port.takeUnless { it == -1 || scheme == "http" && it == 80 || scheme == "https" && it == 443 }
        "$scheme://${uri.host.lowercase()}${port?.let { ":$it" }.orEmpty()}${uri.rawPath.orEmpty().trimEnd('/')}"
    }.getOrNull()

    /** Only explicitly saved exact-owner topics may subscribe; never guess a legacy path owner. */
    fun subscriptionTargets(
        storedTopics: Map<String, String>,
        knownOrigins: List<String>,
        activeOrigin: String?,
    ): Map<String, String> {
        val known = (knownOrigins + listOfNotNull(activeOrigin)).mapNotNull(::topicOwnerKey).toSet()
        return buildMap {
            for ((stored, topic) in storedTopics) {
                if (topic.isBlank()) continue
                val key = topicOwnerKey(stored) ?: continue
                if (key in known) put(key, topic)
            }
        }
    }

    /** Short human label for a wake notification title. */
    fun label(type: String): String = when (type) {
        "room.quiet" -> "room went quiet"
        "room.paused" -> "room paused"
        "room.approval" -> "room needs approval"
        "room.mention" -> "you were mentioned"
        else -> type
    }

    fun sseUrl(topic: String): String? {
        val t = topic.trim()
        val uri = runCatching { java.net.URI(t) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        // Append to the path, not to a filter/auth query; keep existing percent-encoding intact.
        val withoutFragment = t.substringBefore('#')
        val address = withoutFragment.substringBefore('?').trimEnd('/')
        val query = if ('?' in withoutFragment) "?" + withoutFragment.substringAfter('?') else ""
        return (if (address.endsWith("/sse")) address else "$address/sse") + query
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

    fun pairDeepLink(origin: String, code: String, profile: String = "default"): String =
        "$SCHEME://pair?origin=${enc(origin.trim())}&code=${enc(code.trim().uppercase())}&profile=${enc(profile.trim())}"

    fun parsePairDeepLink(uri: String): PairDeepLink? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("$SCHEME://pair")) return null
        val query = trimmed.substringAfter('?', "")
        val parts = query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to dec(part.substring(i + 1))
        }.toMap()
        val code = parts["code"]?.takeIf { it.isNotBlank() } ?: return null
        val origin = parts["origin"]?.takeIf { it.isNotBlank() } ?: parts["host"]?.takeIf { it.isNotBlank() } ?: return null
        val profile = parts["profile"]?.takeIf { it.isNotBlank() } ?: "default"
        return PairDeepLink(origin = origin.trim(), code = code.trim().uppercase(), profile = profile.trim())
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
