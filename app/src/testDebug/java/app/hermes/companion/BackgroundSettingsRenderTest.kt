package app.hermes.companion

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.device.BatteryBackgroundPolicy
import app.hermes.companion.settings.SettingsBottomSheet
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild

/** Synthetic OS state; no system permission is changed or granted by these UI fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31], qualifiers = "w360dp-h800dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class BackgroundSettingsRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun mount(exempt: Boolean, saver: Boolean, restricted: Boolean, scale: Float = 1f) {
        ShadowBuild.setManufacturer("samsung")
        val power = shadowOf(rule.activity.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(rule.activity.packageName, exempt)
        power.setIsPowerSaveMode(saver)
        shadowOf(rule.activity.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(restricted)
        RuntimeEnvironment.setFontScale(scale)
        rule.activity.actionBar?.hide()
        rule.setContent {
            CompanionTheme { CompositionLocalProvider(LocalDensity provides Density(2f, scale)) {
                SettingsBottomSheet(
                    state = CompanionState(origin = "https://fixture.invalid", stayConnected = true),
                    onSelectProfile = {}, onSwitchModel = { _, _ -> }, onOpenModelPicker = {}, onTab = {},
                    onToggleStay = {}, onNtfyTopicChange = {}, onSaveNtfy = {}, onDisconnect = {}, onDismiss = {},
                )
            } }
        }
        rule.onNodeWithTag("settings.background.open").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @After fun resetFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun darkRestrictedStateOffersAnExplicitOsRequestWithoutClaimingApproval() {
        mount(exempt = false, saver = true, restricted = true)
        rule.onNodeWithTag("settings.stay").performScrollTo().assertIsDisplayed()
        capture("background-settings-dark-top")
        rule.onNodeWithTag("settings.battery.open").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            shadowOf(rule.activity).nextStartedActivity.action)
        assertFalse(BatteryBackgroundPolicy.status(rule.activity).ignoringBatteryOptimizations)
        rule.onNodeWithText("Optimized").assertExists()
        rule.onNodeWithTag("settings.background.restriction").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Restricted").assertExists()
        capture("background-settings-dark-policy")
        rule.onNodeWithTag("settings.battery.samsung").performScrollTo().assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w360dp-h800dp-notnight-xhdpi")
    fun lightExemptStateRefreshesFromAndroidWhenTheActivityResumes() {
        mount(exempt = true, saver = false, restricted = false)
        rule.onNodeWithTag("settings.battery.open").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Exempt").assertExists()
        capture("background-settings-light-exempt")
        shadowOf(rule.activity.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(rule.activity.packageName, false)
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithText("Exempt").assertDoesNotExist()
        rule.onNodeWithTag("settings.battery.open").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Optimized").assertExists()
        capture("background-settings-light-return")
    }

    @Test fun doubleTextKeepsAllBackgroundActionsAndTopicFormReachable() {
        mount(exempt = false, saver = true, restricted = true, scale = 2f)
        rule.onNodeWithTag("settings.battery.open").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        capture("background-settings-large-text-battery")
        rule.onNodeWithTag("settings.background.restriction").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        capture("background-settings-large-text-restriction")
        rule.onNodeWithTag("settings.battery.samsung").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("settings.ntfy.input").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("settings.ntfy.save").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        capture("background-settings-large-text-bottom")
    }

    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val decor = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = Canvas(bitmap)
            android.view.inspector.WindowInspector.getGlobalWindowViews()
                .filter { it.isShown && it.width > 0 }.forEach { it.draw(canvas) }
        }
        val configured = System.getProperty("hermes.render.dir") ?: System.getenv("HERMES_RENDER_DIR")
        val root = configured?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("java.io.tmpdir"), "hermes-background-settings-render")
        File(root, "$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
