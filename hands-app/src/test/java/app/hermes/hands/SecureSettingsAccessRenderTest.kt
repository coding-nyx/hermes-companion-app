package app.hermes.hands

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.hermes.hands.engine.ShizukuStatus
import app.hermes.hands.ui.HandsTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecureSettingsAccessRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(granted: Boolean, copied: () -> Unit = {}, refreshed: () -> Unit = {},
        accessibility: Boolean? = null, onToggle: (Boolean) -> Unit = {}) {
        rule.setContent { HandsTheme {
            HandsScreen(HandsState(serving = true), ShizukuStatus(), emptyList(),
                onStart = {}, onStopService = {}, onAllow = {}, onDisarm = {}, onBackend = {}, onTarget = {},
                onShizuku = {}, onOpenShizuku = {}, onAccessibility = {}, onAccessibilityToggle = onToggle, onOverlay = {}, onStartScreen = {},
                onCreateToken = {}, onRevoke = {}, onProtect = {}, initialTab = 2,
                permissions = listOfNotNull(accessibility?.let { HandsPermission("accessibility", "Accessibility", "Phone screen engine", it, true) },
                    HandsPermission("secure_settings", "Secure settings access",
                    "Allows an armed agent to manage supported settings after you grant access using ADB.", granted, true)),
                onPermission = { fail("Android app settings cannot grant this permission") },
                onCopySecureGrant = copied, onRefreshPermissions = refreshed)
        } }
    }
    private fun review(name: String) {
        var copies = 0
        var refreshes = 0
        show(false, copied = { copies++ }, refreshed = { refreshes++ })
        rule.onNodeWithTag("hands.secure_settings.command").performScrollTo().assertIsDisplayed()
            .assertTextContains("android.permission.WRITE_SECURE_SETTINGS", substring = true)
        rule.onNodeWithTag("hands.secure_settings.copy").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, copies)
        rule.onNodeWithTag("hands.secure_settings.refresh").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, refreshes)
        rule.onNodeWithTag("hands.permission.secure_settings").assertDoesNotExist()
        capture(name)
    }
    @Test fun setupLight() = review("secure-settings-light")
    @Test @Config(qualifiers = "w360dp-h800dp-night-xhdpi") fun setupDark() = review("secure-settings-dark")
    @Test @Config(qualifiers = "w320dp-h720dp-xhdpi") fun setupLargeLight() {
        RuntimeEnvironment.setFontScale(2f); review("secure-settings-large-light")
    }
    @Test @Config(qualifiers = "w320dp-h720dp-night-xhdpi") fun setupLargeDark() {
        RuntimeEnvironment.setFontScale(2f); review("secure-settings-large-dark")
    }
    @Test fun grantedDoesNotOfferAnImpossibleAndroidGrant() {
        show(true)
        rule.onNodeWithText("Allowed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("hands.secure_settings.copy").assertDoesNotExist()
        rule.onNodeWithTag("hands.permission.secure_settings").assertDoesNotExist()
    }
    @Test fun accessibilityCanBeEnabledFromAccess() {
        var requested: Boolean? = null
        show(true, accessibility = false, onToggle = { requested = it })
        rule.onNodeWithTag("hands.accessibility.toggle").performScrollTo()
            .assertContentDescriptionEquals("Hands Accessibility").assertIsOff().performClick()
        assertEquals(true, requested)
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(Canvas(bitmap)) }
        File("build/reports/hands-secure-settings/$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
