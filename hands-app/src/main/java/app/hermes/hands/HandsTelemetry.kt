package app.hermes.hands

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.device.DirectToolbox
import app.hermes.companion.device.SystemToolbox
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gathers complete real-time device health, sensor, battery, network, media,
 * notification, and display context on demand for paired agents.
 */
object HandsTelemetry {

    fun collect(
        context: Context,
        runtime: HandsRuntime,
        includeMedia: Boolean = true,
        includeNotifications: Boolean = true,
    ): JSONObject {
        val root = JSONObject()

        // 1. Battery
        runCatching { SystemToolbox.battery(context) }.getOrNull()?.let {
            root.put("battery", JSONObject(it))
        }

        // 2. Power, Display & Lock status
        val km = context.getSystemService(KeyguardManager::class.java)
        val power = runCatching { SystemToolbox.powerState(context) }.getOrNull()
        val screenJson = JSONObject()
        if (power != null) {
            screenJson.put("interactive", power["interactive"])
            screenJson.put("power_save_mode", power["power_save_mode"])
            screenJson.put("device_idle", power["device_idle"])
        }
        screenJson.put("locked", km?.isKeyguardLocked ?: false)
        screenJson.put("secure", km?.isKeyguardSecure ?: false)
        runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrNull()?.let { screenJson.put("screen_off_timeout_ms", it) }
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN)
        }.getOrNull()?.let { screenJson.put("stay_on_while_plugged_in", it) }
        root.put("power", screenJson)

        // 3. Thermal status
        runCatching { SystemToolbox.thermal(context) }.getOrNull()?.let {
            root.put("thermal", JSONObject(it))
        }

        // 4. Connectivity / Network
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val activeNet = cm?.activeNetwork
        val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
        val netJson = JSONObject()
        if (caps != null) {
            netJson.put("connected", true)
            netJson.put("wifi", caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            netJson.put("cellular", caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            netJson.put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            netJson.put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        } else {
            netJson.put("connected", false)
        }
        netJson.put("remote_endpoints", JSONArray(runtime.remoteAccess.value.endpoints))
        root.put("network", netJson)

        // 5. Audio, Ringer & DND
        val audioJson = JSONObject()
        audioJson.put("ringer", DirectToolbox.ringerGet(context))
        val nm = context.getSystemService(NotificationManager::class.java)
        val dndFilter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        audioJson.put("dnd", when (dndFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            else -> "unknown"
        })
        runCatching { DirectToolbox.audioVolumeGet(context, null) }.getOrNull()?.let {
            audioJson.put("volumes", JSONArray(it))
        }
        root.put("audio", audioJson)

        // 6. Media Sessions & Now Playing
        if (includeMedia) {
            val sessions = runCatching { DirectToolbox.mediaSessions(context) }.getOrNull() ?: emptyList()
            val mediaArray = JSONArray()
            for (s in sessions) {
                val pkg = s["package"] as? String ?: continue
                val item = JSONObject(s)
                val nowPlaying = runCatching { DirectToolbox.mediaNowPlaying(context, pkg) }.getOrNull()
                if (nowPlaying != null) item.put("now_playing", JSONObject(nowPlaying))
                mediaArray.put(item)
            }
            root.put("media", mediaArray)
        }

        // 7. Foreground App
        val st = runtime.state.value
        val foregroundPkg = if (st.backend == "shizuku" || runtime.shizuku.status.value.bound) {
            runtime.shizuku.foregroundPackage(if (st.target == "agent" && st.displayId > 0) st.displayId else 0)
        } else {
            CompanionAccessibilityService.foregroundApp
        }
        if (!foregroundPkg.isNullOrBlank()) {
            root.put("foreground_package", foregroundPkg)
        }

        // 8. Notifications
        if (includeNotifications) {
            root.put("notifications", JSONArray(runtime.notifications.recentSummaries(5)))
        }

        // 9. Hands Runtime Status
        val handsJson = JSONObject()
            .put("serving", st.serving)
            .put("armed", st.armed)
            .put("target", st.target)
            .put("backend", st.backend)
            .put("display_id", st.displayId)
            .put("lease_remaining_seconds", runtime.leaseRemainingSeconds())
        root.put("hands", handsJson)

        return root
    }
}
