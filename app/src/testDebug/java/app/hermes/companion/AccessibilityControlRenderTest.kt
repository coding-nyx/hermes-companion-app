package app.hermes.companion

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.device.DeviceScreen
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.PairingPhase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class, qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityControlRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun unpairedUserOpensHandsInsteadOfTogglingCompanionAccessibility() {
        var opens = 0; var connections = 0; var local = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.IDLE, "", null, deviceProfileId = null, error = null,
                onPair = {}, onCancel = {}, onRevoke = {}, onEnableA11y = { local++ }, onDisableA11y = { local++ },
                onOpenHands = { opens++ }, onConnectHands = { connections++ })
        } }
        rule.onNodeWithTag("device.hands.launch").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("device.hands.connect").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("device.a11y.on").assertDoesNotExist()
        rule.onNodeWithTag("device.a11y.off").assertDoesNotExist()
        assertEquals(1, opens); assertEquals(1, connections); assertEquals(0, local)
    }

    @Test fun shizukuOwnerStatusAndStopDoNotDependOnCompanionAccessibility() {
        var stops = 0; var local = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "fixture", deviceProfileId = null, error = null,
                a11yBound = true, a11yListed = true, a11yOffRequested = true, arm = DeviceArm.DISARMED,
                externalHands = true, externalHandsReachable = true, externalHandsArmed = true,
                externalHandsBackend = "shizuku", externalHandsTarget = "agent", externalHandsA11y = false,
                onPair = {}, onCancel = {}, onRevoke = {}, onDisarm = { stops++ }, onDisableA11y = { local++ })
        } }
        rule.onNodeWithTag("device.hands.status").assertTextEquals("Armed in Hands")
        rule.onNodeWithTag("device.a11y.actual").assertDoesNotExist()
        rule.onNodeWithTag("device.hands.disarm").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("device.arm.go").assertDoesNotExist()
        assertEquals(1, stops); assertEquals(0, local)
    }
}
