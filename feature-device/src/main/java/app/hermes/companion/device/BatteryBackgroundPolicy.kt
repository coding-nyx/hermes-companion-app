package app.hermes.companion.device

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Reads Android policy and opens the operator's OS controls; never grants an exemption itself. */
object BatteryBackgroundPolicy {
    data class Status(
        val ignoringBatteryOptimizations: Boolean,
        val powerSaveMode: Boolean,
        val backgroundRestricted: Boolean,
    )

    fun status(context: Context): Status {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        return Status(
            ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
            powerSaveMode = power?.isPowerSaveMode == true,
            backgroundRestricted = activity?.isBackgroundRestricted == true,
        )
    }

    /** Requires the manifest's normal REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission. */
    fun requestIntent(context: Context): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsIntent(context: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Returns the page opened, not whether the operator approved it. Re-read [status] on resume. */
    fun openSettings(context: Context): String? {
        val candidates = buildList {
            if (!status(context).ignoringBatteryOptimizations) add(requestIntent(context))
            add(settingsIntent())
            add(appDetailsIntent(context))
        }
        return candidates.firstOrNull { launch(context, it) }?.action
    }

    fun openAppSettings(context: Context): Boolean = launch(context, appDetailsIntent(context))

    private fun launch(context: Context, intent: Intent): Boolean = try {
        // Starting directly avoids package-visibility false negatives from resolveActivity().
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
