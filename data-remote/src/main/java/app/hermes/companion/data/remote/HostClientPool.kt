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
            it.closeBotSockets()
            it.closeDeviceLane()
        }
    }

    companion object {
        fun key(origin: String): String {
            if (origin.isBlank()) return ""
            return runCatching { HostCredentialScope.key(origin) }.getOrDefault("")
        }
    }
}
