package app.hermes.companion.device

import android.app.ActivityManager
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31])
class BatteryBackgroundPolicyTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun statusReadsCurrentAndroidStateWithoutCachingAnApprovedChoice() {
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        val activity = shadowOf(app.getSystemService(ActivityManager::class.java))
        power.setIgnoringBatteryOptimizations(app.packageName, false)
        power.setIsPowerSaveMode(true)
        activity.setBackgroundRestricted(true)
        assertEquals(BatteryBackgroundPolicy.Status(false, true, true), BatteryBackgroundPolicy.status(app))
        power.setIgnoringBatteryOptimizations(app.packageName, true)
        power.setIsPowerSaveMode(false)
        activity.setBackgroundRestricted(false)
        assertEquals(BatteryBackgroundPolicy.Status(true, false, false), BatteryBackgroundPolicy.status(app))
    }

    @Test fun requestTargetsThisExactApplicationPackageAndDoesNotGrantAnything() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, false)
        val intent = BatteryBackgroundPolicy.requestIntent(app)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val context = Launcher(app)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, BatteryBackgroundPolicy.openSettings(context))
        assertFalse("Opening OS confirmation is not approval", BatteryBackgroundPolicy.status(app).ignoringBatteryOptimizations)
        assertEquals(1, context.intents.size)
    }

    @Test fun existingExemptionOpensManagementInsteadOfAskingAgain() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        val context = Launcher(app)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, BatteryBackgroundPolicy.openSettings(context))
        assertEquals(listOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), context.intents.map { it.action })
    }

    @Test fun unsupportedOrDeniedRequestFallsBackToSettingsAndThenAppDetails() {
        val context = Launcher(app) { intent -> when (intent.action) {
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> throw SecurityException("request permission missing")
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> throw ActivityNotFoundException()
        } }
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, BatteryBackgroundPolicy.openSettings(context))
        assertEquals("package:${app.packageName}", context.intents.last().dataString)
        assertFalse(BatteryBackgroundPolicy.status(app).ignoringBatteryOptimizations)
    }

    @Test fun noSettingsHandlerReportsFailureWithoutClaimingAnExemption() {
        val context = Launcher(app) { throw ActivityNotFoundException() }
        assertNull(BatteryBackgroundPolicy.openSettings(context))
        assertFalse(BatteryBackgroundPolicy.openAppSettings(context))
        assertFalse(BatteryBackgroundPolicy.status(app).ignoringBatteryOptimizations)
    }

    private class Launcher(base: Context, val behavior: (Intent) -> Unit = {}) : ContextWrapper(base) {
        val intents = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            intents.add(intent)
            behavior(intent)
        }
    }
}
