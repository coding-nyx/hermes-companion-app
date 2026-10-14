package app.hermes.companion

import android.app.Application
import android.content.Context
import app.hermes.companion.data.local.*
import app.hermes.companion.data.remote.*
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ApprovalOwnershipTest {
    private class Fixture(ctx: Context) : AutoCloseable {
        val session = SessionRef("fixture-session", "fixture-profile", "Synthetic", 0)
        val prompt = ApprovalPrompt("fixture-request", command = "synthetic operation")
        val state = MutableStateFlow(CompanionState(origin = "https://fixture.invalid", activeProfileId = session.profileId,
            openSessionId = session.id, openSessionRef = session, approval = prompt, draft = "**keep rich draft**"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = CompanionDatabase.inMemory(ctx)
        val calls = AtomicInteger(); val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        @Volatile var failure: DashboardException? = null
        val manager = ChatSessionManager(HostClientPool(), TranscriptCache(db), OutboxStore(db), state, scope,
            submitDecision = { _, _, _, decision, _ ->
                calls.incrementAndGet(); gates.computeIfAbsent(decision.requestId) { CompletableDeferred() }.await()
                failure?.let { throw it }
            })
        suspend fun await(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
        fun finish(id: String = prompt.requestId) { gates[id]!!.complete(Unit) }
        override fun close() { runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }; db.close() }
    }
    private fun fixture() = Fixture(RuntimeEnvironment.getApplication())

    @Test fun duplicateTapsSubmitOnceAndKeepTheDraft() = runBlocking {
        fixture().use { f ->
            repeat(8) { f.manager.respondApproval("once") }; f.await { f.calls.get() == 1 && f.gates.containsKey(f.prompt.requestId) }
            assertEquals(f.prompt.requestId, f.state.value.approvalSubmittingRequestId)
            f.finish(); f.await { f.state.value.approval == null && f.state.value.approvalSubmittingRequestId == null }
            assertEquals(1, f.calls.get()); assertEquals(1, f.state.value.messages.count { it.id == "apr-${f.prompt.requestId}" })
            assertEquals("**keep rich draft**", f.state.value.draft)
        }
    }
    @Test fun lateFailureCannotWriteIntoAnotherHostOrConversation() = runBlocking {
        fixture().use { f ->
            f.manager.respondApproval("once"); f.await { f.gates.containsKey(f.prompt.requestId) }
            f.state.value = f.state.value.dropHostScoped().copy(origin = "https://other.invalid", activeProfileId = "other",
                openSessionId = "other", openSessionRef = SessionRef("other", "other", "Other", 0), approval = f.prompt,
                error = "current notice", draft = "new draft")
            val expected = f.state.value
            f.failure = DashboardException("ws_failed", "old connection failed"); f.finish()
            f.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertEquals(expected, f.state.value)
        }
    }
    @Test fun closingAndReopeningTheSameThreadDoesNotAdoptTheOldReply() = runBlocking {
        fixture().use { f ->
            f.manager.respondApproval("deny"); f.await { f.gates.containsKey(f.prompt.requestId) }
            f.manager.closeChat()
            f.state.value = f.state.value.copy(openSessionId = f.session.id, openSessionRef = f.session, approval = f.prompt)
            val expected = f.state.value; f.finish()
            f.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertEquals(expected, f.state.value)
        }
    }
    @Test fun anOlderDecisionCannotClearANewerRequestOrItsSubmittingState() = runBlocking {
        fixture().use { f ->
            f.manager.respondApproval("once"); f.await { f.gates.containsKey(f.prompt.requestId) }
            val next = f.prompt.copy(requestId = "second-request", command = "second operation")
            f.state.value = f.state.value.copy(approval = next)
            f.manager.respondApproval("deny"); f.await { f.gates.containsKey(next.requestId) }
            f.finish(); f.await { f.scope.coroutineContext[Job]!!.children.count() == 1 }
            assertEquals(next, f.state.value.approval); assertEquals(next.requestId, f.state.value.approvalSubmittingRequestId)
            f.finish(next.requestId); f.await { f.state.value.approval == null && f.state.value.approvalSubmittingRequestId == null }
            assertEquals(listOf("apr-second-request"), f.state.value.messages.map { it.id })
            assertEquals(2, f.calls.get())
        }
    }
    @Test fun expiredDecisionClearsOnlyItsOwnBannerWithoutInventingAnOutcome() = runBlocking {
        fixture().use { f ->
            f.failure = DashboardException("request_expired", "This request is no longer pending")
            f.manager.respondApproval("once"); f.await { f.gates.containsKey(f.prompt.requestId) }; f.finish()
            f.await { f.state.value.error != null && f.state.value.approvalSubmittingRequestId == null }
            assertNull(f.state.value.approval); assertTrue(f.state.value.messages.isEmpty())
            assertEquals("**keep rich draft**", f.state.value.draft)
        }
    }
    @Test fun lostReplyStaysVisibleAndDoesNotAutomaticallyResubmit() = runBlocking {
        fixture().use { f ->
            f.failure = DashboardException("ws_failed", "Delivery outcome unknown")
            f.manager.respondApproval("once"); f.await { f.gates.containsKey(f.prompt.requestId) }; f.finish()
            f.await { f.state.value.error != null && f.state.value.approvalSubmittingRequestId == null }
            assertEquals(f.prompt, f.state.value.approval); assertTrue(f.state.value.messages.isEmpty())
            assertEquals(1, f.calls.get())
        }
    }
    @Test fun secureValuesAreNeverAddedToTheConversation() = runBlocking {
        fixture().use { f ->
            f.state.value = f.state.value.copy(approval = f.prompt.copy(kind = "secret"))
            f.manager.respondApproval("synthetic-private-value"); f.await { f.gates.containsKey(f.prompt.requestId) }; f.finish()
            f.await { f.state.value.approval == null }
            assertEquals("Secure response submitted.", f.state.value.messages.single().text)
            assertTrue(f.state.value.messages.none { it.text.contains("synthetic-private-value") })
        }
    }
}
