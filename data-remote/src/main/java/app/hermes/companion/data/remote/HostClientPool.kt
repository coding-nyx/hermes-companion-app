package app.hermes.companion.data.remote

import java.util.concurrent.ConcurrentHashMap

/**
 * One [DashboardClient] per host (A8.5). A client carries per-host state — session token, cookies,
 * gated flag, RPC + device sockets, live-id map — so sharing one across hosts leaks lab's token
 * to hub. Connections also retain a reverse-proxy prefix; two dashboards behind the same
 * authority must not share cookies, pairing credentials or RPC state.
 */
class HostClientPool(private val factory: () -> DashboardClient = { DashboardClient() }) {
    private val clients = ConcurrentHashMap<String, DashboardClient>()

    fun forOrigin(origin: String): DashboardClient {
        val key = HostCredentialScope.key(origin)
        return clients.computeIfAbsent(key) { factory().apply { bindOrigin(origin) } }
    }

    fun existing(origin: String): DashboardClient? = clients[HostCredentialScope.key(origin)]

    fun all(): Map<String, DashboardClient> = clients.toMap()

    /** Drop a host's client (revoke / forget): closes its sockets first. */
    fun evict(origin: String) {
        clients.remove(HostCredentialScope.key(origin))?.let {
            it.closeRpc()
            it.closeDeviceLane()
        }
    }

    companion object {
        fun key(origin: String): String {
            val trimmed = origin.trim()
            if (trimmed.isBlank()) return ""
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val scheme = withScheme.substringBefore("://").lowercase()
            val rest = withScheme.substringAfter("://").substringBefore('/').substringAfterLast('@')
            val (host, port) = if (rest.startsWith("[")) {
                (rest.substringBefore(']') + "]").lowercase() to rest.substringAfter(']').removePrefix(":")
            } else {
                rest.substringBefore(':').lowercase() to rest.substringAfter(':', "")
            }
            if (host.isBlank()) return ""
            val default = (scheme == "http" && port == "80") || (scheme == "https" && port == "443")
            return if (port.isBlank() || default) "$scheme://$host" else "$scheme://$host:$port"
        }
    }
}
