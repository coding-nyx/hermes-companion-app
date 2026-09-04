package app.hermes.companion.data.local

/**
 * Host identity for per-host storage (A8.5): the normalised origin `scheme://host:port`.
 * Lower-cased, default ports dropped, trailing slash and paths removed.
 */
object HostKeys {
    fun of(origin: String): String {
        val trimmed = origin.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        val rest = withScheme.substringAfter("://").substringBefore('/').substringAfterLast('@')
        val hostPort = if (rest.startsWith("[")) {
            val host = rest.substringBefore(']') + "]"
            val port = rest.substringAfter(']').removePrefix(":")
            host.lowercase() to port
        } else {
            rest.substringBefore(':').lowercase() to rest.substringAfter(':', "")
        }
        val (host, port) = hostPort
        if (host.isBlank()) return ""
        val defaultPort = (scheme == "http" && port == "80") || (scheme == "https" && port == "443")
        return if (port.isBlank() || defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** Bare host (no scheme / port) for display fallbacks. */
    fun hostOf(origin: String): String {
        val key = of(origin)
        val rest = key.substringAfter("://", "")
        return if (rest.startsWith("[")) rest.substringBefore(']') + "]" else rest.substringBefore(':')
    }

    fun same(a: String, b: String): Boolean = of(a).isNotBlank() && of(a) == of(b)
}
