package app.hermes.companion

import android.app.Application
import android.content.Context
import app.hermes.companion.data.local.*
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class SessionControlsControllerTest {
    private class Api : NativeSessionApi {
        @Volatile var value = NativeSessionSettings("model", "provider", "medium", "normal")
        @Volatile var failure: DashboardException? = null
        @Volatile var confirmation = false
        @Volatile var settingReply: CompletableDeferred<NativeConfigResult>? = null
        @Volatile var directive = NativeCommandResult("exec", "output", "", "", "", "")
        val sets = AtomicInteger(); val dispatches = AtomicInteger()
        override suspend fun settings(origin: String, session: SessionRef) = value
        override suspend fun set(origin: String, session: SessionRef, key: String, value: String, confirmed: Boolean): NativeConfigResult {
            sets.incrementAndGet(); failure?.let { throw it }
            settingReply?.let { return it.await() }
            if (confirmation && !confirmed) return NativeConfigResult(key, null, "session", false, true, "Confirm price", "")
            this.value = when (key) { "reasoning" -> this.value.copy(reasoning = value); "fast" -> this.value.copy(fast = value); else -> this.value.copy(model = value) }
            return NativeConfigResult(key, value, "session", false, false, "", "")
        }
        override suspend fun catalog(origin: String, session: SessionRef) = listOf(NativeCommand("status", "status"))
        override suspend fun dispatch(origin: String, session: SessionRef, name: String, argument: String): NativeCommandResult { dispatches.incrementAndGet(); failure?.let { throw it }; return directive }
        override suspend fun redirect(origin: String, session: SessionRef, text: String) = NativeRedirectResult("queued", text)
    }
    private class Fixture(context: Context) : AutoCloseable {
        val db = CompanionRecoveryDatabase.inMemory(context); val store = SessionMutationStore(db.mutations())
        val owner = DraftOwner("http://lab", "ash", "s1")
        val session = SessionRef("s1", "ash", "test", 0)
        val state = MutableStateFlow(CompanionState(origin = owner.origin, activeProfileId = owner.profile,
            openSessionId = session.id, openSessionRef = session, draft = "keep draft"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = Api(); @Volatile var epoch = 1L
        val submitted = mutableListOf<Pair<String, String>>()
        val controller = SessionControlsController(state, scope, { epoch }, api, store) { raw, display -> submitted += raw to display }
        suspend fun await(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(5) }
        suspend fun open() { controller.open(); await { state.value.sessionControls?.loading == false && state.value.sessionControls?.commands?.isNotEmpty() == true } }
        override fun close() { runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }; db.close() }
    }
    private fun fixture() = Fixture(RuntimeEnvironment.getApplication())
    @Test fun sessionSettingIsConfirmedByTheHostAndDoesNotChangeTheDraft() = runBlocking {
        fixture().use { f ->
            f.open(); f.controller.set("reasoning", "low")
            f.await { f.state.value.sessionControls?.settings?.reasoning == "low" && f.state.value.sessionControls?.busy == false }
            assertEquals("keep draft", f.state.value.draft); assertEquals(1, f.api.sets.get())
            assertTrue(f.store.pending(f.owner).isEmpty())
        }
    }
    @Test fun unknownMutationSurvivesRestartAndCannotBeReplayed() = runBlocking {
        fixture().use { f ->
            f.open(); f.api.failure = DashboardException("ws_closed", "lost response")
            f.controller.set("reasoning", "low")
            f.await { f.state.value.sessionControls?.pending?.isNotEmpty() == true && f.state.value.sessionControls?.busy == false }
            f.controller.dismiss(); f.controller.open(); f.await { f.state.value.sessionControls?.loading == false }
            f.controller.set("reasoning", "high")
            f.await { f.state.value.sessionControls?.error?.contains("earlier operation") == true }
            assertEquals(1, f.api.sets.get()); assertEquals("unknown", f.store.pending(f.owner).single().state)
            f.api.value = f.api.value.copy(reasoning = "low"); f.controller.open()
            f.await { f.state.value.sessionControls?.settings?.reasoning == "low" && f.state.value.sessionControls?.pending?.isEmpty() == true }
            assertTrue(f.store.pending(f.owner).isEmpty())
        }
    }
    @Test fun lateSettingsCannotUpdateAReopenedConversation() = runBlocking {
        fixture().use { f ->
            f.open(); val result = CompletableDeferred<NativeConfigResult>(); f.api.settingReply = result
            f.controller.set("reasoning", "low"); f.await { f.api.sets.get() == 1 }
            f.epoch++; f.controller.selectionChanged(); result.complete(NativeConfigResult("reasoning", "low", "session", false, false, "", ""))
            f.await { runBlocking { f.store.pending(f.owner).isEmpty() } }
            assertNull(f.state.value.sessionControls); assertEquals("keep draft", f.state.value.draft)
        }
    }
    @Test fun expensiveModelRequiresAnExplicitSecondAction() = runBlocking {
        fixture().use { f ->
            f.open(); f.api.confirmation = true; f.controller.set("model", "expensive")
            f.await { f.state.value.sessionControls?.confirmationKey == "model" && f.state.value.sessionControls?.busy == false }
            assertEquals("model", f.state.value.sessionControls?.settings?.model); assertEquals(1, f.api.sets.get())
            f.controller.set("model", "expensive", confirmed = true)
            f.await { f.state.value.sessionControls?.settings?.model == "expensive" && f.state.value.sessionControls?.busy == false }
            assertEquals(2, f.api.sets.get())
        }
    }
    @Test fun authFailureIsUnknownWhileMethodNotFoundIsUnsupported() = runBlocking {
        for (code in listOf("http_401", "rpc_-32601")) fixture().use { f ->
            f.open(); f.api.failure = DashboardException(code, "failed")
            f.controller.set("reasoning", "low")
            f.await { f.state.value.sessionControls?.error != null && f.state.value.sessionControls?.busy == false }
            assertEquals(code == "rpc_-32601", "config.reasoning" in f.state.value.sessionControls!!.unsupported)
        }
    }
    @Test fun nativeSkillSendsItsPayloadOnceAndKeepsUnrelatedDrafts() = runBlocking {
        fixture().use { f ->
            f.open(); f.api.directive = NativeCommandResult("skill", "", "hidden skill payload", "/skill", "", "")
            f.controller.dispatch("/skill")
            f.await { f.submitted.size == 1 && f.state.value.sessionControls?.busy == false }
            assertEquals(listOf("hidden skill payload" to "/skill"), f.submitted)
            assertEquals("keep draft", f.state.value.draft); assertEquals(1, f.api.dispatches.get())
        }
    }
    @Test fun redirectReportsQueuedWithoutCreatingANewPrompt() = runBlocking {
        fixture().use { f ->
            f.open(); f.controller.redirect("do this instead")
            f.await { f.state.value.sessionControls?.notice == "Redirect queued" }
            assertTrue(f.submitted.isEmpty()); assertEquals("keep draft", f.state.value.draft)
        }
    }
}
