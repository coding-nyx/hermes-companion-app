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
        "device.notifications",
        // L0 (direct API) tools — do not require a11y and are not scoped to the foreground app.
        "device.audio.volume.get",
        "device.audio.volume.set",
        "device.audio.ringer",
        "device.media.sessions",
        "device.media.now_playing",
        "device.media.control",
        "device.clipboard.get",
        "device.clipboard.set",
        "device.device.info",
        // L0 stream/NLS controls — open the system NLS settings page, or read/toggle
        // the phone's shade-notification stream. `nls.enable` and the stream read are
        // passive; the stream write mutates sticky state so it is destructive.
        "device.notifications.nls",
        "device.notifications.nls.enable",
        "device.notifications.stream",
        "device.notifications.stream.set",
        // L0 notification actions — act on a shade notification instead of tapping its UI.
        "device.notifications.actions",
        "device.notifications.action",
        "device.notifications.reply",
        "device.notifications.dismiss",
        "device.notifications.snooze",
        // L0 messaging / call-log / contacts. Provider queries and one SmsManager call.
        "device.sms.list",
        "device.sms.send",
        "device.sms.compose",
        "device.calls.log",
        "device.calls.dial",
        "device.calls.place",
        "device.contacts.search",
        // L0 voice out. No permission, works with the screen off.
        "device.voice.speak",
        "device.voice.stop",
        "device.voice.status",
        // L2 intent handoffs and L0 read surfaces.
        "device.alarm.set",
        "device.timer.set",
        "device.media.play_search",
        "device.calendar.add",
        "device.share",
        "device.settings.open",
        "device.apps.uninstall",
        "device.calendar.list",
        "device.location.last",
        "device.battery",
        "device.power.state",
        "device.thermal",
        "device.apps.usage",
        "device.audio.dnd",
        "device.audio.dnd.set",
    )

    /**
     * L0 actions skip the a11y-bound gate and the protected-package gate. They are
     * short-lived, capability-scoped platform calls. Arming is still enforced for the
     * destructive subset (volume-set, ringer-set, media-control, clipboard-set) by the
     * lane policy; passive reads may also be allowed while disarmed.
     */
    val L0_NO_A11Y: Set<String> = setOf(
        "device.audio.volume.get",
        "device.audio.volume.set",
        "device.audio.ringer",
        "device.media.sessions",
        "device.media.now_playing",
        "device.media.control",
        "device.clipboard.get",
        "device.clipboard.set",
        "device.device.info",
        "device.notifications.nls",
        "device.notifications.nls.enable",
        "device.notifications.stream",
        "device.notifications.stream.set",
        "device.notifications.actions",
        "device.notifications.action",
        "device.notifications.reply",
        "device.notifications.dismiss",
        "device.notifications.snooze",
        "device.sms.list",
        "device.sms.send",
        "device.sms.compose",
        "device.calls.log",
        "device.calls.dial",
        "device.calls.place",
        "device.contacts.search",
        "device.voice.speak",
        "device.voice.stop",
        "device.voice.status",
        "device.alarm.set",
        "device.timer.set",
        "device.media.play_search",
        "device.calendar.add",
        "device.share",
        "device.settings.open",
        "device.apps.uninstall",
        "device.calendar.list",
        "device.location.last",
        "device.battery",
        "device.power.state",
        "device.thermal",
        "device.apps.usage",
        "device.audio.dnd",
        "device.audio.dnd.set",
    )

    /**
     * Read-only/passive L0 actions that are allowed even when the lane is DISARMED
     * (e.g. health checks, listing sessions). Destructive ones still require ARM.
     */
    val L0_PASSIVE: Set<String> = setOf(
        "device.audio.volume.get",
        "device.media.sessions",
        "device.media.now_playing",
        "device.device.info",
        "device.notifications.nls",
        "device.notifications.nls.enable",
        "device.notifications.stream",
        // Listing a notification's buttons is a read; firing one is not.
        "device.notifications.actions",
        // Reading whether a TTS engine exists is harmless; speaking out loud is not.
        "device.voice.status",
        // Health telemetry about the phone itself: no personal data, and an agent needs it to
        // judge whether the phone is even worth driving (screen off, thermally throttled, flat).
        "device.battery",
        "device.power.state",
        "device.thermal",
        "device.audio.dnd",
        // NOTE: calendar.list, location.last and apps.usage are reads but are deliberately NOT
        // passive — they are personal data, so they sit behind arming exactly like sms.list.
        // NOTE: sms.list, calls.log and contacts.search are reads but are deliberately NOT
        // passive. Arming is the user's consent, and one sms.list hands an agent the whole
        // message history — a disclosure that should not happen on an unarmed phone.
        // NOTE: clipboard.get is not passive either: clipboards routinely hold passwords and
        // one-time codes the user just copied.
    )

    /**
     * Built-in denylist is empty by design. Protection is user-managed via [isProtected]
     * `extra` (StickyStore custom rules synced to the host). Exact id or `prefix.*`.
     */
    val PROTECTED_PACKAGES: Set<String> = emptySet()

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
        wakeTopic: String = "",
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
            if (wakeTopic.isNotBlank()) append(""","wake_topic":${q(wakeTopic)}""")
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
        // Arming never depends on accessibility: an L0-only session is legitimate. Per-action
        // capability checks below still refuse the L1 lane with `a11y_unavailable`.
        if (action == "device.arm") return null
        val l0 = action in L0_NO_A11Y
        if (l0) {
            // L0 actions don't need a11y and don't check the foreground app — they're
            // scoped to a stream/package handle, not to whatever UI is on screen.
            // Passive ones are even allowed while disarmed; destructive ones still need ARM.
            if (!l0Passive(action) && arm == DeviceArm.DISARMED) return "disarmed"
            if (!ref.isNullOrBlank() && action in REF_ACTIONS && ref !in lastRefs) return "stale_ref"
            return null
        }
        if (arm == DeviceArm.DISARMED) return "disarmed"
        if (!a11yBound) return "a11y_unavailable"
        val pkg = targetPackage?.takeIf { it.isNotBlank() } ?: foregroundApp
        if (isProtected(pkg, extraProtected)) return "protected_package"
        if (!ref.isNullOrBlank() && action in REF_ACTIONS && ref !in lastRefs) return "stale_ref"
        return null
    }

    private fun l0Passive(action: String): Boolean = action in L0_PASSIVE

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
