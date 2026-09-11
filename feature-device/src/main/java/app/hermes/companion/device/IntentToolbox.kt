package app.hermes.companion.device

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.SearchManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * L2 lane: hand a task to the app that owns it instead of driving UI or holding a permission.
 *
 * These are the cheapest tools in the inventory. `sms.compose` and `calls.dial` need **no
 * permission at all** — the messaging app or dialer opens pre-filled and the user completes the
 * action — which makes them the route to prefer over the silent `sms.send`.
 *
 * The cost is that L2 is not silent: it puts an app on screen, so every result here reports
 * `screen_changed` and the phone needs a background-activity-start exemption to launch while
 * idle. Companion declares `SYSTEM_ALERT_WINDOW`, which is one of the documented exemptions.
 *
 * On Android 11+ an unlisted intent is invisible — `startActivity` throws
 * `ActivityNotFoundException` — so each action here has a matching `<queries>` entry in the app
 * manifest. Adding an action without the entry produces a tool that silently cannot work.
 */
object IntentToolbox {

    class NoHandler(val action: String) : Exception("no app on this phone handles $action")
    class Denied(val permission: String) : Exception("missing $permission")

    /**
     * Launch one handoff. Returns what the agent needs to know: which app-facing action ran,
     * and that the screen is now showing something else.
     */
    fun launch(ctx: Context, action: String, address: String, body: String): Map<String, Any?> {
        val intent = when (action) {
            "device.sms.compose" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(address)}")).apply {
                if (body.isNotEmpty()) putExtra("sms_body", body)
            }
            "device.calls.dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(address)}"))
            "device.calls.place" -> {
                // The only one here that acts without the user confirming, so it is the only
                // one that needs a runtime permission.
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    throw Denied(Manifest.permission.CALL_PHONE)
                }
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(address)}"))
            }
            else -> throw IllegalArgumentException("unknown handoff $action")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw NoHandler(action)
        }
        return mapOf(
            "handoff" to action,
            "address" to address,
            // The agent must know the phone is no longer where it left it.
            "screen_changed" to true,
            "needs_user" to (action != "device.calls.place"),
        )
    }

    /**
     * The rest of the L2 lane: hand a task to the app that owns it.
     *
     * `alarm`/`timer` carry `EXTRA_SKIP_UI`, which asks the clock app to act without coming to
     * the foreground — the one case where an intent handoff is genuinely silent. Whether a given
     * clock app honours it is not guaranteed by the platform, so the result reports what was
     * requested and the caller should not assume the screen stayed put.
     */
    fun launchTask(ctx: Context, action: String, args: Args): Map<String, Any?> {
        val intent = when (action) {
            "device.alarm.set" -> {
                val hour = args.int("hour") ?: throw IllegalArgumentException("hour required (0-23)")
                val minute = args.int("minute") ?: throw IllegalArgumentException("minute required (0-59)")
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                    putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                    args.str("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    putExtra(AlarmClock.EXTRA_SKIP_UI, args.bool("skip_ui") ?: true)
                }
            }
            "device.timer.set" -> {
                val seconds = args.int("seconds") ?: throw IllegalArgumentException("seconds required")
                if (seconds <= 0) throw IllegalArgumentException("seconds must be positive")
                Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    args.str("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    putExtra(AlarmClock.EXTRA_SKIP_UI, args.bool("skip_ui") ?: true)
                }
            }
            "device.media.play_search" -> {
                // The gap the media tools had: transport control needs an existing session, so
                // nothing could start playback from cold. This can.
                val query = args.str("query") ?: throw IllegalArgumentException("query required")
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                }
            }
            "device.calendar.add" -> {
                val title = args.str("title") ?: throw IllegalArgumentException("title required")
                val begin = args.long("begin_ms") ?: throw IllegalArgumentException("begin_ms required")
                val end = args.long("end_ms") ?: (begin + 60L * 60 * 1000)
                Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                    args.str("location")?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                    args.str("description")?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                }
            }
            "device.share" -> {
                val text = args.str("text") ?: throw IllegalArgumentException("text required")
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    args.str("subject")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                }
            }
            "device.settings.open" -> {
                val page = args.str("page") ?: throw IllegalArgumentException("page required")
                val settingsAction = SETTINGS_PAGES[page.trim().lowercase()]
                    ?: throw IllegalArgumentException(
                        "unknown page; one of ${SETTINGS_PAGES.keys.sorted().joinToString(",")}",
                    )
                Intent(settingsAction)
            }
            "device.apps.uninstall" -> {
                val pkg = args.str("package") ?: throw IllegalArgumentException("package required")
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            }
            else -> throw IllegalArgumentException("unknown handoff $action")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw NoHandler(action)
        }
        // skip_ui asks the clock app to stay in the background, but nothing guarantees it obeys,
        // so report the request rather than a promise.
        val silentRequested = action in SKIP_UI_ACTIONS && (args.bool("skip_ui") ?: true)
        return mapOf(
            "handoff" to action,
            "screen_changed" to !silentRequested,
            "skip_ui_requested" to silentRequested,
            "needs_user" to !silentRequested,
        )
    }

    /**
     * The settings pages worth deep-linking: every one of them is somewhere the platform
     * refuses to let a third-party app act directly, so pointing the user there is the only
     * honest option (see the "not available" rows in docs/PLAN-phone-tools.md).
     */
    val SETTINGS_PAGES: Map<String, String> = mapOf(
        "wifi" to android.provider.Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
        "airplane" to android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "location" to android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "accessibility" to android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "notification_listener" to android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        "dnd_access" to android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
        "usage_access" to android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,
        "write_settings" to android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
        "battery_optimization" to android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        "app_details" to android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "date" to android.provider.Settings.ACTION_DATE_SETTINGS,
        "sound" to android.provider.Settings.ACTION_SOUND_SETTINGS,
        "display" to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
    )

    private val SKIP_UI_ACTIONS = setOf("device.alarm.set", "device.timer.set")

    /** Typed accessor over the coordinator's flat argument map. */
    class Args(private val get: (String) -> String?) {
        fun str(key: String): String? = get(key)?.takeIf { it.isNotBlank() }
        fun int(key: String): Int? = get(key)?.trim()?.toIntOrNull()
        fun long(key: String): Long? = get(key)?.trim()?.toLongOrNull()
        fun bool(key: String): Boolean? = when (get(key)?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    fun errorCode(t: Throwable?): String = when (t) {
        is Denied -> "permission_denied"
        is NoHandler -> "no_handler"
        is IllegalArgumentException -> "missing_args"
        is SecurityException -> "permission_denied"
        else -> "handoff_failed"
    }
}
