package app.hermes.companion.device

import android.Manifest
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.LocationManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * L0 read surfaces: calendar, location, battery, power, thermal, app usage — plus the one
 * write that belongs with them, Do Not Disturb.
 *
 * The rule these all follow, learned from the clipboard defect in A29.1: **never degrade to
 * empty data**. A missing permission or an unavailable provider raises a named failure so the
 * agent is told "you cannot see this" rather than being handed an empty list it would report
 * to the user as "you have no events today".
 */
object SystemToolbox {

    /** A permission the user has not granted, named so the agent can ask for the right one. */
    data class Denied(val permission: String) : Exception("missing $permission")

    /** A capability the phone or OS version does not offer at all. */
    class Unavailable(message: String) : Exception(message)

    private fun require(ctx: Context, permission: String) {
        if (ContextCompat.checkSelfPermission(ctx, permission) != PackageManager.PERMISSION_GRANTED) {
            throw Denied(permission)
        }
    }

    private fun <T> Cursor.map(limit: Int, row: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(minOf(limit, 64))
        use { c -> while (c.moveToNext() && out.size < limit) out.add(row(c)) }
        return out
    }

    private fun Cursor.str(i: Int): String = if (i < 0 || isNull(i)) "" else getString(i).orEmpty()
    private fun Cursor.long(i: Int): Long = if (i < 0 || isNull(i)) 0L else getLong(i)

    // ---------- calendar ----------

