package app.hermes.companion

import android.app.Application
import android.content.Context
import app.hermes.companion.data.local.*
import app.hermes.companion.data.remote.*
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class DraftRecoveryTest {
    private class Fixture(test: TestScope) : AutoCloseable {
        val ctx = RuntimeEnvironment.getApplication()
        val store = ComposerDraftStore(ctx.getSharedPreferences("recovery-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        val state = MutableStateFlow(CompanionState(origin = "http://lab", activeProfileId = "ash"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val db = CompanionDatabase.inMemory(ctx)
        val manager = ChatSessionManager(HostClientPool { DashboardClient() }, TranscriptCache(db), OutboxStore(db), state, scope, store)
        fun open(id: String) = manager.openSession(SessionRef(id, state.value.activeProfileId!!, id, 1))
        override fun close() { scope.cancel(); db.close() }
    }
    @Test fun rapidSwitchingKeepsEachRichTextDraftWithoutWaitingForTheCollector() = runTest {
        Fixture(this).use { f ->
            f.open("a"); f.state.value = f.state.value.copy(draft = "**bold**\n`code`")
            f.open("b"); assertEquals("", f.state.value.draft)
            f.state.value = f.state.value.copy(draft = "second")
            f.open("a"); assertEquals("**bold**\n`code`", f.state.value.draft)
            f.open("b"); assertEquals("second", f.state.value.draft)
        }
    }
    @Test fun closingClearsVisibleFilesAndReopeningRecoversTheirExactOwner() = runTest {
        Fixture(this).use { f ->
            val file = File(f.ctx.cacheDir, "draft-${UUID.randomUUID()}").apply { writeText("test") }
            try {
                f.open("a"); val attachment = ChatAttachment("a", file.path, "test", "text/plain", ChatBlockKind.FILE)
                f.state.value = f.state.value.copy(draft = "with file", pendingAttachments = listOf(attachment))
                f.manager.closeChat(); assertTrue(f.state.value.pendingAttachments.isEmpty())
                f.open("b"); assertTrue(f.state.value.pendingAttachments.isEmpty())
                f.open("a"); assertEquals(listOf(attachment), f.state.value.pendingAttachments)
                assertEquals("with file", f.state.value.draft)
            } finally { file.delete() }
        }
    }
    @Test fun hostAndProfileSwitchesDoNotExposeOtherDrafts() = runTest {
        Fixture(this).use { f ->
            f.open("a"); f.state.value = f.state.value.copy(draft = "private")
            f.manager.cancelAll(); f.state.value = f.state.value.dropHostScoped().copy(origin = "http://other", activeProfileId = "ash")
            f.open("a"); assertEquals("", f.state.value.draft)
            f.manager.cancelAll(); f.state.value = f.state.value.dropHostScoped().copy(origin = "http://lab", activeProfileId = "other")
            f.open("a"); assertEquals("", f.state.value.draft)
            f.manager.cancelAll(); f.state.value = f.state.value.dropHostScoped().copy(origin = "http://lab", activeProfileId = "ash")
            f.open("a"); assertEquals("private", f.state.value.draft)
        }
    }
    @Test fun localDraftSurvivesClosingAndExplicitNewDoesNotEraseIt() = runTest {
        Fixture(this).use { f ->
            f.manager.newThread(); f.state.value = f.state.value.copy(draft = "unfinished local")
            f.manager.createThread() // Do not run the host coroutine; check immediate navigation admission.
            assertTrue(f.state.value.creatingThread); assertEquals("unfinished local", f.state.value.draft)
            f.manager.closeChat(); f.manager.newThread()
            assertEquals("unfinished local", f.state.value.draft)
        }
    }
    @Test fun recreationRestoresOnlyWhenThatThreadIsOpened() = runTest {
        Fixture(this).use { f ->
            f.open("a"); f.state.value = f.state.value.copy(draft = "restart draft")
            f.manager.cancelAll(); assertTrue(f.store.flush()); f.scope.cancel()
            val state = MutableStateFlow(CompanionState(origin = "http://lab", activeProfileId = "ash"))
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val manager = ChatSessionManager(HostClientPool { DashboardClient() }, TranscriptCache(f.db), OutboxStore(f.db), state, scope, f.store)
                assertEquals("", state.value.draft)
                manager.openSession(SessionRef("a", "ash", "a", 1))
                assertEquals("restart draft", state.value.draft)
            } finally { scope.cancel() }
        }
    }
    @Test fun roomTextCannotOverwriteTheChatDraft() = runTest {
        Fixture(this).use { f ->
            f.open("a"); f.state.value = f.state.value.copy(draft = "chat"); f.manager.closeChat()
            f.state.value = f.state.value.copy(openRoomId = "room", draft = "room text")
            runCurrent(); f.state.value = f.state.value.copy(openRoomId = null, draft = "")
            f.open("a"); assertEquals("chat", f.state.value.draft)
        }
    }
    @Test fun unreadableDraftIsReportedWithoutOverwritingTheStoredRecord() = runTest {
        Fixture(this).use { f ->
            // Isolate the recovery controller's error path, without exposing corrupt JSON to the UI.
            val prefs = f.ctx.getSharedPreferences("corrupt-${UUID.randomUUID()}", Context.MODE_PRIVATE)
            val key = kotlinx.serialization.json.Json.encodeToString(DraftOwner.serializer(), DraftOwner("http://lab", "ash", "a"))
            prefs.edit().putString(key, "corrupted fixture").commit()
            val recovery = DraftRecovery(ComposerDraftStore(prefs), f.state, f.scope)
            val restored = recovery.restore("http://lab", "ash", "a")
            assertTrue(restored.contents.empty); assertNotNull(recovery.warning(restored))
            assertEquals("corrupted fixture", prefs.getString(key, null))
        }
    }
    @Test fun missingSavedFileShowsRecoveryActionAndKeepsText() = runTest {
        Fixture(this).use { f ->
            f.store.save(DraftOwner("http://lab", "ash", "a"), ComposerDraft("kept", listOf(
                ChatAttachment("missing", "/does/not/exist", "file.txt", "text/plain", ChatBlockKind.FILE))))
            f.open("a"); assertEquals("kept", f.state.value.draft)
            assertTrue(f.state.value.error.orEmpty().contains("pick the file again"))
        }
    }
}
