package app.hermes.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.local.*
import app.hermes.companion.data.remote.*
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two separate instrumentation runs with ADB force-stop between them. No UI, speech or host calls. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DraftRecoveryDeviceTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context = i.targetContext
    private val origin = "https://draft-fixture.invalid/dashboard"
    private val profile = "synthetic-draft-fixture"
    private val owner = DraftOwner(origin, profile, "synthetic-a")
    private val store = ComposerDraftStore.encrypted(context, "companion.drafts.device-test.v1")
    private val fixtureFile = File(context.filesDir, "draft-recovery-device-fixture.txt")
    private fun withManager(action: (ChatSessionManager, MutableStateFlow<CompanionState>) -> Unit) {
        val db = CompanionDatabase.inMemory(context)
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val state = MutableStateFlow(CompanionState(origin = origin, activeProfileId = profile))
        try {
            i.runOnMainSync {
                val manager = ChatSessionManager(HostClientPool { DashboardClient() }, TranscriptCache(db), OutboxStore(db), state, scope, store)
                action(manager, state)
                manager.checkpointDraft()
                scope.cancel()
                scheduler.runCurrent()
            }
        } finally { db.close() }
    }
    @Test fun seedDraftsForProcessDeath() {
        fixtureFile.writeText("synthetic attachment")
        store.save(owner, ComposerDraft())
        store.save(owner.copy(session = "synthetic-b"), ComposerDraft())
        withManager { manager, state ->
            manager.openSession(SessionRef("synthetic-a", profile, "a", 1))
            state.value = state.value.copy(draft = "**recover after process death**\n- readable list\n`code`",
                pendingAttachments = listOf(ChatAttachment("fixture-file", fixtureFile.path, "fixture.txt", "text/plain", ChatBlockKind.FILE)))
            manager.openSession(SessionRef("synthetic-b", profile, "b", 1))
            state.value = state.value.copy(draft = "second conversation")
            manager.closeChat()
            assertTrue(state.value.pendingAttachments.isEmpty())
        }
        assertTrue(store.flush())
        val disk = File(context.applicationInfo.dataDir, "shared_prefs/companion.drafts.device-test.v1.xml").readText()
        assertFalse(disk.contains("recover after process death"))
        assertFalse(disk.contains(profile))
    }
    @Test fun verifyDraftsAfterProcessDeath() {
        assertEquals("synthetic attachment", fixtureFile.readText())
        withManager { manager, state ->
            manager.openSession(SessionRef("synthetic-a", profile, "a", 1))
            assertEquals("**recover after process death**\n- readable list\n`code`", state.value.draft)
            assertEquals(fixtureFile.path, state.value.pendingAttachments.single().localUri)
            manager.openSession(SessionRef("synthetic-b", profile, "b", 1))
            assertEquals("second conversation", state.value.draft)
            assertTrue(state.value.pendingAttachments.isEmpty())
            manager.cancelAll()
            state.value = state.value.dropHostScoped().copy(origin = "https://other.invalid", activeProfileId = profile)
            manager.openSession(SessionRef("synthetic-a", profile, "a", 1))
            assertEquals("", state.value.draft); assertTrue(state.value.pendingAttachments.isEmpty())
            manager.closeChat()
        }
        store.save(owner, ComposerDraft()); store.save(owner.copy(session = "synthetic-b"), ComposerDraft())
        assertTrue(store.flush()); assertTrue(fixtureFile.delete())
    }
}
