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
    fun setEnabled(enabled: Boolean, checkAllowed: () -> Unit = {}): Boolean {
        var added = false
        return try {
            checkAllowed()
            if (!granted()) return false
            val current = readServices().orEmpty().split(':').filter { it.isNotBlank() }
            val others = current.filter { ComponentName.unflattenFromString(it) != component }
            val next = if (enabled) others + component.flattenToString() else others
            checkAllowed()
            if (next != current) {
                if (!writeServices(next.joinToString(":"))) return false
                added = enabled && current.size == others.size
            }
            checkAllowed()
            // Android owns global state. Never disable TalkBack or other services.
            if (enabled && !enableAccessibility()) throw IllegalStateException("accessibility_enable_failed")
            checkAllowed()
            val present = readServices().orEmpty().split(':').any {
                ComponentName.unflattenFromString(it) == component
            }
            if (present != enabled) throw IllegalStateException("accessibility_enable_failed")
            true
        } catch (_: RuntimeException) {
            // An interrupted enable must not leave a newly introduced component behind.
            // Re-read before rollback so unrelated services added concurrently survive.
            if (added) runCatching {
                val now = readServices().orEmpty().split(':').filter { it.isNotBlank() }
                val others = now.filter { ComponentName.unflattenFromString(it) != component }
                if (now != others) writeServices(others.joinToString(":"))
            }
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
