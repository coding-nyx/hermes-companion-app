package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hermes.companion.data.local.DraftOwner
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.*
import app.hermes.companion.settings.ComposerModelBottomSheet
import app.hermes.companion.settings.ModelBottomSheet
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic control states only. Does not start microphone, connect a host, or expand voice rollout. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w360dp-h800dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CyberpunkChatControlsRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val session = SessionRef("sample", "research", "Design review", 0)
    private fun voice() = CompanionState(origin = "https://fixture.invalid", hostName = "Sample host",
        activeProfileId = "research", profiles = listOf(ProfileRef("research", "Research", "R")),
        openSessionId = session.id, openSessionRef = session,
        voiceStreamState = VoiceStreamState.LISTENING, voiceMicrophoneReady = false)

    @org.junit.After fun restoreFontScale() { org.robolectric.RuntimeEnvironment.setFontScale(1f) }

    @Test fun preparingDockKeepsExplicitEndAndNeverClaimsListening() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        val actions = mutableListOf<String>()
        rule.setContent { CompanionTheme {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                Box(Modifier.width(320.dp)) {
                    VoiceDock(voice(), { actions += "open" }, { actions += "mute" }, { actions += "stop" })
                }
            }
        } }
        rule.onNodeWithContentDescription("Open voice controls for Design review, Preparing microphone…")
            .assertIsDisplayed().performClick()
        rule.onNodeWithTag("voice.stage.mute").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("voice.stage.stop").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("open", "mute", "stop"), actions)
        capture("voice-dock-preparing-2x")
    }

    @Test fun voiceStopStaysPinnedOutsideLargeTextDetails() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        var stopped = false
        rule.setContent { CompanionTheme {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                VoiceControlsSheet(voice().copy(voicePauseReason = "Synthetic permission recovery explanation. "
                    .repeat(12)), {}, {}, {}, { stopped = true }, {})
            }
        } }
        rule.onNodeWithTag("voice.controls.stop").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("voice.controls.details").performTouchInput { swipeUp() }
        rule.onNodeWithTag("voice.controls.stop").assertIsDisplayed().performClick()
        assertTrue(stopped)
        capture("voice-controls-pinned-stop-2x")
    }

    @Test fun voicePreviewDoesNotStartMicrophoneUntilConfirmed() {
        var starts = 0
        rule.setContent { CompanionTheme { VoiceStartSheet(voice(), {}, { starts++ }, {}) } }
        rule.onNodeWithText("Host").assertExists()
        rule.onNodeWithText("Agent").assertExists()
        assertEquals(0, starts)
        rule.onNodeWithTag("voice.start.confirm").performScrollTo().performClick()
        assertEquals(1, starts)
        capture("voice-start")
    }

    @Test fun conversationModelIsHostConfirmedAndChoicesExposeSelection() {
        val choices = listOf(ModelOption("current-model", "sample", "Current"), ModelOption("other-model", "sample", "Other"))
        val panel = SessionControlsPanel(DraftOwner("https://fixture.invalid", "research", session.id), session, 1,
            loading = false, settings = NativeSessionSettings("current-model", "sample", "none", "normal", choices))
        val changes = mutableListOf<String>()
        rule.setContent { CompanionTheme { ComposerModelBottomSheet(panel,
            { key, value, _ -> changes += "$key=$value" }, {}, {}) } }
        rule.onNodeWithText("Last confirmed by Hermes").assertExists()
        rule.onNodeWithTag("composer.tab.model").assertIsSelected().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("composer.models.item.sample/current-model").assertIsSelected()
        rule.onNodeWithTag("composer.models.item.sample/other-model").assertIsNotSelected()
            .performScrollTo().performClick()
        assertEquals(listOf("model=other-model --provider sample --session"), changes)
        // Selecting does not overwrite the last confirmed row while the host processes the request.
        rule.onNodeWithTag("composer.models.item.sample/current-model").assertIsSelected()
        capture("conversation-model")
    }

    @Test fun unconfirmedGlobalOverrideNeverShowsASelectedCheck() {
        val catalog = ModelCatalog("host-model", "sample", listOf(ModelOption("requested-model", "sample", "Requested")))
        rule.setContent { CompanionTheme { ModelBottomSheet(catalog, "requested-model",
            onSelectModel = { _, _ -> }, onDismiss = {}) } }
        rule.onNodeWithText("Requested model").assertIsDisplayed()
        rule.onNodeWithTag("model.sheet.item.requested-model").assertIsNotSelected()
        capture("model-requested-not-confirmed")
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val decor = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = Canvas(bitmap)
            val windows = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 }
            if (windows.isEmpty()) decor.draw(canvas) else windows.forEach { it.draw(canvas) }
        }
        File("build/reports/cyberpunk-chat-controls/$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
