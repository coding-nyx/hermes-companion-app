package app.hermes.companion.device

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.PairingPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceScreenRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun assertNoLocalControls() {
        listOf("device.arm.go", "device.a11y.enable", "device.notify.enable", "device.permission.a11y",
            "device.permission.overlay", "device.sms.open", "device.nls.enable").forEach {
            rule.onNodeWithTag(it).assertDoesNotExist()
        }
    }

    @Test fun unconnectedOffersHandsWithoutResurrectingLegacyControl() {
        var connections = 0; var opens = 0; var legacy = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                arm = DeviceArm.ARMED, armPending = true, a11yBound = true, notifyGranted = true, overlayGranted = true,
                onPair = {}, onCancel = {}, onRevoke = {}, onArm = { legacy++ }, onEnableA11y = { legacy++ },
                onEnableNls = { legacy++ }, onConnectHands = { connections++ }, onOpenHands = { opens++ })
        } }
        rule.onNodeWithTag("device.hands.status").assertTextContains("Connect Hands to enable mobile tools")
        assertNoLocalControls()
        rule.onNodeWithTag("device.hands.disarm").assertDoesNotExist()
        rule.onNodeWithTag("device.hands.connect").performScrollTo().performClick()
        rule.onNodeWithTag("device.hands.launch").performScrollTo().performClick()
        assertEquals(1, connections); assertEquals(1, opens); assertEquals(0, legacy)
        capture("device-hands-unconnected-light")
    }

    @Test fun connectedAndUnavailableStatusNeverOffersLocalFallback() {
        val reachable = mutableStateOf(true)
        var opens = 0; var arms = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                externalHands = true, externalHandsReachable = reachable.value,
                externalHandsBackend = "shizuku", externalHandsTarget = "agent",
                onPair = {}, onCancel = {}, onRevoke = {}, onArm = { arms++ }, onOpenHands = { opens++ })
        } }
        rule.onNodeWithTag("device.hands.status").assertTextContains("Connected · control off")
        rule.onNodeWithTag("device.hands.launch").performScrollTo().performClick()
        rule.runOnIdle { reachable.value = false }
        rule.onNodeWithTag("device.hands.status").assertTextContains("Hands unavailable · open the app to reconnect")
        rule.onNodeWithTag("device.hands.connect").assertDoesNotExist()
        assertNoLocalControls(); assertEquals(1, opens); assertEquals(0, arms)
    }

    @Test fun actualHandsArmOffersStopAndPrivacyPauseRemainsVisible() {
        val privacy = mutableStateOf(false)
        var stops = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                externalHands = true, externalHandsReachable = true, externalHandsArmed = true,
                externalHandsPrivacy = privacy.value, externalHandsA11y = false,
                onPair = {}, onCancel = {}, onRevoke = {}, onDisarm = { stops++ })
        } }
        rule.onNodeWithTag("device.hands.status").assertTextContains("Armed in Hands")
        rule.onNodeWithTag("device.hands.disarm").performScrollTo().performClick()
        assertEquals(1, stops)
        rule.runOnIdle { privacy.value = true }
        rule.onNodeWithTag("device.hands.status").assertTextContains("Private entry · remote access paused")
        assertNoLocalControls()
    }

    @Test fun gatewayPairingNeedsExplicitApprovalAndDoesNotGrantControl() {
        var approves = 0; var cancels = 0; var arms = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.WAITING, "123456", null, deviceProfileId = null, error = null,
                onPair = {}, onCancel = { cancels++ }, onRevoke = {}, onApprove = { approves++ }, onArm = { arms++ })
        } }
        rule.onNodeWithTag("device.pair.code").performScrollTo().assertTextContains("123456")
        assertEquals(0, approves)
        rule.onNodeWithTag("device.pair.approve").performScrollTo().performClick()
        rule.onNodeWithTag("device.pair.cancel").performScrollTo().performClick()
        assertEquals(1, approves); assertEquals(1, cancels); assertEquals(0, arms)
    }

    @Test fun gatewayIdentityRenameAndRepairRemainTransportOnly() {
        var name: String? = null; var repairs = 0; var arms = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceLabel = "Old name", deviceProfileId = "coder", error = null,
                onPair = {}, onCancel = {}, onRevoke = {}, onRenameDevice = { name = it },
                onRepair = { repairs++ }, onArm = { arms++ })
        } }
        rule.onNodeWithTag("device.identity").performScrollTo().performClick()
        rule.onNodeWithTag("device.label").performScrollTo().performTextReplacement("My phone")
        rule.onNodeWithTag("device.label.save").performScrollTo().performClick()
        assertEquals("My phone", name)
        rule.onNodeWithTag("device.repair").performScrollTo().performClick()
        assertEquals(1, repairs); assertEquals(0, arms)
        rule.onNodeWithTag("device.id").performScrollTo().assertTextContains("phone")
    }

    @Test fun pairingRemovalRequiresConfirmationAndCancelPreservesPairing() {
        var revoked = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                onPair = {}, onCancel = {}, onRevoke = { revoked++ })
        } }
        rule.onNodeWithTag("device.identity").performScrollTo().performClick()
        rule.onNodeWithTag("device.revoke").performScrollTo().performClick()
        assertEquals(0, revoked)
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("device.revoke").performScrollTo().performClick()
        rule.onNodeWithTag("device.revoke.confirm").performClick()
        assertEquals(1, revoked)
    }

    @Test fun notificationDestinationUsesExplicitGatewayAndProfileChoices() {
        var host: String? = null; var profile: String? = null; var opened = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                externalHands = true, notifStreamOrigin = "https://lab.example", notifStreamProfile = "assistant",
                pairedHosts = listOf("https://lab.example", "https://raj.example"), profilesForStream = listOf("assistant", "coder"),
                onPair = {}, onCancel = {}, onRevoke = {}, onPickStreamOrigin = { host = it },
                onPickStreamProfile = { profile = it }, onOpenHands = { opened++ })
        } }
        rule.onNodeWithTag("device.stream.route").performScrollTo().assertTextContains("https://lab.example\nassistant").performClick()
        rule.onNodeWithText("https://raj.example").performScrollTo().performClick()
        rule.onNodeWithText("coder", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("https://raj.example", host); assertEquals("coder", profile)
        rule.onNodeWithTag("device.details.close").performClick()
        rule.onNodeWithTag("device.stream.open").performScrollTo().performClick()
        assertEquals(1, opened); assertNoLocalControls()
    }

    @Test fun notificationForwardingCannotStartWithoutHandsAndDestination() {
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                onPair = {}, onCancel = {}, onRevoke = {})
        } }
        rule.onNodeWithTag("device.stream").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("device.stream.confirm").assertDoesNotExist()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val file = java.io.File("build/reports/device-render/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
