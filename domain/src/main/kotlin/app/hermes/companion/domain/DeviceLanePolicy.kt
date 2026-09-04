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
     * Fail-closed denylist. Exact package ids, or a prefix ending in `*`.
     * Grouped: platform surfaces that grant power, authenticators, password managers,
     * payments/wallets, banking. Users extend it from the Device tab (see [isProtected]).
     */
    val PROTECTED_PACKAGES = setOf(
        // platform: settings, permission grants, installs, keystore, billing
        "com.android.settings",
        "com.android.systemui",
        "com.android.vending",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.keychain",
        "com.android.certinstaller",
        "com.google.android.gms",
        "com.samsung.android.settings.*",
        "com.samsung.android.lool",
        "com.samsung.knox.*",
        // authenticators
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "com.azure.authenticator",
        "com.duosecurity.duomobile",
        "com.beemdevelopment.aegis",
        "org.fedorahosted.freeotp",
        "com.yubico.yubioath",
        "com.okta.android.auth",
        "com.rsa.securidapp",
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
        "com.nordpass.android.app.password.manager",
        "proton.android.pass",
        "com.enpass.app",
        "com.samsung.android.samsungpass",
        "com.samsung.android.authfw",
        // payments / wallets
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
        "com.samsung.android.spay",
        "com.samsung.android.spayfw",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "in.amazon.mShop.android.shopping",
        "com.coinbase.android",
        "com.binance.dev",
        // banking
        "com.chase.sig.android",
        "com.infonow.bofa",
        "com.wf.wellsfargo",
        "com.citi.citimobile",
        "com.usbank.mobilebanking",
        "com.capitalone.*",
        "com.discoverfinancial.mobile",
        "com.revolut.revolut",
        "co.uk.getmondo",
        "com.barclays.*",
        "com.hsbc.*",
        "com.sbi.*",
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.axis.mobile",
        "com.msf.kbank.mobile",
        "com.idbibank.*",
        "com.db.pwcc.dbmobile",
        "de.comdirect.android",
        "com.ing.*",
        "com.commbank.netbank",
        "au.com.nab.mobile",
        "org.westpac.bank",
        "com.anz.android.gomoney",
        "com.rbc.mobile.android",
        "com.td",
        "com.scotiabank.banking",
        "com.cibc.android.mobi",
        "com.bmo.mobile",
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
