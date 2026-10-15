package app.hermes.companion

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.SessionRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Production shell with synthetic content; no prompts, settings mutations or microphone access. */
@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun readableBarAndMoreNavigateWithoutChangingTheComposerOrStartingVoice() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            (i.targetContext.applicationContext as CompanionApp).deviceNode.arm()
            compose.activity.setShowWhenLocked(false)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val keyguard = i.targetContext.getSystemService(KeyguardManager::class.java)
        val deadline = System.nanoTime() + 90_000_000_000L
        while (keyguard.isKeyguardLocked && System.nanoTime() < deadline) delay(100)
        assertFalse("Unlock the device for navigation testing", keyguard.isKeyguardLocked)
        val session = SessionRef("synthetic-navigation", "knight", "Navigation test", 0)
        var screen by mutableStateOf(CompanionState(origin = "http://lab", hostName = "Lab", activeProfileId = "knight",
            openSessionId = session.id, openSessionRef = session, draft = "Draft with **formatting**"))
        val actions = mutableListOf<String>()
        val settingChanges = mutableListOf<Pair<String, String>>()
        val modelChoices = listOf(
            app.hermes.companion.model.ModelOption("fixture-model", "first", "Fixture", reasoning = true, canDisableReasoning = true),
            app.hermes.companion.model.ModelOption("other-model", "second", "Other"))
        i.runOnMainSync {
            compose.activity.setContent { CompanionTheme {
                CompanionShell(screen, onOriginChange = {}, onConnect = {}, onSelectProfile = {},
                    onTab = { actions += it.name; screen = screen.copy(tab = it, chatMinimized = true) }, onOpenSession = {}, onNewThread = {},
                    onCloseChat = { actions += "close"; screen = screen.copy(openSessionId = null, openSessionRef = null) },
                    onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {},
                    onSessionControls = { screen = screen.copy(sessionControls = SessionControlsPanel(
                        app.hermes.companion.data.local.DraftOwner("http://lab", "knight", session.id), session, 1,
                        loading = false, settings = app.hermes.companion.model.NativeSessionSettings(
                            "fixture-model", "first", "none", "normal", modelChoices))) },
                    onSetSessionSetting = { key, value, _ -> settingChanges += key to value },
                    onDismissSessionControls = { screen = screen.copy(sessionControls = null) },
                    onToggleVoiceStream = { actions += "voice"; screen=screen.copy(voiceStreamState=VoiceStreamState.LISTENING,voiceSessionTitle=session.title) },
                    onToggleVoiceMicrophone = { val muted=!screen.voiceMicrophoneMuted; screen=screen.copy(voiceMicrophoneMuted=muted,voiceStreamState=if(muted)VoiceStreamState.MUTED else VoiceStreamState.LISTENING) },
                    onStopVoiceStream = { screen=screen.copy(voiceStreamState=VoiceStreamState.IDLE,voiceMicrophoneMuted=false) },
                    onReturnToVoiceConversation = { screen=screen.copy(tab=MainTab.THREADS,chatMinimized=false) })
            } }
        }
        compose.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
        compose.onNodeWithTag("chat.fmt.italic").assertIsDisplayed()
        compose.onNodeWithTag("header.voice").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        listOf("attach", "slash", "voice", "send").forEach {
            compose.onNodeWithTag("chat.$it").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        fun bounds(tag: String) = compose.onNodeWithTag("chat.$tag").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds("attach").bottom < bounds("slash").top)
        assertTrue(bounds("voice").bottom < bounds("send").top)
        assertTrue(bounds("draft").width > bounds("attach").width * 3)
        i.runOnMainSync {
            compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        listOf("threads", "console", "device", "more").forEach {
            compose.onNodeWithTag("nav.$it").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        screenshot(i, "navigation-device-chat.png")
        compose.onNodeWithTag("nav.more").performClick()
        compose.onNodeWithText("Code review").assertIsDisplayed()
        compose.onNodeWithText("Schedules").assertIsDisplayed()
        compose.onNodeWithText("Hosts").assertIsDisplayed()
        compose.onNodeWithText("Profiles").assertIsDisplayed()
        screenshot(i, "navigation-device-more.png")
        compose.onNodeWithTag("nav.review").performClick()
        compose.waitUntil(5_000) { screen.tab == MainTab.REVIEW }
        compose.onNodeWithTag("nav.more").assertIsSelected()
        assertEquals(listOf("REVIEW"), actions)
        compose.onNodeWithTag("nav.device").performClick()
        compose.waitUntil(5_000) { screen.tab == MainTab.DEVICE }
        compose.onNodeWithTag("nav.device").assertIsSelected()
        assertFalse("Navigation must not open the microphone", "voice" in actions)
        i.runOnMainSync { screen = screen.copy(companionAvailability = app.hermes.companion.model.CompanionAvailability.AVAILABLE) }
        compose.onNodeWithTag("nav.threads").performClick()
        compose.onNodeWithTag("threads.new").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("threads.new").assertTextContains("New chat")
        compose.onNodeWithTag("threads.newroom").assertDoesNotExist()
        screenshot(i, "navigation-device-chat-list.png")
        compose.onNodeWithTag("threads.options").performClick()
        compose.onNodeWithText("New agent room").assertIsDisplayed()
        screenshot(i, "navigation-device-chat-options.png")
        i.runOnMainSync { screen = screen.copy(openSessionId = session.id, openSessionRef = session, chatMinimized = false) }
        compose.onNodeWithTag("chat.model.settings").assertDoesNotExist()
        compose.onNodeWithTag("chat.draft").performClick()
        compose.onNodeWithTag("chat.model.settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("composer.models").assertIsDisplayed()
        compose.onNodeWithTag("composer.models.providers").performClick()
        compose.onNodeWithTag("composer.models.provider.second").performClick()
        compose.onNodeWithTag("composer.models.item.first/fixture-model").assertDoesNotExist()
        compose.onNodeWithTag("composer.models.item.second/other-model").performClick()
        compose.onNodeWithTag("composer.models.reasoning").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf("model" to "other-model --provider second --session", "reasoning" to "medium"), settingChanges)
        screenshot(i, "navigation-device-composer-models.png")
        compose.onNodeWithTag("composer.models.done").performScrollTo().performClick()
        assertEquals("Draft with **formatting**", screen.draft)
        i.runOnMainSync { compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nav.voice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat.voice").performTouchInput { longClick() }
        compose.waitUntil(5_000) { screen.isVoiceStreamActive }
        assertEquals(1,actions.count { it=="voice" })
        compose.onNodeWithTag("voice.stage.mute").performClick()
        assertTrue(screen.voiceMicrophoneMuted)
        compose.onNodeWithTag("nav.device").performClick()
        compose.onNodeWithTag("voice.stage").assertIsDisplayed()
        assertEquals(session.id,screen.openSessionId)
        compose.onNodeWithTag("nav.voice").performClick()
        compose.onNodeWithTag("voice.controls").assertIsDisplayed()
        screenshot(i,"navigation-device-voice-controls.png")
        compose.onNodeWithTag("voice.controls.conversation").performClick()
        compose.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
        compose.onNodeWithTag("chat.draft").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nav.voice").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("voice.stage").assertIsDisplayed()
        screenshot(i,"navigation-device-voice-keyboard.png")
        compose.onNodeWithTag("voice.stage.stop").performClick()
        assertFalse(screen.isVoiceStreamActive)
        assertEquals("Draft with **formatting**",screen.draft)
        Unit
    }

    @After fun disarmAfterDeviceChecks() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync { (i.targetContext.applicationContext as CompanionApp).deviceNode.disarm() }
    }

    private fun screenshot(i: android.app.Instrumentation, name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        i.waitForIdleSync()
        // The Compose test clock does not advance WindowManager's dialog animation.
        // Wait for its real-time fade before capturing SurfaceFlinger's screen image.
        android.os.SystemClock.sleep(600)
        val bitmap = requireNotNull(i.uiAutomation.takeScreenshot())
        File(i.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
