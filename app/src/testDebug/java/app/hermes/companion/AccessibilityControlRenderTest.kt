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

    @Test fun unpairedUserCanTurnServiceOnAndOffSeparatelyFromArm() {
        var on = 0
        var off = 0
        rule.setContent {
            CompanionTheme {
                DeviceScreen(phase = PairingPhase.IDLE, code = "", deviceId = null, deviceProfileId = null,
                    error = null, a11yBound = false, a11yListed = false,
                    onPair = {}, onCancel = {}, onRevoke = {}, onEnableA11y = { on++ }, onDisableA11y = { off++ })
            }
        }
        rule.onNodeWithTag("device.a11y.actual").assertTextEquals("Off")
        rule.onNodeWithTag("device.a11y.on").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("device.a11y.off").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, on); assertEquals(1, off) }
        rule.onNodeWithTag("device.arm.go").assertDoesNotExist()
    }

    @Test fun offRequestDoesNotFalselyDisplayDisconnectedWhileServiceStillConnected() {
        rule.setContent {
            CompanionTheme {
                DeviceScreen(phase = PairingPhase.PAIRED, code = "", deviceId = "fixture", deviceProfileId = null,
                    error = null, a11yBound = true, a11yListed = true, a11yOffRequested = true,
                    arm = DeviceArm.DISARMED, onPair = {}, onCancel = {}, onRevoke = {})
            }
        }
        rule.onNodeWithTag("device.a11y.actual").assertTextEquals("Connected")
        rule.onNodeWithText("OFF requested.", substring = true).assertExists()
        rule.onNodeWithTag("device.arm.go").assertDoesNotExist()
    }
}
