package app.hermes.hands

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.hermes.companion.design.CompanionTheme
import app.hermes.hands.engine.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HandsScreenRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun show(state: HandsState = HandsState(), allow: () -> Unit = {}, stop: () -> Unit = {},
        remote: RemoteAccessState = RemoteAccessState(), onPair: () -> Unit = {}) {
        rule.setContent { CompanionTheme {
            HandsScreen(state, ShizukuStatus(), emptyList(), {}, {}, allow, stop, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                remote = remote, onPair = onPair)
        } }
    }
    @Test fun darkStartAndEngineSettings() {
        show()
        rule.onNodeWithTag("hands.start").assertIsDisplayed()
        capture("hands-light-start")
        rule.onNodeWithText("Control engine").performScrollTo()
        capture("hands-light-engine")
    }
    @Test @Config(qualifiers = "w360dp-h800dp-night-xhdpi") fun darkArmedStateAlwaysShowsStop() {
        var stops = 0
        show(HandsState(serving = true, consent = true, armed = true, client = "Research agent", message = "Controller connected. Stop at any time."), stop = { stops++ })
        rule.onNodeWithTag("hands.disarm").assertIsDisplayed().performClick()
        assertEquals(1, stops)
        capture("hands-dark-active")
        rule.onNodeWithText("Control engine").performScrollTo()
        capture("hands-dark-engine")
    }
    @Test @Config(qualifiers = "w320dp-h720dp-night-xhdpi") fun largeTextKeepsServiceAndClientControlsReachable() {
        rule.activity.resources.configuration.fontScale = 2f
        show(HandsState(serving = true))
        rule.onNodeWithTag("hands.stop.service").assertIsDisplayed()
        rule.onNodeWithTag("hands.allow").assertDoesNotExist()
        capture("hands-large-control")
        rule.onNodeWithTag("hands.tab.clients").performClick()
        rule.onNodeWithTag("hands.token.create").performScrollTo().assertIsDisplayed()
        capture("hands-large-clients")
    }
    @Test fun clientsPageExplainsConnectionWithoutAccount() {
        show()
        rule.onNodeWithTag("hands.tab.clients").performClick()
        rule.onNodeWithTag("hands.token.create").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("No clients connected yet.").performScrollTo().assertIsDisplayed()
        capture("hands-light-clients")
    }
    @Test @Config(qualifiers = "w360dp-h800dp-night-xhdpi") fun remoteConnectionDisplaysPinnedAddressesAndLocalPairAction() {
        var paired = 0
        show(HandsState(serving = true), remote = remoteFixture(), onPair = { paired++ })
        rule.onNodeWithTag("hands.tab.clients").performClick()
        rule.onNodeWithTag("hands.remote.pair").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, paired)
        capture("hands-remote-dark")
    }
    @Test @Config(qualifiers = "w320dp-h720dp-xhdpi") fun enlargedPairingCodeAndFingerprintReflow() {
        rule.activity.resources.configuration.fontScale = 2f
        rule.setContent { CompanionTheme {
            RemotePairingPanel(PairingState(open = true, code = "12345678", expiresInSeconds = 117),
                remoteFixture(), {}, {}, {})
        } }
        rule.onNodeWithTag("hands.pair.code").performScrollTo().assertIsDisplayed()
        capture("hands-pair-code-light-large")
    }
    @Test @Config(qualifiers = "w320dp-h720dp-night-xhdpi") fun enlargedPendingApprovalKeepsBothDecisionsReachable() {
        rule.activity.resources.configuration.fontScale = 2f
        var approvals = 0
        rule.setContent { CompanionTheme {
            RemotePairingPanel(PairingState(pending = PendingPairing("Research workstation agent", "100.105.213.54")),
                remoteFixture(), { approvals++ }, {}, {})
        } }
        rule.onNodeWithTag("hands.pair.approve").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, approvals)
        rule.onNodeWithText("Deny").performScrollTo().assertIsDisplayed()
        capture("hands-pair-approval-dark-large")
    }
    @Test @Config(qualifiers = "w360dp-h800dp-night-xhdpi") fun pairingCodeUsesDarkChrome() {
        rule.setContent { CompanionTheme {
            RemotePairingPanel(PairingState(open = true, code = "12345678", expiresInSeconds = 117),
                remoteFixture(), {}, {}, {})
        } }
        rule.onNodeWithTag("hands.pair.code").assertIsDisplayed()
        capture("hands-pair-code-dark")
    }
    @Test fun pendingApprovalUsesLightChrome() {
        rule.setContent { CompanionTheme {
            RemotePairingPanel(PairingState(pending = PendingPairing("Research workstation agent", "100.105.213.54")),
                remoteFixture(), {}, {}, {})
        } }
        rule.onNodeWithTag("hands.pair.approve").performScrollTo().assertIsDisplayed()
        capture("hands-pair-approval-light")
    }
    @Test fun shareAgentInstructionBuildsPayloadAndTriggersShareAction() {
        var sharedPayload: String? = null
        var copiedPayload: String? = null
        rule.setContent { CompanionTheme {
            RemotePairingPanel(
                pairing = PairingState(open = true, code = "87654321", expiresInSeconds = 120),
                remote = remoteFixture(),
                onApprove = {},
                onDeny = {},
                onClose = {},
                onShare = { sharedPayload = it },
                onCopyPrompt = { copiedPayload = it },
            )
        } }
        rule.onNodeWithTag("hands.pair.share").performScrollTo().assertIsDisplayed().performClick()
        org.junit.Assert.assertNotNull(sharedPayload)
        org.junit.Assert.assertTrue(sharedPayload!!.contains("87654321"))
        org.junit.Assert.assertTrue(sharedPayload!!.contains("https://192.168.1.100:8766"))
        org.junit.Assert.assertTrue(sharedPayload!!.contains("ab12cd34"))

        rule.onNodeWithTag("hands.pair.copy_prompt").performScrollTo().assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(sharedPayload, copiedPayload)
        capture("hands-share-instructions-glass")
    }

    private fun remoteFixture() = RemoteAccessState(true, "ab12cd34".repeat(8),
        listOf("https://192.168.1.100:8766", "https://100.105.213.54:8766"), "Encrypted access is available to paired agents.")
    private fun capture(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val file = java.io.File("build/reports/hands-render/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
