package app.hermes.companion.device

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Only edits Hermes' component; never writes the global accessibility switch off. */
class AccessibilitySettingsToggle(
    private val component: ComponentName,
    private val granted: () -> Boolean,
    private val readServices: () -> String?,
    private val writeServices: (String) -> Boolean,
    private val enableAccessibility: () -> Boolean,
) {
    fun setEnabled(enabled: Boolean): Boolean {
        return try {
            if (!granted()) return false
            val current = readServices().orEmpty().split(':').filter { it.isNotBlank() }
            val others = current.filter { ComponentName.unflattenFromString(it) != component }
            val next = if (enabled) others + component.flattenToString() else others
            if (next != current && !writeServices(next.joinToString(":"))) return false
            // Android owns global state. Never disable TalkBack or other services.
            if (enabled && !enableAccessibility()) return false
            val present = readServices().orEmpty().split(':').any {
                ComponentName.unflattenFromString(it) == component
            }
            present == enabled
        } catch (_: RuntimeException) {
            false
        }
    }

    /** null means state could not be read, not "off". */
    fun isListed(): Boolean? = try {
        readServices().orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    } catch (_: RuntimeException) { null }

    companion object {
        fun forContext(context: Context) = AccessibilitySettingsToggle(
            ComponentName(context, CompanionAccessibilityService::class.java),
            { CompanionAccessibilityService.canWriteSecureSettings(context) },
            { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) },
            { Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, it) },
            { Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1) },
        )
    }
}
