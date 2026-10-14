package app.hermes.companion

import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionContextHydrationDeviceTest {
    private val session = SessionRef("s1", "coder", "Synthetic context", 0)
    private val context = SessionContext(10, 100, 20, true, "local_estimate", "model", emptyList())
    private fun state() = MutableStateFlow(CompanionState(origin = "http://lab", activeProfileId = "coder",
        openSessionId = session.id, openSessionRef = session, sessions = listOf(session)))

    @Test fun closingAndReopeningSameSessionCannotAdoptOldResult() = runTest {
        val state = state(); var epoch = 1L
        val completion = CompletableDeferred<SessionContext>()
        val controller = SessionContextController(state, this, { epoch }) { _, _ ->
            withContext(NonCancellable) { completion.await() }
        }
        controller.open(); runCurrent()
        epoch++
        controller.selectionChanged()
        completion.complete(context); runCurrent()
        assertNull(state.value.contextPanel)
    }

    @Test fun dismissedSheetDoesNotReappearFromLateResult() = runTest {
        val state = state(); val completion = CompletableDeferred<SessionContext>()
        val controller = SessionContextController(state, this, { 1 }) { _, _ -> withContext(NonCancellable) { completion.await() } }
        controller.open(); runCurrent(); controller.dismiss()
        completion.complete(context); runCurrent()
        assertNull(state.value.contextPanel)
    }

    @Test fun hostAndProfileChangesRejectLateResults() = runTest {
        for (hostSwitch in listOf(true, false)) {
            val state = state(); val completion = CompletableDeferred<SessionContext>()
            val controller = SessionContextController(state, this, { 1 }) { _, _ -> withContext(NonCancellable) { completion.await() } }
            controller.open(); runCurrent()
            state.value = if (hostSwitch) state.value.copy(origin = "http://other") else state.value.copy(activeProfileId = "other")
            controller.selectionChanged(); completion.complete(context); runCurrent()
            assertNull(state.value.contextPanel)
        }
    }

    @Test fun authAndNetworkFailuresRemainRetryableAndRefreshCanRecover() = runTest {
        val state = state(); var fail = true
        val controller = SessionContextController(state, this, { 1 }) { _, _ ->
            if (fail) throw DashboardException("http_401", "Login expired") else context
        }
        controller.open(); runCurrent()
        assertFalse(state.value.contextPanel!!.unsupported)
        assertEquals("Login expired", state.value.contextPanel!!.error)
        fail = false; controller.open(); runCurrent()
        assertEquals(context, state.value.contextPanel!!.value)
        assertFalse(state.value.contextPanel!!.loading)
    }

    @Test fun onlyExplicitMethodNotFoundEstablishesUnsupported() = runTest {
        val state = state()
        SessionContextController(state, this, { 1 }) { _, _ -> throw DashboardException("rpc_-32601", "missing") }.open()
        runCurrent()
        assertTrue(state.value.contextPanel!!.unsupported)
    }

    @Test fun completedMessagesRefreshOnceWithoutDraftOrPanelFeedbackLoops() = runTest {
        val state = state(); var calls = 0
        val controller = SessionContextController(state, this, { 1 }) { _, _ -> context.copy(used = (++calls * 10).toLong()) }
        controller.open(); runCurrent()
        state.value = state.value.copy(messages = listOf(ChatMessage("a1", MessageRole.ASSISTANT, "partial", streaming = true)), streaming = true)
        controller.observe(state.value); advanceTimeBy(300); runCurrent(); assertEquals(1, calls)
        state.value = state.value.copy(messages = listOf(ChatMessage("a1", MessageRole.ASSISTANT, "complete")), streaming = false)
        controller.observe(state.value)
        state.value = state.value.copy(draft = "**new draft**"); controller.observe(state.value)
        advanceTimeBy(250); runCurrent()
        assertEquals(2, calls); assertEquals(20L, state.value.contextPanel!!.value!!.used)
        controller.observe(state.value); advanceTimeBy(300); runCurrent(); assertEquals(2, calls)
        assertEquals("**new draft**", state.value.draft)
    }

    @Test fun completedBurstsCoalesceAndDismissalCancelsTheScheduledRead() = runTest {
        val state = state(); var calls = 0
        val controller = SessionContextController(state, this, { 1 }) { _, _ -> calls++; context }
        controller.open(); runCurrent()
        repeat(3) { n ->
            state.value = state.value.copy(messages = listOf(ChatMessage("a$n", MessageRole.ASSISTANT, "reply $n")))
            controller.observe(state.value); advanceTimeBy(50)
        }
        advanceTimeBy(250); runCurrent(); assertEquals(2, calls)
        state.value = state.value.copy(messages = emptyList()); controller.observe(state.value); runCurrent()
        controller.dismiss(); advanceTimeBy(300); runCurrent()
        assertEquals(2, calls); assertNull(state.value.contextPanel)
    }

    @Test fun anAutomaticRefreshFailureKeepsTheLastMeasurementAndCanRecover() = runTest {
        val state = state(); var fail = false
        val controller = SessionContextController(state, this, { 1 }) { _, _ ->
            if (fail) throw DashboardException("http_401", "Login expired") else context
        }
        controller.open(); runCurrent(); fail = true
        state.value = state.value.copy(messages = listOf(ChatMessage("a1", MessageRole.ASSISTANT, "reply")))
        controller.observe(state.value); advanceTimeBy(250); runCurrent()
        assertEquals(context, state.value.contextPanel!!.value); assertEquals("Login expired", state.value.contextPanel!!.error)
        assertFalse(state.value.contextPanel!!.refreshing); assertFalse(state.value.contextPanel!!.unsupported)
        fail = false; controller.open(); runCurrent(); assertNull(state.value.contextPanel!!.error)
    }

    @Test fun aSlowPreviousReadCannotOverwriteTheNewerContext() = runTest {
        val state = state(); val previous = CompletableDeferred<SessionContext>(); var calls = 0
        val controller = SessionContextController(state, this, { 1 }) { _, _ ->
            if (++calls == 1) withContext(NonCancellable) { previous.await() } else context.copy(used = 25)
        }
        controller.open(); runCurrent()
        state.value = state.value.copy(messages = listOf(ChatMessage("a1", MessageRole.ASSISTANT, "reply")))
        controller.observe(state.value); advanceTimeBy(250); runCurrent()
        assertEquals(25L, state.value.contextPanel!!.value!!.used)
        previous.complete(context); runCurrent(); assertEquals(25L, state.value.contextPanel!!.value!!.used)
    }
}
