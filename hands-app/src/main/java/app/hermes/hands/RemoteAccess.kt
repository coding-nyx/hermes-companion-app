package app.hermes.hands

import java.net.Inet4Address
import java.net.NetworkInterface

data class RemoteAccessState(
    val enabled: Boolean = false,
    val fingerprint: String = "",
    val endpoints: List<String> = emptyList(),
    val message: String = "Remote access is off.",
    val starting: Boolean = false,
)

/** Include VPN interfaces: Android Tailscale addresses usually live on a tunnel. */
internal fun remoteEndpoints(port: Int = 8766): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { network ->
        network.inetAddresses.toList().filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress && !it.isAnyLocalAddress }
            .mapNotNull { it.hostAddress?.let { address -> "https://$address:$port" } }
    }.distinct().sorted()
}.getOrDefault(emptyList())
