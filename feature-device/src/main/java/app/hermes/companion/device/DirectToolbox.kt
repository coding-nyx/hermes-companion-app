package app.hermes.companion.device

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import app.hermes.companion.domain.DeviceLanePolicy
import java.util.Locale

/**
 * L0 (direct-API) device tools. Independent of the AccessibilityService.
 * Every function here is safe to call without `CompanionAccessibilityService` being bound.
 *
 * All work is synchronous and runs on the caller's thread; the DeviceNodeCoordinator
 * wraps these in `withContext(Dispatchers.Default)` so the lane does not block on UI.
 */
object DirectToolbox {

    // ---------- Audio: volume ----------

    private const val STREAM_MUSIC = "music"
    private const val STREAM_RING = "ring"
    private const val STREAM_NOTIFICATION = "notification"
    private const val STREAM_ALARM = "alarm"
    private const val STREAM_SYSTEM = "system"
    private const val STREAM_VOICE_CALL = "voice_call"
    private const val STREAM_DTMF = "dtmf"

    /** Map a stable name to the AudioManager stream constant. Unknown name → null. */
    fun streamType(name: String): Int? = when (name.trim().lowercase(Locale.ROOT)) {
        STREAM_MUSIC -> AudioManager.STREAM_MUSIC
        STREAM_RING -> AudioManager.STREAM_RING
        STREAM_NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        STREAM_ALARM -> AudioManager.STREAM_ALARM
        STREAM_SYSTEM -> AudioManager.STREAM_SYSTEM
        STREAM_VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        STREAM_DTMF -> AudioManager.STREAM_DTMF
        else -> null
    }

    fun streamName(type: Int): String = when (type) {
        AudioManager.STREAM_MUSIC -> STREAM_MUSIC
        AudioManager.STREAM_RING -> STREAM_RING
        AudioManager.STREAM_NOTIFICATION -> STREAM_NOTIFICATION
        AudioManager.STREAM_ALARM -> STREAM_ALARM
        AudioManager.STREAM_SYSTEM -> STREAM_SYSTEM
        AudioManager.STREAM_VOICE_CALL -> STREAM_VOICE_CALL
        AudioManager.STREAM_DTMF -> STREAM_DTMF
        else -> "stream_$type"
    }

    /**
     * Read a single stream or all of them (when [name] is null/blank).
     * Each row: `{"stream": "...", "current": 0..max, "max": ...}`.
     */
    fun audioVolumeGet(ctx: Context, name: String?): List<Map<String, Any>> {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return emptyList()
        val wanted = if (name.isNullOrBlank()) {
            intArrayOf(
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_ALARM,
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_VOICE_CALL,
            )
        } else {
            intArrayOf(streamType(name) ?: return emptyList())
        }
        return wanted.map { type ->
            mapOf(
                "stream" to streamName(type),
                "current" to am.getStreamVolume(type),
                "max" to am.getStreamMaxVolume(type),
            )
        }
    }

    /**
     * Set a stream to an absolute level (clamped to [0, max]). Step uses
     * `adjustStreamVolume` when the caller passes `step` instead of `level`.
     * Returns the resulting `{stream, current, max}`.
     */
    fun audioVolumeSet(
        ctx: Context,
        name: String,
        level: Int? = null,
        step: Int? = null,
    ): Map<String, Any>? {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        val type = streamType(name) ?: return null
        val max = am.getStreamMaxVolume(type)
        val current = when {
            level != null -> level.coerceIn(0, max)
            step != null -> (am.getStreamVolume(type) + step).coerceIn(0, max)
            else -> return null
        }
        am.setStreamVolume(type, current, 0)
        return mapOf(
            "stream" to streamName(type),
            "current" to am.getStreamVolume(type),
            "max" to max,
        )
    }

    // ---------- Audio: ringer mode ----------

