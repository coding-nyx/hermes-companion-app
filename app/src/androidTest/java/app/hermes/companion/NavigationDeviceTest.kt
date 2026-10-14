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
        i.runOnMainSync {
            compose.activity.setContent { CompanionTheme {
                CompanionShell(screen, onOriginChange = {}, onConnect = {}, onSelectProfile = {},
                    onTab = { actions += it.name; screen = screen.copy(tab = it) }, onOpenSession = {}, onNewThread = {},
                    onCloseChat = { actions += "close"; screen = screen.copy(openSessionId = null, openSessionRef = null) },
                    onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {},
                    onToggleVoiceStream = { actions += "voice" })
            } }
        }
        compose.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
        compose.onNodeWithTag("chat.fmt.italic").assertIsDisplayed()
        compose.onNodeWithTag("header.voice").assertIsDisplayed()
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
        assertEquals(listOf("close", "REVIEW"), actions)
        compose.onNodeWithTag("nav.device").performClick()
        compose.waitUntil(5_000) { screen.tab == MainTab.DEVICE }
        compose.onNodeWithTag("nav.device").assertIsSelected()
        assertFalse("Navigation must not open the microphone", "voice" in actions)
        Unit
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
