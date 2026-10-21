package app.hermes.companion.device

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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

    @Test fun screenControlCannotBeArmedBeforeRequiredPermissions() {
        var armed = 0
        var accessibility = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                onPair = {}, onCancel = {}, onRevoke = {}, onArm = { armed++ }, onEnableA11y = { accessibility++ })
        } }
        rule.onNodeWithTag("device.arm.go").assertDoesNotExist()
        rule.onNodeWithTag("device.a11y.enable").performScrollTo().performClick()
        assertEquals(1, accessibility)
        assertEquals(0, armed)
        rule.onNodeWithTag("device.nextAction").assertTextContains("Next: allow accessibility for screen control.")
        capture("device-control-permission-next-action")
    }

    @Test fun notificationsRemainTheNextStepAfterAccessibility() {
        var notifications = 0
        var armed = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                a11yBound = true, onPair = {}, onCancel = {}, onRevoke = {}, onArm = { armed++ },
                onEnableNotify = { notifications++ })
        } }
        rule.onNodeWithTag("device.arm.go").assertDoesNotExist()
        rule.onNodeWithTag("device.notify.enable").performScrollTo().performClick()
        assertEquals(1, notifications)
        assertEquals(0, armed)
    }

    @Test fun grantingPermissionsNeverArmsHandsAutomatically() {
        var arms = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                a11yBound = true, notifyGranted = true, overlayGranted = true,
                onPair = {}, onCancel = {}, onRevoke = {}, onArm = { arms++ })
        } }
        assertEquals(0, arms)
        rule.onNodeWithTag("device.arm").assertTextContains("Screen control is off")
        rule.onNodeWithTag("device.arm.go").performScrollTo().performClick()
        assertEquals(1, arms)
    }

    @Test fun armedStateAlwaysOffersStopEvenWhenPermissionWasLost() {
        var stops = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                arm = DeviceArm.ARMED, onPair = {}, onCancel = {}, onRevoke = {}, onDisarm = { stops++ })
        } }
        rule.onNodeWithTag("device.disarm").performScrollTo().performClick()
        assertEquals(1, stops)
        rule.onNodeWithTag("device.arm.go").assertDoesNotExist()
    }

    @Test fun permissionUpdatesAdvanceTheNextActionWithoutArming() {
        val accessibility = androidx.compose.runtime.mutableStateOf(false)
        val notifications = androidx.compose.runtime.mutableStateOf(false)
        var arms = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                a11yBound = accessibility.value, notifyGranted = notifications.value,
                onPair = {}, onCancel = {}, onRevoke = {}, onArm = { arms++ })
        } }
        rule.onNodeWithTag("device.a11y.enable").assertExists()
        rule.runOnIdle { accessibility.value = true }
        rule.onNodeWithTag("device.a11y.enable").assertDoesNotExist()
        rule.onNodeWithTag("device.notify.enable").assertExists()
        rule.runOnIdle { notifications.value = true }
        rule.onNodeWithTag("device.notify.enable").assertDoesNotExist()
        rule.onNodeWithTag("device.arm.go").assertExists()
        assertEquals(0, arms)
    }

    @Test fun notificationDestinationIsOneTapFromDeviceOverview() {
        var host: String? = null
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                notifStreamEnabled = true, nlsBound = true, notifStreamOrigin = "https://lab.example",
                notifStreamProfile = "assistant", pairedHosts = listOf("https://lab.example", "https://raj.example"),
                profilesForStream = listOf("assistant", "coder"),
                onPair = {}, onCancel = {}, onRevoke = {}, onPickStreamOrigin = { host = it })
        } }
        rule.onNodeWithTag("device.stream.route").performScrollTo().assertTextContains("https://lab.example\nassistant").performClick()
        rule.onNodeWithText("https://raj.example").performClick()
        assertEquals("https://raj.example", host)
        capture("device-notification-routing")
    }

    @Test fun enabledStreamWithMissingAccessShowsExplicitRecovery() {
        var access = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                notifStreamEnabled = true, nlsBound = false, onPair = {}, onCancel = {}, onRevoke = {},
                onEnableNls = { access++ })
        } }
        rule.onNodeWithTag("device.stream.open").performScrollTo().assertTextContains("Needs access")
        rule.onNodeWithTag("device.nls.enable").performScrollTo().performClick()
        assertEquals(1, access)
        capture("device-notifications-needs-access")
    }

    @Test fun permissionChecklistReportsStateAndOpensAndroidSettingsDirectly() {
        var overlay = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                a11yBound = true, notifyGranted = true, onPair = {}, onCancel = {}, onRevoke = {},
                onEnableOverlay = { overlay++ })
        } }
        rule.onNodeWithTag("device.permission.overlay").performScrollTo().assertTextContains("Not allowed").performClick()
        assertEquals(1, overlay)
        rule.onNodeWithTag("device.permissions.count").performScrollTo().assertTextContains("2 / 3")
        capture("device-permission-checklist")
    }

    @Test fun optionalIndicatorAccessDoesNotChangeExistingArmEligibility() {
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                a11yBound = true, notifyGranted = true, overlayGranted = false,
                onPair = {}, onCancel = {}, onRevoke = {})
        } }
        rule.onNodeWithTag("device.arm.go").assertExists()
        rule.onNodeWithTag("device.nextAction").assertTextContains("Control access is ready. Allow the overlay to show the control indicator.")
    }

    @Test fun largeTextKeepsPermissionStatusAndDirectActionReadable() {
        var opened = 0
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                CompanionTheme {
                    DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "research-agent", error = null,
                        a11yBound = true, onPair = {}, onCancel = {}, onRevoke = {}, onEnableOverlay = { opened++ })
                }
            }
        }
        rule.onNodeWithTag("device.permission.overlay").performScrollTo().assertIsDisplayed().assertTextContains("Not allowed").performClick()
        assertEquals(1, opened)
        capture("device-permissions-large-text")
    }

    @Test fun lockScreenPreferenceExplainsAndroidAuthenticationBoundary() {
        var requested = 0
        var armed = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                onPair = {}, onCancel = {}, onRevoke = {}, onArm = { armed++ }, onToggleLockedAccess = { requested++ })
        } }
        rule.onNodeWithTag("device.locked.limit").performScrollTo()
            .assertTextContains("Screen actions may require you to unlock. The background gateway connection is separate.")
        rule.onNodeWithTag("device.permissions.open").performScrollTo().performClick()
        rule.onNodeWithTag("device.locked.access").performScrollTo()
            .assertTextContains("Ask Android to dismiss the lock screen when Companion opens. Android may still require you to unlock. Does not bypass PIN or biometrics.")
            .performClick()
        assertEquals(1, requested)
        assertEquals(0, armed)
    }

    @Test fun pairingRemovalStillRequiresConfirmation() {
        var revoked = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                onPair = {}, onCancel = {}, onRevoke = { revoked++ })
        } }
        rule.onNodeWithTag("device.status").performClick()
        rule.onNodeWithTag("device.revoke").performScrollTo().performClick()
        assertEquals(0, revoked)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, revoked)
        rule.onNodeWithTag("device.revoke").performClick()
        rule.onNodeWithTag("device.revoke.confirm").performClick()
        assertEquals(1, revoked)
    }

    @Test
    @Config(sdk = [31], qualifiers = "w360dp-h720dp-night-xhdpi")
    fun darkControlOverviewRendersWithExplicitDisarmedState() {
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceLabel = "Pixel 9", deviceProfileId = "research", error = null,
                laneOpen = true, a11yBound = true, notifyGranted = true, overlayGranted = true,
                notifStreamEnabled = true, nlsBound = true, notifStreamOrigin = "https://lab.example", notifStreamProfile = "assistant",
                onPair = {}, onCancel = {}, onRevoke = {})
        } }
        rule.onNodeWithTag("device.arm").assertTextContains("Screen control is off")
        capture("device-control-dark")
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
