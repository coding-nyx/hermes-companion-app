package app.hermes.companion.device

import android.app.AppOpsManager
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.os.Process
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DisplayToolboxTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val resolver get() = app.contentResolver

    @Before fun allowWrites() { setWriteAccess(true) }

    private fun setWriteAccess(allowed: Boolean) {
        val ops = app.getSystemService(AppOpsManager::class.java)
        shadowOf(ops).setMode(
            AppOpsManager.OPSTR_WRITE_SETTINGS, Process.myUid(), app.opPackageName,
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_ERRORED,
        )
    }

    private fun expectFailure(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (e: DisplayToolbox.Failure) {
            assertEquals(code, e.code)
        }
    }

    @Test fun readsSavedLevelAndAdaptiveModeWithoutWriteAccess() {
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        setWriteAccess(false)

        val result = DisplayToolbox.brightnessGet(app)

        assertEquals(50, result["level"])
        assertEquals(128, result["raw_level"])
        assertEquals(true, result["auto"])
        assertEquals(false, result["can_write"])
        assertEquals("system_setting", result["level_source"])
    }

    @Test fun missingWriteAccessDoesNotMutateSettings() {
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        setWriteAccess(false)

        expectFailure("permission_denied") { DisplayToolbox.brightnessSet(app, level = 75) }

        assertEquals(128, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test fun manualPercentageDisablesAdaptiveBrightnessAndPreservesScreenTimeout() {
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 15_000)

        val result = DisplayToolbox.brightnessSet(app, level = 75)

        assertEquals(75, result["level"])
        assertEquals(191, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
        assertEquals(false, result["auto"])
        assertEquals(15_000, Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT))
    }

    @Test fun adaptiveTogglePreservesSavedManualLevel() {
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 80)

        assertEquals(true, DisplayToolbox.brightnessSet(app, auto = true)["auto"])
        assertEquals(80, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
        assertEquals(false, DisplayToolbox.brightnessSet(app, auto = false)["auto"])
        assertEquals(80, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test fun invalidInputsFailBeforeChangingSettings() {
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 80)

        for (level in listOf(-1, 0, 101)) {
            expectFailure("bad_args") { DisplayToolbox.brightnessSet(app, level = level) }
        }
        expectFailure("bad_args") { DisplayToolbox.brightnessSet(app) }
        expectFailure("bad_args") { DisplayToolbox.brightnessSet(app, level = 50, auto = true) }

        assertEquals(80, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test fun maximumAndMinimumManualLevelsStayInAndroidRange() {
        assertEquals(255, DisplayToolbox.brightnessSet(app, level = 100)["raw_level"])
        assertEquals(3, DisplayToolbox.brightnessSet(app, level = 1)["raw_level"])
    }

    @Test
    @Config(shadows = [RefusedBrightnessSettings::class])
    fun refusedSettingsWriteIsNotReportedAsSuccess() {
        expectFailure("settings_failed") { DisplayToolbox.brightnessSet(app, level = 50) }
    }

    @Implements(Settings.System::class)
    class RefusedBrightnessSettings {
        companion object {
            @JvmStatic @Implementation
            fun canWrite(context: Context): Boolean = true

            @JvmStatic @Implementation
            fun putInt(resolver: ContentResolver, name: String, value: Int): Boolean = false
        }
    }
}
