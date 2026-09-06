package app.hermes.companion.data.local

import android.content.Context

/**
 * Last origin, per-host last profile / ntfy topic, phone-wide toggles. Not secrets.
 * Per-host values are keyed by [HostKeys]; the pre-A8.5 global `profile_id` / `ntfy_topic`
 * remain as fallbacks so an upgrade keeps the current host's choices.
 */
class StickyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("companion.sticky", Context.MODE_PRIVATE)

    /** Last origin the user *attempted*. Prefer [lastGoodOrigin] for auto-connect. */
    var origin: String?
        get() = prefs.getString(KEY_ORIGIN, null)
        set(value) { prefs.edit().putString(KEY_ORIGIN, value).apply() }

    /** Last origin that connected successfully (A18.5): a dead host never becomes the auto-connect target. */
    var lastGoodOrigin: String?
        get() = prefs.getString(KEY_LAST_GOOD, null)
        set(value) { prefs.edit().putString(KEY_LAST_GOOD, value).apply() }

    /** Global fallback profile (legacy). Use [profileFor] / [setProfile]. */
    var profileId: String?
        get() = prefs.getString(KEY_PROFILE, null)
        set(value) { prefs.edit().putString(KEY_PROFILE, value).apply() }

    fun profileFor(origin: String): String? =
        prefs.getString(KEY_PROFILE + "." + HostKeys.of(origin), null) ?: profileId

    fun setProfile(origin: String, id: String?) {
        prefs.edit()
            .putString(KEY_PROFILE + "." + HostKeys.of(origin), id)
            .putString(KEY_PROFILE, id)
            .apply()
    }

    /** Global fallback ntfy topic (legacy). Use [ntfyTopicFor] / [setNtfyTopic]. */
    var ntfyTopic: String?
        get() = prefs.getString(KEY_NTFY, null)
        set(value) { prefs.edit().putString(KEY_NTFY, value?.trim()?.ifBlank { null }).apply() }

    fun ntfyTopicFor(origin: String): String? =
        prefs.getString(KEY_NTFY + "." + HostKeys.of(origin), null) ?: ntfyTopic

    fun setNtfyTopic(origin: String, topic: String?) {
        val clean = topic?.trim()?.ifBlank { null }
        prefs.edit()
            .putString(KEY_NTFY + "." + HostKeys.of(origin), clean)
            .putString(KEY_NTFY, clean)
            .apply()
    }

    /** Every host that has a per-host ntfy topic (for background wake subscriptions). */
    fun ntfyHosts(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(KEY_NTFY + ".") && it.value is String && (it.value as String).isNotBlank() }
            .associate { it.key.removePrefix(KEY_NTFY + ".") to (it.value as String) }

    var stayConnected: Boolean
        get() = prefs.getBoolean(KEY_STAY, false)
        set(value) { prefs.edit().putBoolean(KEY_STAY, value).apply() }

    /** Phone-wide: lock the operator UI behind biometric / device PIN (A12.3). */
    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) { prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply() }

    /** Phone-wide: stream shade notifications to a chosen gateway+profile (A13.2). */
    var notifStreamEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_STREAM, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIF_STREAM, value).apply() }

    /** Target origin for the stream; blank → fall back to [lastGoodOrigin] / [origin]. */
    var notifStreamOrigin: String?
        get() = prefs.getString(KEY_NOTIF_STREAM_ORIGIN, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().putString(KEY_NOTIF_STREAM_ORIGIN, value?.trim()?.ifBlank { null }).apply() }

    fun notifStreamProfileFor(origin: String): String? =
        prefs.getString(KEY_NOTIF_STREAM_PROFILE + "." + HostKeys.of(origin), null) ?: profileFor(origin)

    fun setNotifStreamProfile(origin: String, id: String?) {
        prefs.edit()
            .putString(KEY_NOTIF_STREAM_PROFILE + "." + HostKeys.of(origin), id?.trim()?.ifBlank { null })
            .apply()
    }

    /** Resolved stream sink: configured origin or sticky last-good / origin. */
    fun notifStreamTargetOrigin(): String? =
        notifStreamOrigin ?: lastGoodOrigin ?: origin

    /** User-added protected packages (exact ids or `prefix.*`). Phone-wide safety denylist (built-in list is empty). */
    var protectedPackages: Set<String>
        get() = prefs.getStringSet(KEY_PROTECTED, emptySet()).orEmpty().toSet()
        set(value) { prefs.edit().putStringSet(KEY_PROTECTED, value.toSet()).apply() }

    companion object {
        private const val KEY_PROTECTED = "protected_packages"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_LAST_GOOD = "last_good_origin"
        private const val KEY_PROFILE = "profile_id"
        private const val KEY_NTFY = "ntfy_topic"
        private const val KEY_STAY = "stay_connected"
        private const val KEY_BIOMETRIC = "biometric_lock"
        private const val KEY_NOTIF_STREAM = "notif_stream_enabled"
        private const val KEY_NOTIF_STREAM_ORIGIN = "notif_stream_origin"
        private const val KEY_NOTIF_STREAM_PROFILE = "notif_stream_profile"
    }
}
