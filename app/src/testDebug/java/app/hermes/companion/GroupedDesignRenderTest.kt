package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import app.hermes.companion.device.DeviceScreen
import app.hermes.companion.model.*
import app.hermes.companion.settings.SettingsBottomSheet
import java.io.File
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w360dp-h720dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupedDesignRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(CompanionState(origin = "https://fixture.invalid", hostName = "Lab", activeProfileId = "developer"))
    private val actions = mutableListOf<String>()
    private fun mount(settings: Boolean = false, font: Float = 1f) {
        org.robolectric.RuntimeEnvironment.setFontScale(font)
        rule.activity.actionBar?.hide()
        rule.setContent { CompanionTheme { CompositionLocalProvider(LocalDensity provides Density(2f, font)) {
            if (settings) SettingsBottomSheet(state, {}, { _, _ -> }, {}, {},
                onSpeechBackend = { actions += "speech:$it" },
                onToggleStay = { actions += "stay"; state = state.copy(stayConnected = !state.stayConnected) },
                onNtfyTopicChange = { state = state.copy(ntfyTopic = it) }, onSaveNtfy = { actions += "topic:${state.ntfyTopic}" },
                onDisconnect = { actions += "disconnect" }, onDismiss = {})
            else DeviceScreen(PairingPhase.PAIRED, "", "fixture", deviceLabel = "Samsung Galaxy S22", deviceProfileId = "developer", error = null,
                laneOpen = true, a11yBound = true, overlayGranted = true, notifyGranted = true,
                protectedCustom = listOf("com.example.bank"), nlsBound = true,
                notifStreamEnabled = state.notifStreamEnabled, pairedHosts = listOf("https://fixture.invalid", "https://other.invalid"),
                notifStreamOrigin = "https://fixture.invalid", notifStreamProfile = "developer", profilesForStream = listOf("developer", "researcher"),
                onPair = {}, onCancel = {}, onRevoke = { actions += "revoke" }, onArm = { actions += "arm" },
                onToggleNotifStream = { actions += "stream"; state = state.copy(notifStreamEnabled = !state.notifStreamEnabled) })
        } } }
        rule.mainClock.advanceTimeBy(500)
    }
    @After fun restoreFontScale() { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    private fun capture(name: String) {
        rule.waitForIdle()
        val decor = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = Canvas(bitmap)
            val roots = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 }
            if (roots.isEmpty()) decor.draw(canvas) else roots.forEach { it.draw(canvas) }
        }
        File("build/reports/grouped/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun deviceDetailsAreFocusedAndDoNotArmHands() {
        mount()
        capture("device-dark")
        rule.onNodeWithTag("device.protected").assertDoesNotExist()
        rule.onNodeWithTag("device.protected.open").performScrollTo().performClick()
        rule.onNodeWithTag("device.protected").assertIsDisplayed()
        rule.onNodeWithText("com.example.bank").assertIsDisplayed()
        capture("protected-dark")
        rule.onNodeWithTag("device.details.close").performScrollTo().performClick()
        rule.onNodeWithTag("device.protected").assertDoesNotExist()
        Assert.assertTrue(actions.isEmpty())
    }
    @Test fun notificationSwitchActsOnceAndDestinationStaysSeparateAtLargeText() {
        mount(font = 2f)
        rule.onNodeWithTag("device.stream.open").performScrollTo().performClick()
        rule.onNodeWithTag("device.stream").performScrollTo().performClick()
        Assert.assertEquals(listOf("stream"), actions)
        Assert.assertTrue(state.notifStreamEnabled)
        capture("notifications-large")
        rule.onNodeWithTag("device.stream.sink").performScrollTo().performClick()
        rule.onNodeWithText("https://other.invalid").performScrollTo().assertIsDisplayed()
        capture("notification-destination-large")
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithTag("device.stream").performScrollTo().assertIsDisplayed()
        Assert.assertEquals(listOf("stream"), actions)
    }
    @Test fun settingsHostSpeechSelectionAndBackNavigationRemainExplicit() {
        mount(settings = true)
        capture("settings-dark")
        rule.onNodeWithTag("settings.speech.open").performScrollTo().performClick()
        rule.onNodeWithTag("settings.speech.host").assertIsEnabled()
        capture("speech-dark")
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithTag("settings.speech.open").assertIsDisplayed()
        Assert.assertTrue(actions.isEmpty())
    }
    @Test fun backgroundSettingsRetainTopicAndDispatchOnlyTheRequestedActions() {
        mount(settings = true, font = 2f)
        rule.onNodeWithTag("settings.background.open").performScrollTo().performClick()
        rule.onNodeWithTag("settings.stay").performScrollTo().performClick()
        rule.onNodeWithTag("settings.ntfy.input").performScrollTo().performTextInput("test-topic")
        rule.onNodeWithTag("settings.ntfy.save").performScrollTo().performClick()
        Assert.assertEquals(listOf("stay", "topic:test-topic"), actions)
        Assert.assertEquals("test-topic", state.ntfyTopic)
        capture("background-large")
    }
    @Test fun longRowContentReflowsWithoutTruncatingTheValue() {
        val label = "Research and development host"
        val value = "A long host-specific setting that must remain readable"
        rule.setContent { CompanionTheme { CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
            Box(Modifier.width(320.dp).padding(24.dp)) { GroupSurface {
                PreferenceRow(label, "https://example.invalid/long/reverse/proxy/path", value = value, onClick = {})
            } }
        } } }
        listOf(label, value).forEach { text ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            Assert.assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
        }
        capture("row-large")
    }
    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xhdpi") fun groupedSettingsFollowTheLightPalette() {
        mount(settings = true)
        capture("settings-light")
        rule.onNodeWithTag("settings.disconnect").performScrollTo().performClick()
        rule.onNodeWithText("Keep connected").performClick()
        Assert.assertTrue(actions.isEmpty())
    }
}
