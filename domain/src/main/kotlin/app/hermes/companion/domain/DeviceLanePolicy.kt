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

    /**
     * Minimal fail-closed denylist (exact id or `prefix.*`). Custom rules merge via [isProtected].
     * Kept small on purpose — Settings / permission controller / installer / keychain plus
     * common authenticators and password managers. Full banking list stays out of tree.
     */
    val PROTECTED_PACKAGES = setOf(
        // platform surfaces that grant power
        "com.android.settings",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.keychain",
        "com.android.certinstaller",
        "com.samsung.android.settings.*",
        // authenticators
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "com.azure.authenticator",
        "com.duosecurity.duomobile",
        "com.beemdevelopment.aegis",
        "org.fedorahosted.freeotp",
        "com.yubico.yubioath",
        "com.okta.android.auth",
        // password managers
        "com.onepassword.android",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.bitwarden.mobile",
        "com.x8bit.bitwarden",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre",
        "keepass2android.*",
        "com.dashlane",
        "proton.android.pass",
        "com.samsung.android.samsungpass",
        "com.samsung.android.authfw",
    )

    private val PACKAGE_RE = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /** Trim/lowercase a user-typed package (optionally `prefix.*`). Null if it is not a package id. */
    fun normalizePackage(raw: String): String? {
        val p = raw.trim().lowercase()
        if (p.isBlank()) return null
        val wild = p.endsWith("*")
        val body = if (wild) p.removeSuffix("*").removeSuffix(".") else p
        if (!PACKAGE_RE.matches(body)) return null
        return if (wild) "$body.*" else body
    }

    fun matchesRule(pkg: String, rule: String): Boolean {
        if (rule.endsWith("*")) {
            val prefix = rule.removeSuffix("*")
            return pkg.startsWith(prefix) || pkg == prefix.removeSuffix(".")
        }
        return pkg == rule
    }

    fun isProtected(pkg: String, extra: Set<String> = emptySet()): Boolean {
        val p = pkg.trim().lowercase()
        if (p.isBlank()) return false
        return PROTECTED_PACKAGES.any { matchesRule(p, it) } || extra.any { matchesRule(p, it) }
    }

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

    fun registerJson(
        deviceId: String,
        profileId: String,
        credential: String,
        capabilities: List<String> = CAPABILITIES,
        deviceName: String = "",
        model: String = "",
        manufacturer: String = "",
        osVersion: String = "",
        protectedPackages: Collection<String> = emptyList(),
    ): String {
        val caps = filterCapabilities(capabilities).joinToString(",") { "\"$it\"" }
        val protected = protectedPackages.map { it.trim().lowercase() }.filter { it.isNotBlank() }
            .distinct().joinToString(",") { q(it) }
        return buildString {
            append("""{"protocol_version":$PROTOCOL_VERSION,"device_id":${q(deviceId)},"profile":${q(profileId)},"credential":${q(credential)},"capabilities":[$caps]""")
            if (deviceName.isNotBlank()) append(""","device_name":${q(deviceName)}""")
            if (model.isNotBlank()) append(""","model":${q(model)}""")
            if (manufacturer.isNotBlank()) append(""","manufacturer":${q(manufacturer)}""")
            if (osVersion.isNotBlank()) append(""","os_version":${q(osVersion)}""")
            if (protected.isNotEmpty()) append(""","protected_packages":[$protected]""")
            append("}")
        }
    }

    fun reject(
        arm: DeviceArm,
        a11yBound: Boolean,
        action: String,
        foregroundApp: String = "",
        ref: String? = null,
        lastRefs: Set<String> = emptySet(),
        targetPackage: String? = null,
        extraProtected: Set<String> = emptySet(),
    ): String? {
        if (action !in CAPABILITIES) return "capability_denied"
        if (action == "device.disarm") return null
        if (action == "device.arm") return if (a11yBound) null else "a11y_unavailable"
        if (arm == DeviceArm.DISARMED) return "disarmed"
        if (!a11yBound) return "a11y_unavailable"
        val pkg = targetPackage?.takeIf { it.isNotBlank() } ?: foregroundApp
        if (isProtected(pkg, extraProtected)) return "protected_package"
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
