package app.hermes.desk.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.hermes.companion.VoiceStreamState
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeskScreenRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val connected = DeskState(connected = true, origin = "https://lab.example/desk", profile = "personal",
        profiles = listOf(ProfileRef("personal", "Personal", "P")), session = SessionRef("one", "personal", "A place for ideas", 0), reducedMotion = true)

    @Test fun onboardingDoesNotStartListening() {
        var talks = 0
        mount(DeskState(), actions(talk = { talks++ }))
        rule.onNodeWithText("Connect to Hermes").assertIsDisplayed()
        rule.onNodeWithText("Connect to Hermes").assertIsNotEnabled()
        assertEquals(0, talks)
        capture("01-connect")
    }
    @Test fun landscapeKeepsTranscriptBehindHistory() {
        mount(connected.copy(messages = listOf(ChatMessage("u", MessageRole.USER, "What should we work on today?"),
            ChatMessage("a", MessageRole.ASSISTANT, "We can pick up an existing thread, or make space for a new idea."))))
        rule.onNodeWithContentDescription("Desk robot: available").assertIsDisplayed()
        rule.onNodeWithText("MIC OFF").assertIsDisplayed()
        rule.onNodeWithText("Say what’s on your mind").assertDoesNotExist()
        rule.onNodeWithText("What should we work on today?").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("History").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("What should we work on today?").assertIsDisplayed()
        rule.onNodeWithText("Close history").performClick()
        rule.mainClock.advanceTimeBy(500)
        capture("02-conversation")
    }
    @Test fun privateAmbientDoesNotExposeTheTranscript() {
        mount(connected.copy(privateCaptions = true, ambient = true,
            messages = listOf(ChatMessage("a", MessageRole.ASSISTANT, "private result"))))
        rule.onNodeWithText("private result", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Your conversation stays private").assertIsDisplayed()
        capture("03-ambient-private")
    }
    @Test fun listeningAlwaysHasAnExplicitMicrophoneIndicator() {
        mount(connected.copy(voice = VoiceStreamState.LISTENING, microphoneReady = true, ambient = true))
        rule.onNodeWithText("MIC ON").assertIsDisplayed()
        rule.onNodeWithText("Mute").assertIsDisplayed()
        capture("04-listening")
    }
    @Test fun unknownDeliveryDisablesSubmission() {
        mount(connected.copy(uncertain = true, draft = "do it again"))
        rule.onNodeWithText("Start voice").assertIsNotEnabled()
        rule.onNodeWithText("Check history").assertIsDisplayed()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("History").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Send").assertIsNotEnabled()
    }
    @Test fun approvalIsVisibleAndDoesNotEnableRepeatedTaps() {
        mount(connected.copy(pending = ApprovalPrompt("r", command = "Read the project files"), decisionSending = true))
        rule.onNodeWithText("Once").assertIsNotEnabled()
        rule.onNodeWithText("Deny").assertIsNotEnabled()
        capture("05-decision")
    }
    @Test @Config(qualifiers = "w411dp-h891dp-mdpi") fun compactLayoutKeepsVoiceControlsAndTaskReachable() {
        mount(connected.copy(working = true))
        rule.onNodeWithText("Stop task").assertIsDisplayed()
        rule.onNodeWithText("History").assertIsDisplayed()
        rule.onNodeWithText("Say what’s on your mind").assertDoesNotExist()
        capture("06-compact")
    }
    @Test @Config(qualifiers = "w800dp-h1100dp-mdpi") fun portraitWithLargeTypeKeepsSettingsAndControlsAccessible() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        mount(connected)
        rule.onNodeWithText("Settings").assertIsDisplayed().performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithText("Reduce motion").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithText("Start voice").assertIsDisplayed()
        capture("07-portrait-large-text")
    }
    @Test fun cameraControlsAreNotComposedOnTheHomeScreen() {
        var cameraCompositions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent { CompanionTheme { DeskScreen(connected, actions(), cameraContent = {
            cameraCompositions++
            androidx.compose.material3.Text("Camera controls only")
        }) } }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Camera controls only").assertDoesNotExist()
        assertEquals(0, cameraCompositions)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Camera").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Camera controls only").assertIsDisplayed()
        assertTrue(cameraCompositions > 0)
    }
    @Test fun aListeningRequestDoesNotClaimMicReadinessBeforeCaptureIsReady() {
        mount(connected.copy(voice = VoiceStreamState.LISTENING, microphoneReady = false))
        rule.onNodeWithText("Preparing microphone").assertIsDisplayed()
        rule.onNodeWithText("MIC OFF").assertIsDisplayed()
    }
    @Test fun speakingAndConsideringUseDifferentCharacterPoses() {
        mount(connected.copy(voice = VoiceStreamState.SPEAKING))
        rule.onNodeWithContentDescription("Desk robot: speaking").assertIsDisplayed()
        capture("08-speaking")
    }
    private fun mount(state: DeskState, actions: DeskActions = actions()) {
        rule.activity.actionBar?.hide()
        rule.mainClock.autoAdvance = false
        rule.setContent { CompanionTheme { DeskScreen(state, actions) } }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }
    private fun capture(name: String) {
        var bitmap: Bitmap? = null
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val output = File("build/reports/desk-render").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun actions(talk: () -> Unit = {}) = DeskActions(
        connect = { _, _, _, _ -> }, profile = {}, thread = {}, newThread = {}, draft = {}, send = {}, talk = talk,
        endVoice = {}, interrupt = {}, decision = { _, _ -> }, refresh = {}, dismissUncertain = {}, ambient = {},
        preferences = { _, _, _ -> }, disconnect = {}, openChat = {}, dismissError = {})
}
