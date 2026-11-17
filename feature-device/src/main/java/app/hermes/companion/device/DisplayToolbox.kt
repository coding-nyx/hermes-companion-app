package app.hermes.companion.device

import android.content.Context
import android.provider.Settings
import kotlin.math.roundToInt

/** Direct display settings, independent of Accessibility and the armed screen hold. */
object DisplayToolbox {
    class Failure(val code: String, message: String) : Exception(message)

    /**
     * Reads the saved system brightness, not measured panel luminance. With adaptive brightness
     * enabled the visible level may differ. Unknown settings remain null rather than looking like 0.
     */
    fun brightnessGet(ctx: Context): Map<String, Any?> {
        val resolver = ctx.contentResolver
        val raw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            .takeIf { it >= 0 }
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        return mapOf(
            "level" to raw?.takeIf { it <= 255 }?.let { (it * 100.0 / 255).roundToInt() },
            "raw_level" to raw,
            "auto" to when (mode) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> true
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> false
                else -> null
            },
            "can_write" to Settings.System.canWrite(ctx),
            "level_source" to "system_setting",
        )
    }

    /** A manual percentage disables adaptive brightness; [auto] alone switches its mode. */
    fun brightnessSet(ctx: Context, level: Int? = null, auto: Boolean? = null): Map<String, Any?> {
        if (level == null && auto == null) {
            throw Failure("bad_args", "supply level (1..100 percent) or auto")
        }
        if (level != null && level !in 1..100) {
            throw Failure("bad_args", "level must be an integer from 1 to 100 percent")
        }
        if (level != null && auto == true) {
            throw Failure("bad_args", "a manual level cannot be combined with auto=true")
        }
        if (!Settings.System.canWrite(ctx)) {
            throw Failure("permission_denied", "WRITE_SETTINGS required: grant Companion Modify system settings access using mobile_settings_open(page=write_settings)")
        }
        val resolver = ctx.contentResolver
        val targetMode = if (auto == true) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        val targetRaw = level?.let { (it * 255.0 / 100).roundToInt() }
        try {
            // Save the requested level before switching out of adaptive mode. A failed write must
            // never be reported as success; two Settings writes are not an atomic transaction.
            if (targetRaw != null && !Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, targetRaw)) {
                throw Failure("settings_failed", "Android refused the brightness level")
            }
            if (!Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, targetMode)) {
                throw Failure("settings_failed", "Android refused the brightness mode; the saved level may have changed")
            }
        } catch (e: SecurityException) {
            throw Failure("permission_denied", "Android denied WRITE_SETTINGS for the brightness change; check Modify system settings access")
        }
        val result = brightnessGet(ctx)
        if (result["auto"] != (auto == true) || (targetRaw != null && result["raw_level"] != targetRaw)) {
            throw Failure("settings_failed", "Android did not retain the requested brightness settings")
        }
        return result
    }

    fun errorCode(t: Throwable?): String = when (t) {
        is Failure -> t.code
        is SecurityException -> "permission_denied"
        else -> "settings_failed"
    }
}
