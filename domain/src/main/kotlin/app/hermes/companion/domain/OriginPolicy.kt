package app.hermes.companion.domain

/**
 * Phone origin must be a host the device can reach. Loopback is this phone, not the lab.
 *
 * Cleartext (`http://` / `ws://`) is only allowed toward private hosts: loopback, RFC 1918,
 * the CGNAT block Tailscale uses (100.64.0.0/10), link-local, IPv6 ULA and a few LAN-only
 * suffixes. Android's `network_security_config.xml` cannot express IP ranges, so this is the
 * enforcement point; the OkHttp client and the connect flow both consult it.
 */
object OriginPolicy {
    const val RELAY_PORT = 9120
    const val EMPTY_HINT = "Hermes Tailscale URL, port 9120 (companion plugin)."
    const val LOOPBACK_HINT =
        "127.0.0.1 is this phone. Use the Hermes Tailscale URL (companion plugin :9120)."
    const val CLEARTEXT_HINT = "http · cleartext, LAN/Tailscale only"
    const val CLEARTEXT_DENIED =
        "cleartext_denied · http:// only for LAN/Tailscale hosts. Use https:// for public hosts."

    private val PRIVATE_SUFFIXES = listOf(".local", ".ts.net", ".internal", ".lan", ".home.arpa", ".localdomain")
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0.0.0.0", "[::1]")

    /** OkHttp needs a scheme. Bare `host:9120` becomes `http://host:9120`. */
    fun canonicalize(origin: String): String {
        val trimmed = origin.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return if ("://" in trimmed) trimmed else "http://$trimmed"
    }

    fun host(origin: String): String {
        val rest = origin.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("ws://")
            .removePrefix("wss://")
            .substringBefore('/')
        val noUser = rest.substringAfterLast('@')
        return if (noUser.startsWith("[")) {
            noUser.substringBefore(']').removePrefix("[").lowercase()
        } else {
            noUser.substringBefore(':').lowercase()
        }
    }

    fun loopback(origin: String): Boolean = host(origin) in LOOPBACK_HOSTS

    fun isCleartext(origin: String): Boolean {
        val o = origin.trim().lowercase()
        return o.startsWith("http://") || o.startsWith("ws://")
    }

    /** True for hosts that never leave the LAN / tailnet. */
    fun privateHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h.isBlank()) return false
        if (h in LOOPBACK_HOSTS) return true
        ipv4(h)?.let { return privateV4(it) }
        if (h.contains(':')) return privateV6(h)
        if (PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
        // Single-label hostnames only resolve on the local network (mDNS / LAN DNS).
        return !h.contains('.')
    }

    /** https/wss always allowed; http/ws only to a private host. */
    fun cleartextAllowed(origin: String): Boolean {
        if (!isCleartext(origin)) return true
        return privateHost(host(origin))
    }

    fun hint(origin: String): String? {
        if (origin.isBlank()) return EMPTY_HINT
        if (loopback(origin)) return LOOPBACK_HINT
        if (isCleartext(origin) && host(origin).isNotBlank()) {
            return if (cleartextAllowed(origin)) CLEARTEXT_HINT else CLEARTEXT_DENIED
        }
        return null
    }

    private fun ipv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n < 0 || n > 255 || parts[i].isEmpty()) return null
            out[i] = n
        }
        return out
    }

    private fun privateV4(o: IntArray): Boolean = when {
        o[0] == 10 -> true
        o[0] == 127 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        o[0] == 100 && o[1] in 64..127 -> true // CGNAT / Tailscale
        else -> false
    }

    private fun privateV6(h: String): Boolean {
        val bare = h.substringBefore('%')
        if (bare == "::1" || bare == "::") return true
        val first = bare.substringBefore(':')
        if (first.length < 2) return false
        val hex = first.toIntOrNull(16) ?: return false
        return (hex and 0xFE00) == 0xFC00 || // fc00::/7 ULA (Tailscale fd7a:...)
            (hex and 0xFFC0) == 0xFE80 // fe80::/10 link-local
    }
}