    fun ringerGet(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "unknown"
        return when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_SILENT -> "silent"
            else -> "unknown"
        }
    }

    /**
     * Set ringer mode. SILENT requires `ACCESS_NOTIFICATION_POLICY` (special) — the caller
     * must grant it in Settings for the silent transition to actually flip.
     * Returns the resulting mode name; null if the input was unknown.
     */
    fun ringerSet(ctx: Context, mode: String): String? {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        val target = when (mode.trim().lowercase(Locale.ROOT)) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return null
        }
        val policyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm?.isNotificationPolicyAccessGranted == true
        } else true
        if (target == AudioManager.RINGER_MODE_SILENT && !policyGranted) {
            // Still attempt: AudioManager will reject if policy is required, but we surface a clear error.
            am.ringerMode = target
            return if (am.ringerMode == target) "silent" else "denied:grant_notification_policy"
        }
        am.ringerMode = target
        return ringerGet(ctx)
    }

    // ---------- Media sessions ----------

    private fun mediaSessionManager(ctx: Context): MediaSessionManager? =
        ctx.getSystemService(MediaSessionManager::class.java)

    /**
     * Discover active media sessions. Requires `BIND_NOTIFICATION_LISTENER_SERVICE`; the user
     * must enable Companion under Settings → Notifications → Device & app notifications.
     * Returns a list of `{"package", "session_id", "is_active", "playback_state"}`.
     * Empty list when the listener is not bound.
     */
    fun mediaSessions(ctx: Context): List<Map<String, Any?>> {
        val sm = mediaSessionManager(ctx) ?: return emptyList()
        if (!NotifStreamBus.listenerBound) return emptyList()
        val listeners = runCatching {
            sm.getActiveSessions(
                android.content.ComponentName(ctx, CompanionNotificationListener::class.java),
            )
        }.getOrDefault(emptyList())
        return listeners.map { c ->
            val pkg = runCatching { c.packageName }.getOrNull().orEmpty()
            val state: Int = runCatching { c.playbackState?.state ?: PlaybackState.STATE_NONE }
                .getOrDefault(PlaybackState.STATE_NONE)
            mapOf(
                "package" to pkg,
                "session_id" to pkg, // We expose package as the stable handle; the agent targets the owning app.
                "is_active" to (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING),
                "playback_state" to playbackStateName(state),
            )
        }
    }

    /**
     * Get the "now playing" metadata for a given session handle (= owning package name).
     * Returns `{"package", "title", "artist", "album", "duration_ms"}` or null when no
     * matching active session exists.
     */
    fun mediaNowPlaying(ctx: Context, sessionHandle: String): Map<String, Any?>? {
        val controller = findController(ctx, sessionHandle) ?: return null
        val meta = runCatching { controller.metadata }.getOrNull()
        return mapOf(
            "package" to sessionHandle,
            "title" to meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            "artist" to meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            "album" to meta?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            "duration_ms" to (meta?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L),
        )
    }

    /**
     * Fire a transport action on the session owned by [sessionHandle]. Valid actions:
     * play, pause, toggle, next, prev, stop, seek (seek needs `position_ms`).
     * Returns `{"action", "package", "ok"}`.
     */
    fun mediaControl(
        ctx: Context,
        sessionHandle: String,
        action: String,
        positionMs: Long? = null,
    ): Map<String, Any?> {
        val controller = findController(ctx, sessionHandle)
        if (controller == null) {
            return mapOf("action" to action, "package" to sessionHandle, "ok" to false, "error" to "no_session")
        }
        val tc = controller.transportControls
        var fired = true
        when (action.trim().lowercase(Locale.ROOT)) {
            "play" -> tc.play()
            "pause" -> tc.pause()
            "toggle" -> {
                val state = runCatching { controller.playbackState?.state }.getOrDefault(PlaybackState.STATE_NONE)
                if (state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            }
            "next" -> tc.skipToNext()
            "prev" -> tc.skipToPrevious()
            "stop" -> tc.stop()
            "seek" -> if (positionMs != null) tc.seekTo(positionMs) else fired = false
            else -> fired = false
        }
        return mapOf("action" to action, "package" to sessionHandle, "ok" to fired)
    }

    private fun findController(ctx: Context, sessionHandle: String): MediaController? {
        if (sessionHandle.isBlank()) return null
        if (!NotifStreamBus.listenerBound) return null
        val sm = mediaSessionManager(ctx) ?: return null
        val listeners = runCatching {
            sm.getActiveSessions(
                android.content.ComponentName(ctx, CompanionNotificationListener::class.java),
            )
        }.getOrDefault(emptyList())
        return listeners.firstOrNull { runCatching { it.packageName }.getOrNull() == sessionHandle }
    }

    private fun playbackStateName(state: Int): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_next"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping_queue"
        PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
        PlaybackState.STATE_REWINDING -> "rewinding"
        else -> "none"
    }

    // ---------- Clipboard ----------

    /**
     * Outcome of a clipboard read. [Blocked] is distinct from [Empty] on purpose: on
     * Android 10+ a background app gets `null` back whether the clip is empty or the
     * platform is withholding it, and the agent needs to know which happened.
     */
    sealed interface ClipRead {
        data class Text(val text: String) : ClipRead

        /** A clip exists but carries no `text/plain` item. */
        data class NotText(val mime: String?) : ClipRead

        /** We could read, and there is genuinely nothing on the clipboard. */
        data object Empty : ClipRead

        /**
         * The platform withheld the clip. Android 10+: "Unless your app is the default
         * input method editor (IME) or is the app that currently has focus, your app
         * cannot access clipboard data on Android 10 or higher."
         */
        data object Blocked : ClipRead
    }

    /**
     * Read the primary clip as plain text.
     *
     * Companion normally runs as a foreground *service* with no visible activity, so it
     * is neither the focused app nor the default IME and this returns [ClipRead.Blocked]
     * on every supported device (`minSdk` 31). It succeeds only while a Companion
     * activity is actually on screen — which is why the null case is disambiguated by
     * process importance rather than reported as an empty clipboard.
     *
     * Reading also surfaces a "pasted from your clipboard" toast to the user on
     * Android 12+, so this is never a silent operation even when it works.
     */
    fun clipboardGet(ctx: Context): ClipRead {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return ClipRead.Blocked
        val desc = cm.primaryClipDescription
        if (desc == null) {
            // Ambiguous: empty clipboard, or a background read the platform refused.
            return if (hasVisibleActivity()) ClipRead.Empty else ClipRead.Blocked
        }
        if (!desc.hasMimeType("text/plain")) {
            return ClipRead.NotText(if (desc.mimeTypeCount > 0) desc.getMimeType(0) else null)
        }
        val clip = cm.primaryClip
            ?: return if (hasVisibleActivity()) ClipRead.Empty else ClipRead.Blocked
        if (clip.itemCount == 0) return ClipRead.Empty
        val text = runCatching { clip.getItemAt(0).coerceToText(ctx).toString() }.getOrNull()
            ?: return ClipRead.NotText(null)
        return ClipRead.Text(text)
    }

    /**
     * True when this process has a visible activity, as opposed to only a foreground
     * service. A resumed activity reports `IMPORTANCE_FOREGROUND` (100); an FGS with no
     * UI reports `IMPORTANCE_FOREGROUND_SERVICE` (125), so the `<=` comparison separates
     * "we have focus and may read the clipboard" from "we are only a service".
     */
    private fun hasVisibleActivity(): Boolean {
        val state = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(state)
        return state.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * Write plain text into the primary clip. Returns true on success.
     */
    fun clipboardSet(ctx: Context, text: String): Boolean {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return false
        return runCatching { cm.setPrimaryClip(ClipData.newPlainText("hermes", text)) }.isSuccess
    }

    // ---------- Notification: stream + listener access ----------

    /**
     * Read whether the system has granted Hermes Companion notification-listener access,
     * and which component is currently bound. Pure read; does not require NLS to be on.
     */
    fun nlsStatus(ctx: Context): Map<String, Any?> {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        val component = android.content.ComponentName(ctx, CompanionNotificationListener::class.java)
        val bound = DeviceLanePolicy.componentEnabled(enabled, component.packageName, component.className)
        return mapOf(
            "nls_bound" to bound,
            "listener_component" to component.flattenToString(),
            "enabled_listeners" to enabled,
        )
    }

    /**
     * Open the system Notification Listener settings page so the user can grant Hermes
     * Companion access. The app cannot programmatically grant NLS (signature-only on
     * Android), so the best the host can do is surface the right settings screen and
     * let the user complete the toggle.
     */
    fun nlsEnable(ctx: Context): Map<String, Any?> {
        val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { ctx.startActivity(intent) }.isSuccess
        // Report current state too so the agent knows whether the user already granted it.
        val current = nlsStatus(ctx)
        return mapOf(
            "launched" to launched,
            "intent" to "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
            "nls_bound" to (current["nls_bound"] as? Boolean ?: false),
        )
    }

    // ---------- Device info ----------

    /**
     * Read-only device metadata. Useful as a connectivity / health check
     * and as a stable identity hint for the host.
     */
    fun deviceInfo(ctx: Context): Map<String, Any?> {
        val battery = ctx.getSystemService(BatteryManager::class.java)
        val batteryLevel = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics().also { wm?.defaultDisplay?.getRealMetrics(it) }
        val refreshRate = wm?.defaultDisplay?.refreshRate ?: 0f
        val locale = Locale.getDefault().toLanguageTag()
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "product" to Build.PRODUCT,
            "device" to Build.DEVICE,
            "brand" to Build.BRAND,
            "android_release" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT,
            "build_id" to Build.ID,
            "screen_w" to dm.widthPixels,
            "screen_h" to dm.heightPixels,
            "density_dpi" to dm.densityDpi,
            "refresh_rate_hz" to refreshRate,
            "locale" to locale,
            "battery_pct" to batteryLevel,
            "ringer_mode" to ringerGet(ctx),
            "a11y_bound" to CompanionAccessibilityService.bound,
            "nls_bound" to NotifStreamBus.listenerBound,
        )
    }
}
