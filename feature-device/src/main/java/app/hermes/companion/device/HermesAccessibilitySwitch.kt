package app.hermes.companion.device

/** Self-disable needs no secure-settings grant and cannot affect another service. */
class HermesAccessibilitySwitch(
    private val connected: () -> Boolean,
    private val disableSelf: () -> Unit,
    private val secureDisable: () -> Boolean,
) {
    fun requestOff(): Boolean {
        if (connected()) {
            try {
                disableSelf()
                return true // Request accepted, not proof of disconnection. UI observes actual state.
            } catch (_: RuntimeException) { /* Fall back to the service-specific settings edit. */ }
        }
        return try { secureDisable() } catch (_: RuntimeException) { false }
    }
}
