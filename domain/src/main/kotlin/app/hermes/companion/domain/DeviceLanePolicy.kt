package app.hermes.companion.domain

import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.DeviceResult

/** Device-lane WSS: ticket in subprotocol only. Never a query param. */
object DeviceLanePolicy {
    const val PROTOCOL = "hermes-mobile-control-v1"
    const val TICKET_PREFIX = "hermes-mobile-control-ticket."
    const val TICKET_TTL_MS = 30_000L
    const val PROTOCOL_VERSION = 1
    const val IDLE_DISARM_MS = 300_000L

    val CAPABILITIES = listOf(
        "device.noop",
        "device.snapshot",
        "device.click",
        "device.type",
        "device.swipe",
        "device.scroll",
        "device.press",
        "device.open_app",
        "device.apps",
        "device.wait",
        "device.screenshot",
        "device.arm",
        "device.disarm",
    )

    val PROTECTED_PACKAGES = setOf(
        "com.google.android.apps.authenticator2",
        "com.android.vending",
        "com.android.settings",
        "com.android.systemui",
    )

    fun ticketSubprotocol(ticket: String): String = TICKET_PREFIX + ticket.trim()

    fun subprotocols(ticket: String): List<String> = listOf(PROTOCOL, ticketSubprotocol(ticket))

    fun protocolHeader(ticket: String): String = subprotocols(ticket).joinToString(", ")

    fun parseTicket(header: String): String? {
        val parts = header.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return parts.firstOrNull { it.startsWith(TICKET_PREFIX) }?.removePrefix(TICKET_PREFIX)
            ?.takeIf { it.isNotBlank() }
    }

    fun hasV1(header: String): Boolean =
        header.split(',').map { it.trim() }.contains(PROTOCOL)

    fun filterCapabilities(requested: List<String>): List<String> =
        requested.map { it.trim() }.filter { it in CAPABILITIES }.distinct()

    fun wsUrl(origin: String): String {
        val base = origin.trim().trimEnd('/')
            .replace(Regex("^http://"), "ws://")
            .replace(Regex("^https://"), "wss://")
        return "$base/companion/device/ws"
    }

    fun registerJson(deviceId: String, profileId: String, credential: String, capabilities: List<String> = CAPABILITIES): String {
        val caps = filterCapabilities(capabilities).joinToString(",") { "\"$it\"" }
        return """{"protocol_version":$PROTOCOL_VERSION,"device_id":${q(deviceId)},"profile":${q(profileId)},"credential":${q(credential)},"capabilities":[$caps]}"""
    }

    fun reject(
        arm: DeviceArm,
        a11yBound: Boolean,
        action: String,
        foregroundApp: String = "",
        ref: String? = null,
        lastRefs: Set<String> = emptySet(),
        targetPackage: String? = null,
    ): String? {
        if (action !in CAPABILITIES) return "capability_denied"
        if (action == "device.disarm") return null
        if (action == "device.arm") return if (a11yBound) null else "a11y_unavailable"
        if (arm == DeviceArm.DISARMED) return "disarmed"
        if (!a11yBound) return "a11y_unavailable"
        val pkg = targetPackage?.takeIf { it.isNotBlank() } ?: foregroundApp
        if (pkg in PROTECTED_PACKAGES) return "protected_package"
        if (!ref.isNullOrBlank() && action in REF_ACTIONS && ref !in lastRefs) return "stale_ref"
        return null
    }

    fun fail(commandId: String, code: String, message: String = code): DeviceResult =
        DeviceResult(commandId = commandId, ok = false, errorCode = code, errorMessage = message)

    fun ok(commandId: String, resultJson: String = "{}"): DeviceResult =
        DeviceResult(commandId = commandId, ok = true, resultJson = resultJson)

    fun a11yEnabled(enabledList: String?, suffix: String = "CompanionAccessibilityService"): Boolean =
        enabledList.orEmpty().contains(suffix)

    private val REF_ACTIONS = setOf("device.click", "device.type", "device.swipe", "device.scroll")

    private fun q(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }
}