    /**
     * Events overlapping the window [fromMs, toMs), soonest first. Defaults to the next 7 days.
     *
     * Uses `CalendarContract.Instances` rather than `Events`: a recurring event is a single row
     * in `Events` with an RRULE, so querying that table returns the series definition instead of
     * "what is actually on today" — which is what an agent is being asked.
     */
    fun calendarList(
        ctx: Context,
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int = 20,
    ): List<Map<String, Any?>> {
        require(ctx, Manifest.permission.READ_CALENDAR)
        val start = fromMs ?: System.currentTimeMillis()
        val end = toMs ?: (start + 7L * 24 * 60 * 60 * 1000)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString())
            .appendPath(end.toString())
            .build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val cursor = ctx.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?: throw Unavailable("calendar provider returned nothing")
        return cursor.map(limit.coerceIn(1, 200)) { c ->
            mapOf(
                "event_id" to c.long(c.getColumnIndex(CalendarContract.Instances.EVENT_ID)),
                "title" to c.str(c.getColumnIndex(CalendarContract.Instances.TITLE)),
                "begin_ms" to c.long(c.getColumnIndex(CalendarContract.Instances.BEGIN)),
                "end_ms" to c.long(c.getColumnIndex(CalendarContract.Instances.END)),
                "location" to c.str(c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)),
                "all_day" to (c.long(c.getColumnIndex(CalendarContract.Instances.ALL_DAY)) == 1L),
                "calendar" to c.str(c.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)),
            )
        }
    }

    // ---------- location ----------

    /**
     * The most recent fix any enabled provider already holds. Deliberately does not *request* a
     * new fix: that needs a callback, costs power, and can take tens of seconds — an agent
     * asking "where is the phone" almost always wants the last known position.
     *
     * Reports `age_ms` so a stale fix cannot be mistaken for a current one.
     */
    fun locationLast(ctx: Context): Map<String, Any?> {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) throw Denied(Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = ctx.getSystemService(LocationManager::class.java)
            ?: throw Unavailable("no location service")
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        if (providers.isEmpty()) throw Unavailable("location is turned off on the phone")
        var best: android.location.Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        val l = best ?: throw Unavailable("no provider has a stored fix yet")
        return mapOf(
            "latitude" to l.latitude,
            "longitude" to l.longitude,
            "accuracy_m" to l.accuracy,
            "provider" to l.provider.orEmpty(),
            "time_ms" to l.time,
            "age_ms" to (System.currentTimeMillis() - l.time),
            "precise" to fine,
        )
    }

    // ---------- battery / power / thermal ----------

    fun battery(ctx: Context): Map<String, Any?> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
            ?: throw Unavailable("no battery service")
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return mapOf(
            "percent" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "charging" to (
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                ),
            "status" to batteryStatusName(status),
            "power_source" to pluggedName(plugged),
            "temperature_c" to if (tempTenths > 0) tempTenths / 10.0 else -1.0,
            "health" to healthName(intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1),
        )
    }

    private fun batteryStatusName(v: Int) = when (v) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun pluggedName(v: Int) = when (v) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "battery"
        else -> "unknown"
    }

    private fun healthName(v: Int) = when (v) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        else -> "unknown"
    }

    /**
     * Whether the screen is on, and the power-saving states.
     *
     * `interactive` is the one an agent should check before planning an L1 gesture run: driving
     * UI on a phone with the screen off usually does nothing useful.
     */
    fun powerState(ctx: Context): Map<String, Any?> {
        val pm = ctx.getSystemService(PowerManager::class.java)
            ?: throw Unavailable("no power service")
        return mapOf(
            "interactive" to pm.isInteractive,
            "power_save_mode" to pm.isPowerSaveMode,
            "device_idle" to pm.isDeviceIdleMode,
            "ignoring_battery_optimizations" to
                runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false),
        )
    }

    /** Thermal throttling status. Requires API 29; `minSdk` here is 31 so it is always present. */
    fun thermal(ctx: Context): Map<String, Any?> {
        val pm = ctx.getSystemService(PowerManager::class.java)
            ?: throw Unavailable("no power service")
        val status = pm.currentThermalStatus
        return mapOf(
            "status" to thermalName(status),
            "level" to status,
            // Above LIGHT the system is actively throttling; a long a11y run will crawl.
            "throttling" to (status >= PowerManager.THERMAL_STATUS_MODERATE),
        )
    }

    private fun thermalName(v: Int) = when (v) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    // ---------- app usage ----------

    /**
     * Foreground time per app over the last [sinceMs] milliseconds (default 24h), busiest first.
     *
     * `PACKAGE_USAGE_STATS` is a *special* permission: it cannot be granted by `pm grant` in the
     * ordinary way and does not appear in the app's permission list — the user toggles it under
     * Settings → Special app access → Usage access. An ungranted app gets an empty list rather
     * than a SecurityException, so this checks for emptiness and reports the grant explicitly
     * instead of claiming the user used nothing.
     */
    fun appUsage(ctx: Context, sinceMs: Long? = null, limit: Int = 20): List<Map<String, Any?>> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
            ?: throw Unavailable("no usage-stats service")
        val end = System.currentTimeMillis()
        val start = end - (sinceMs ?: (24L * 60 * 60 * 1000))
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
        }.getOrNull().orEmpty()
        if (stats.isEmpty()) {
            throw Denied("android.permission.PACKAGE_USAGE_STATS (Settings > Special app access > Usage access)")
        }
        return stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit.coerceIn(1, 200))
            .map {
                mapOf(
                    "package" to it.packageName,
                    "foreground_ms" to it.totalTimeInForeground,
                    "last_used_ms" to it.lastTimeUsed,
                )
            }
    }

    // ---------- do not disturb ----------

    fun dndGet(ctx: Context): Map<String, Any?> {
        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: throw Unavailable("no notification service")
        return mapOf(
            "filter" to filterName(nm.currentInterruptionFilter),
            "policy_access_granted" to nm.isNotificationPolicyAccessGranted,
        )
    }

    /**
     * Set the interruption filter. Needs `ACCESS_NOTIFICATION_POLICY`, which is a *special*
     * permission the user grants in Settings — so this checks first and names the settings page,
     * rather than letting the platform silently ignore the change.
     */
    fun dndSet(ctx: Context, mode: String): Map<String, Any?> {
        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: throw Unavailable("no notification service")
        if (!nm.isNotificationPolicyAccessGranted) {
            throw Denied("Do Not Disturb access (Settings > Notifications > Do Not Disturb access)")
        }
        val target = when (mode.trim().lowercase(Locale.ROOT)) {
            "all", "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority", "on" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "none", "total" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> throw IllegalArgumentException("mode must be all/priority/none/alarms")
        }
        nm.setInterruptionFilter(target)
        return mapOf(
            "filter" to filterName(nm.currentInterruptionFilter),
            "requested" to filterName(target),
        )
    }

    private fun filterName(v: Int) = when (v) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        else -> "unknown"
    }

    fun errorCode(t: Throwable?): String = when (t) {
        is Denied -> "permission_denied"
        is Unavailable -> "unavailable"
        is IllegalArgumentException -> "missing_args"
        is SecurityException -> "permission_denied"
        else -> "query_failed"
    }

    fun errorMessage(t: Throwable?): String = when (t) {
        is Denied -> "grant ${t.permission}"
        else -> t?.message ?: "query failed"
    }
}
