package app.hermes.companion.data.remote

import java.util.concurrent.ConcurrentHashMap

/**
 * One [DashboardClient] per host (A8.5). A client carries per-host state — session token, cookies,
 * gated flag, RPC + device sockets, live-id map — so sharing one across hosts leaks lab's token
 * to hub. Keyed by the normalised origin `scheme://host:port`.
 */
class HostClientPool(private val factory: () -> DashboardClient = { DashboardClient() }) {
    private val clients = ConcurrentHashMap<String, DashboardClient>()

    fun forOrigin(origin: String): DashboardClient {
        val key = key(origin)
        return clients.getOrPut(key) { factory() }
    }

    fun existing(origin: String): DashboardClient? = clients[key(origin)]

    fun all(): Map<String, DashboardClient> = clients.toMap()

    /** Drop a host's client (revoke / forget): closes its sockets first. */
    fun evict(origin: String) {
        clients.remove(key(origin))?.let {
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
