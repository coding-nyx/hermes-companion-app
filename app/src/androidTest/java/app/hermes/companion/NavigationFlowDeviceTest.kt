package app.hermes.companion

import android.app.KeyguardManager
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.local.*
import app.hermes.companion.data.remote.*
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.domain.DraftThread
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Real draft/session manager and production shell, isolated HTTP fixture; no host prompts or audio. */
@RunWith(AndroidJUnit4::class)
class NavigationFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun chatsIsAListAndExplicitRecoveryPreservesRichDraftsAndNewIntent() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation(); val ctx = i.targetContext
        i.runOnMainSync { compose.activity.setShowWhenLocked(false); compose.activity.setTurnScreenOn(true); compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val keyguard = ctx.getSystemService(KeyguardManager::class.java)
        withTimeout(120_000) { while (keyguard.isKeyguardLocked) delay(100) }
        // Physical UI tests obey the same arm-first rule as other operator control.
        val app = ctx.applicationContext as CompanionApp
        withTimeout(120_000) { app.deviceNode.state.first { it.arm != DeviceArm.DISARMED } }
        fun guarded(action: () -> Unit) {
            assertTrue("Hands was disarmed during UI testing", app.deviceNode.state.value.arm != DeviceArm.DISARMED)
            assertTrue("The test activity lost foreground focus", compose.activity.hasWindowFocus())
            action()
        }
        MockWebServer().use { server ->
            val methods = java.util.concurrent.ConcurrentLinkedQueue<String>()
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                methods += request.method.orEmpty(); return MockResponse().setResponseCode(404).setBody("{}")
            } }
            val origin = server.url("/").toString().trimEnd('/')
            val name = "navigation-fixture-${UUID.randomUUID()}"
            val drafts = ComposerDraftStore.encrypted(ctx, name)
            val db = CompanionDatabase.inMemory(ctx)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val state = MutableStateFlow(CompanionState(origin = origin, activeProfileId = "fixture", hostName = "Navigation fixture"))
            val manager = ChatSessionManager(HostClientPool(), TranscriptCache(db), OutboxStore(db), state, scope, drafts)
            val session = SessionRef("fixture-thread", "fixture", "Saved conversation", 1)
            val local = DraftOwner(origin, "fixture", DraftThread.ID)
            drafts.save(local, ComposerDraft("Local **bold** draft"))
            state.value = state.value.copy(sessions = listOf(session), hasLocalDraft = true)
            var newRequests = 0; var voiceRequests = 0
            fun refreshEntry() { state.update { it.copy(hasLocalDraft = !drafts.load(local).empty) } }
            try {
                i.runOnMainSync { compose.activity.setContent { CompanionTheme {
                    val screen = state.collectAsState().value
                    CompanionShell(screen, onOriginChange = {}, onConnect = {}, onSelectProfile = {},
                        onTab = { tab -> state.update { it.copy(tab = tab) }; refreshEntry() },
                        onOpenSession = manager::openSession, onNewThread = { newRequests++ },
                        onResumeLocalDraft = manager::newThread,
                        onCloseChat = { manager.closeChat(); refreshEntry() },
                        onDraftChange = { text -> state.update { it.copy(draft = text) } },
                        onSend = {}, onInterrupt = {}, onApproval = {}, onToggleVoiceStream = { voiceRequests++ })
                } } }
                compose.onNodeWithTag("threads.list").assertIsDisplayed()
                assertNull(state.value.openSessionId); assertEquals(0, newRequests)
                guarded { compose.onNodeWithTag("threads.resumeDraft").performClick() }
                compose.waitUntil(5_000) { state.value.openSessionId == DraftThread.ID }
                assertEquals("Local **bold** draft", state.value.draft)
                compose.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
                compose.onNodeWithTag("chat.fmt.italic").assertIsDisplayed()
                compose.onNodeWithTag("header.voice").assertIsDisplayed()
                guarded { compose.onNodeWithTag("nav.threads").performClick() }
                compose.waitUntil(5_000) { state.value.openSessionId == null }
                compose.onNodeWithTag("threads.list").assertIsDisplayed()
                guarded { compose.onNodeWithTag("threads.row.fixture-thread").performClick() }
                compose.waitUntil(5_000) { state.value.openSessionId == session.id }
                i.runOnMainSync { state.update { it.copy(draft = "Thread `code` draft") } }
                guarded { compose.onNodeWithTag("nav.device").performClick() }
                compose.waitUntil(5_000) { state.value.tab == MainTab.DEVICE && state.value.openSessionId == null }
                guarded { compose.onNodeWithTag("nav.threads").performClick() }
                guarded { compose.onNodeWithTag("threads.row.fixture-thread").performClick() }
                compose.waitUntil(5_000) { state.value.openSessionId == session.id }
                assertEquals("Thread `code` draft", state.value.draft)
                guarded { compose.onNodeWithTag("chat.draft").performClick() }
                compose.waitUntil(5_000) { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
                guarded { i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK) }
                compose.waitUntil(5_000) { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false }
                assertEquals(session.id, state.value.openSessionId)
                guarded { i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK) }
                compose.waitUntil(5_000) { state.value.openSessionId == null }
                guarded { compose.onNodeWithTag("threads.resumeDraft").performClick() }
                compose.waitUntil(5_000) { state.value.openSessionId == DraftThread.ID }
                assertEquals("Local **bold** draft", state.value.draft)
                guarded { compose.onNodeWithTag("nav.threads").performClick() }
                guarded { compose.onNodeWithTag("threads.new").performClick() }
                assertEquals(1, newRequests); assertEquals(0, voiceRequests)
                assertEquals("Local **bold** draft", drafts.load(local).text)
                assertEquals("Thread `code` draft", drafts.load(local.copy(session = session.id)).text)
                assertTrue(methods.all { it == "GET" })
            } finally {
                scope.cancel(); db.close(); ctx.deleteSharedPreferences(name)
            }
        }
    }
}
