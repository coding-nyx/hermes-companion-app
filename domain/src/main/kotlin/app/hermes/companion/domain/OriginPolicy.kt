package app.hermes.companion.domain

/** Phone origin must be a host the device can reach. Loopback is this phone, not the lab. */
object OriginPolicy {
    const val RELAY_PORT = 9120
    const val EMPTY_HINT = "Hermes Tailscale URL, port 9120 (companion plugin)."
    const val LOOPBACK_HINT =
        "127.0.0.1 is this phone. Use the Hermes Tailscale URL (companion plugin :9120)."

    fun host(origin: String): String {
        val rest = origin.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("ws://")
            .removePrefix("wss://")
        return rest.substringBefore('/').substringBefore(':').lowercase()
    }

    fun loopback(origin: String): Boolean =
        host(origin) in setOf("127.0.0.1", "localhost", "::1", "0.0.0.0", "[::1]")

    fun hint(origin: String): String? {
        if (origin.isBlank()) return EMPTY_HINT
        return if (loopback(origin)) LOOPBACK_HINT else null
    }
}
