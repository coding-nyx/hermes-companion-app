package app.hermes.companion.device

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.PairingPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Companion consent concerns a destination, not an Android permission grant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationForwardingRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "+night") fun darkConsentRequiresExplicitStart() = render("notification-forwarding-dark")
    @Test @Config(qualifiers = "+notnight") fun lightConsentRequiresExplicitStart() = render("notification-forwarding-light")
    @Test @Config(qualifiers = "+night") fun doubleTextConsentRemainsReadableAndCancelable() {
        RuntimeEnvironment.setFontScale(2f)
        render("notification-forwarding-large-text")
    }

    private fun render(name: String) {
        var forwards = 0; var localPermission = 0; var arms = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "assistant", error = null,
                externalHands = true, notifStreamOrigin = "https://lab.example", notifStreamProfile = "assistant",
                onPair = {}, onCancel = {}, onRevoke = {}, onToggleNotifStream = { forwards++ },
                onRequestSmsPermission = { localPermission++ }, onEnableNls = { localPermission++ }, onArm = { arms++ })
        } }
        rule.onNodeWithTag("device.stream").performScrollTo().performClick()
        assertEquals(0, forwards)
        rule.onNodeWithText("Gateway: https://lab.example", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Profile: assistant", substring = true).assertIsDisplayed()
        rule.onNodeWithText("This does not arm control.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("device.stream.confirm").assertIsDisplayed().assertIsEnabled()
        capture(name)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, forwards)
        rule.onNodeWithTag("device.stream").performScrollTo().performClick()
        rule.onNodeWithTag("device.stream.confirm").performClick()
        assertEquals(1, forwards); assertEquals(0, localPermission); assertEquals(0, arms)
        rule.onNodeWithTag("device.sms.open").assertDoesNotExist()
        rule.onNodeWithTag("device.nls.enable").assertDoesNotExist()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = android.graphics.Canvas(bitmap)
            android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown }.forEach { root ->
                val position = IntArray(2); root.getLocationOnScreen(position)
                canvas.save(); canvas.translate(position[0].toFloat(), position[1].toFloat()); root.draw(canvas); canvas.restore()
            }
        }
        val file = java.io.File("build/reports/device-render/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
