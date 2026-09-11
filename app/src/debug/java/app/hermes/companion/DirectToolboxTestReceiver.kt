package app.hermes.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.device.DirectToolbox
import app.hermes.companion.device.NotifStreamBus

/**
 * Debug-only smoke test: an `adb shell am broadcast` can drive any DirectToolbox
 * function and read the JSON result from logcat. Used to verify the L0 lane
 * end-to-end on a real device before the WS host is paired.
 *
 * Example: `adb shell am broadcast -a app.hermes.companion.test.L0 \
 *     --es action device.audio.volume.get --es stream music`
 *
 * This file and its manifest entry both live in `src/debug`, so nothing here is
 * merged into a release APK.
 *
 * The receiver has to be exported to be reachable from `adb shell am broadcast`
 * (the shell runs as a different UID), so `src/debug/AndroidManifest.xml` gates it
 * on `android.permission.DUMP` — held by the adb shell, unobtainable by third-party
 * apps. That gate is load-bearing: `device.notifications.stream.set` can retarget
 * the notification stream, so an ungated export would let any installed app
 * redirect the user's notifications to a gateway of its choosing.
 */
class DirectToolboxTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val action = intent.getStringExtra("action").orEmpty()
        if (action.isBlank()) {
            Log.w(TAG, "missing action")
            return
        }
        val payload = intent.getStringExtra("args").orEmpty()
        val out: String = try {
            runL0(context, action, payload)
        } catch (t: Throwable) {
            Log.e(TAG, "L0 error: $action", t)
            return
        }
        Log.i(TAG, "L0_RESULT action=$action $out")
    }

    private fun runL0(ctx: Context, action: String, args: String): String {
        // Args are a tiny key=value; pairs string, separated by '&'. We don't need
        // a full JSON parser for the smoke test — these tools have simple arg sets.
        val params = parseArgs(args)
        return when (action) {
            "device.audio.volume.get" -> {
                val stream = params["stream"]
                DirectToolbox.audioVolumeGet(ctx, stream).toString()
            }
            "device.audio.volume.set" -> {
                val stream = params["stream"] ?: return "missing_stream"
                val level = params["level"]?.toIntOrNull()
                val step = params["step"]?.toIntOrNull()
                DirectToolbox.audioVolumeSet(ctx, stream, level, step).toString()
            }
            "device.audio.ringer" -> {
                val mode = params["mode"]
                if (mode == null) DirectToolbox.ringerGet(ctx) else DirectToolbox.ringerSet(ctx, mode).orEmpty()
            }
            "device.media.sessions" -> DirectToolbox.mediaSessions(ctx).toString()
            "device.media.now_playing" -> {
                DirectToolbox.mediaNowPlaying(ctx, params["session_id"].orEmpty()).toString()
            }
            "device.media.control" -> {
                DirectToolbox.mediaControl(
                    ctx,
                    params["session_id"].orEmpty(),
                    params["action"].orEmpty(),
                    params["position_ms"]?.toLongOrNull(),
                ).toString()
            }
            "device.clipboard.get" -> when (val read = DirectToolbox.clipboardGet(ctx)) {
                // Print the variant, not just the text: on a real device this is how we confirm
                // whether Android refused the read or the clipboard was genuinely empty.
                is DirectToolbox.ClipRead.Text -> "text=\"${read.text}\""
                is DirectToolbox.ClipRead.Empty -> "empty"
                is DirectToolbox.ClipRead.NotText -> "not_text mime=${read.mime}"
                is DirectToolbox.ClipRead.Blocked -> "blocked (not focused / not default IME)"
            }
            "device.clipboard.set" -> {
                DirectToolbox.clipboardSet(ctx, params["text"].orEmpty()).toString()
            }
            "device.device.info" -> DirectToolbox.deviceInfo(ctx).toString()
            "device.notifications.nls" -> DirectToolbox.nlsStatus(ctx).toString()
            "device.notifications.nls.enable" -> DirectToolbox.nlsEnable(ctx).toString()
            "device.notifications.stream" -> {
                val sticky = StickyStore(ctx)
                val target = sticky.notifStreamTargetOrigin()
                val profile = target?.let { sticky.notifStreamProfileFor(it) }
                mapOf(
                    "enabled" to sticky.notifStreamEnabled,
                    "origin" to sticky.notifStreamOrigin,
                    "target_origin" to target,
                    "profile" to profile,
                    "nls_bound" to NotifStreamBus.listenerBound,
                ).toString()
            }
            "device.notifications.stream.set" -> {
                // Debug smoke test: poke the sticky store + reconfigure the bus, mirroring
                // what DeviceNodeCoordinator.setNotifStreamEnabled does in the production lane.
                val sticky = StickyStore(ctx)
                val enabledRaw = params["enabled"]
                if (enabledRaw != null) {
                    val parsed = when (enabledRaw.trim().lowercase()) {
                        "true", "1", "yes", "on" -> true
                        "false", "0", "no", "off" -> false
                        else -> "invalid_enabled"
                    }
                    if (parsed is Boolean) {
                        sticky.notifStreamEnabled = parsed
                        NotifStreamBus.configure(parsed, sticky.protectedPackages, ctx.packageName)
                    }
                }
                val originRaw = params["origin"]
                if (originRaw != null) {
                    sticky.notifStreamOrigin = originRaw.takeIf { it.isNotBlank() }
                }
                val profileRaw = params["profile"]
                val target = sticky.notifStreamTargetOrigin()
                if (target != null && profileRaw != null) {
                    sticky.setNotifStreamProfile(target, profileRaw.takeIf { it.isNotBlank() })
                }
                val prof = target?.let { sticky.notifStreamProfileFor(it) }
                mapOf(
                    "enabled" to sticky.notifStreamEnabled,
                    "origin" to sticky.notifStreamOrigin,
                    "target_origin" to target,
                    "profile" to prof,
                    "nls_bound" to NotifStreamBus.listenerBound,
                ).toString()
            }
            else -> "unknown_action:$action"
        }
    }

    private fun parseArgs(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }.toMap()
    }

    companion object {
        private const val TAG = "DirectToolboxTest"
        const val ACTION = "app.hermes.companion.test.L0"
    }
}
