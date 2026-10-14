package app.hermes.companion

import android.app.KeyguardManager
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.SessionRef
import app.hermes.companion.settings.SessionContextBottomSheet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real native fetch and production panel/controller, without changing saved host or drafts. */
@RunWith(AndroidJUnit4::class)
class SessionContextDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeContextLoadsRefreshesAndDismissesOnThePhone() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val origin = requireNotNull(args.getString("nativeOrigin"))
        val profile = requireNotNull(args.getString("profile"))
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            compose.activity.setShowWhenLocked(false)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val keyguard = i.targetContext.getSystemService(KeyguardManager::class.java)
        val deadline = System.nanoTime() + 45_000_000_000L
        while (keyguard.isKeyguardLocked && System.nanoTime() < deadline) delay(100)
        assertFalse("Unlock the device for the context panel test", keyguard.isKeyguardLocked)
        val client = DashboardClient().apply { useLoopback() }
        assertNotNull(client.adoptLoopbackToken(origin))
        var created: SessionRef? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val session = client.createSession(origin, profile, "Companion context device test", requireRpc = true)
            created = session
            val state = MutableStateFlow(CompanionState(origin = origin, activeProfileId = profile,
                openSessionId = session.id, openSessionRef = session, sessions = listOf(session)))
            val controller = SessionContextController(state, scope, { 1 }) { host, thread -> client.sessionContext(host, thread) }
            i.runOnMainSync {
                compose.activity.setContent {
                    val snapshot by state.collectAsState()
                    CompanionTheme {
                        snapshot.contextPanel?.let { SessionContextBottomSheet(it, controller::open, controller::dismiss) }
                    }
                }
                controller.open()
            }
            compose.waitUntil(20_000) { state.value.contextPanel?.loading == false }
            assertNull(state.value.contextPanel!!.error)
            assertNotNull(state.value.contextPanel!!.value)
            compose.onNodeWithTag("session.context").assertExists()
            compose.onNodeWithText("Context usage").assertExists()
            compose.onNodeWithText("Refresh context").performScrollTo().performClick()
            compose.waitUntil(20_000) { state.value.contextPanel?.loading == false }
            assertNull(state.value.contextPanel!!.error)
            i.runOnMainSync { controller.dismiss() }
            compose.onNodeWithTag("session.context").assertDoesNotExist()
            android.util.Log.i("CompanionContextTest", "Native context loaded, refreshed and dismissed; no prompt submitted")
        } finally {
            scope.cancel()
            created?.let { client.deleteSession(origin, it.id, profile) }
            client.closeRpc()
        }
        Unit
    }
}
