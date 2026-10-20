package app.hermes.companion.data.local

import android.content.Context
import app.hermes.companion.domain.WakePolicy

/**
 * Last origin, per-host last profile / ntfy topic, phone-wide toggles. Not secrets.
 * Profiles use [HostKeys]; notification topics use exact dashboard origins including proxy paths.
 * Legacy topics are retained but need an explicit re-save because their path owner is unknowable.
 */
class StickyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("companion.sticky", Context.MODE_PRIVATE)

    /** Explicit phone-wide speech choice. An unset choice takes the rollout default. */
    var speechBackend: String?
        get() = prefs.getString("speech_backend", null)
        set(value) { prefs.edit().putString("speech_backend", value).apply() }

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

    /** Retained legacy value, never implicitly inherited by another host. */
    var ntfyTopic: String?
        get() = prefs.getString(KEY_NTFY, null)
        set(value) { prefs.edit().putString(KEY_NTFY, value?.trim()?.ifBlank { null }).apply() }

    fun ntfyTopicFor(origin: String): String? {
        val owner = WakePolicy.topicOwnerKey(origin) ?: return null
        return prefs.getString(KEY_NTFY_OWNER + owner, null)
    }

    fun setNtfyTopic(origin: String, topic: String?) {
        val owner = WakePolicy.topicOwnerKey(origin) ?: return
        val clean = topic?.trim()?.ifBlank { null }
        prefs.edit()
            .putString(KEY_NTFY_OWNER + owner, clean)
            .apply()
    }

    /** Explicit exact-owner topics only. Legacy authority keys must never become trusted routing. */
    fun ntfyHosts(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(KEY_NTFY_OWNER) && it.value is String && (it.value as String).isNotBlank() }
            .associate { it.key.removePrefix(KEY_NTFY_OWNER) to (it.value as String) }

    var stayConnected: Boolean
        get() = prefs.getBoolean(KEY_STAY, false)
        set(value) { prefs.edit().putBoolean(KEY_STAY, value).apply() }

    /**
     * Opt-in: with WRITE_SECURE_SETTINGS granted over adb, write the Hands accessibility service
     * back into Settings whenever it drops out. Off by default — while on, turning Hermes off in
     * Android's accessibility settings does not stick, so the user must choose it knowingly.
     */
    var keepA11yOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_A11Y, false)
        set(value) { prefs.edit().putBoolean(KEY_KEEP_A11Y, value).apply() }

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

    /** Thread rail order (A18.9): `CREATED` (default) / `ACTIVE` / `TITLE`. Phone-wide; parsed leniently by the domain. */
    var threadSort: String?
        get() = prefs.getString(KEY_THREAD_SORT, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().putString(KEY_THREAD_SORT, value?.trim()?.ifBlank { null }).apply() }

    /** Rail shows archived threads too (A18.12). Phone-wide; changes the host fetch, so it is not screen-local. */
    var showArchived: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ARCHIVED, false)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_ARCHIVED, value).apply() }

    /** Last transcript seq the operator has seen per room (A22.2 unread), keyed `<hostKey>/<roomId>`. */
    fun roomSeen(origin: String, roomId: String): Int =
        prefs.getInt(KEY_ROOM_SEEN + "." + HostKeys.of(origin) + "/" + roomId, 0)

    fun setRoomSeen(origin: String, roomId: String, seq: Int) {
        prefs.edit().putInt(KEY_ROOM_SEEN + "." + HostKeys.of(origin) + "/" + roomId, seq).apply()
    }

    /** User-added protected packages (exact ids or `prefix.*`). Phone-wide safety denylist (built-in list is empty). */
    var protectedPackages: Set<String>
        get() = prefs.getStringSet(KEY_PROTECTED, emptySet()).orEmpty().toSet()
        set(value) { prefs.edit().putStringSet(KEY_PROTECTED, value.toSet()).apply() }

    companion object {
        private const val KEY_PROTECTED = "protected_packages"
        private const val KEY_THREAD_SORT = "thread_sort"
        private const val KEY_SHOW_ARCHIVED = "thread_show_archived"
        private const val KEY_ROOM_SEEN = "room_seen"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_LAST_GOOD = "last_good_origin"
        private const val KEY_PROFILE = "profile_id"
        private const val KEY_NTFY = "ntfy_topic"
        private const val KEY_NTFY_OWNER = "ntfy_owner_v2."
        private const val KEY_STAY = "stay_connected"
        private const val KEY_KEEP_A11Y = "keep_a11y_on"
        private const val KEY_BIOMETRIC = "biometric_lock"
        private const val KEY_NOTIF_STREAM = "notif_stream_enabled"
        private const val KEY_NOTIF_STREAM_ORIGIN = "notif_stream_origin"
        private const val KEY_NOTIF_STREAM_PROFILE = "notif_stream_profile"
    }
}
