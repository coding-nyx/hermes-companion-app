@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.hermes.desk.feature

import app.hermes.companion.VoiceStreamState
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class DeskCoordinatorTest {
    @Test fun nativeProvidersDoNotRequireAnIdleEventSocket() = runTest {
        val f = Fixture(backgroundScope)
        f.backend.capabilities = DeskCapabilities(liveEvents = false, decisions = false)
        f.ready(); runCurrent()
        assertTrue(f.desk.state.value.connected)
        assertTrue(f.desk.state.value.canSend)
        assertNull(f.desk.state.value.error)
        assertEquals(0, f.backend.eventSubscriptions)
    }
    @Test fun connectingDoesNotCreateAThreadOrOpenTheMicrophone() = runTest {
        val f = Fixture(backgroundScope); f.connect(); runCurrent()
        assertTrue(f.desk.state.value.connected)
        assertNull(f.desk.state.value.session)
        assertEquals(0, f.backend.creates)
        assertEquals(VoiceStreamState.IDLE, f.desk.state.value.voice)
    }

    @Test fun journalMustBeDurableBeforeDispatch() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.desk.send("hello"); runCurrent()
        assertEquals(1, f.backend.sends)
        assertEquals("hello", f.backend.journalAtDispatch?.text)
        assertTrue(f.desk.state.value.working)
        f.backend.turnEvents.emit(ChatEvent.Accepted()); runCurrent()
        assertNull(f.store.pending(f.binding))
    }

    @Test fun storageFailureRefusesTheSend() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.store.writable = false
        f.desk.send("hello"); runCurrent()
        assertEquals(0, f.backend.sends)
        assertFalse(f.desk.state.value.working)
        assertTrue(f.desk.state.value.error!!.contains("not sent"))
    }

    @Test fun lostAcknowledgementIsNotRetriedAcrossReconnectOrRestart() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.backend.failTurn = true
        f.desk.send("do the work"); runCurrent()
        assertTrue(f.desk.state.value.uncertain)
        f.desk.send("do the work"); runCurrent()
        assertEquals(1, f.backend.sends)
        f.desk.close()
        val restored = DeskCoordinator(f.backend, f.store, backgroundScope)
        restored.connect(DeskConnection(f.binding.origin)); runCurrent()
        assertEquals("one", restored.state.value.session?.id)
        assertTrue(restored.state.value.uncertain)
        restored.send("do the work"); runCurrent()
        assertEquals(1, f.backend.sends)
    }

    @Test fun identicalOlderMessageDoesNotProveDeliveryButANewRowDoes() = runTest {
        val f = Fixture(backgroundScope)
        f.backend.messages = listOf(ChatMessage("old", MessageRole.USER, "hello"))
        f.ready(); runCurrent()
        f.backend.failTurn = true; f.desk.send("hello"); runCurrent()
        f.desk.refreshHistory(); runCurrent()
        assertTrue(f.desk.state.value.uncertain)
        f.backend.messages += ChatMessage("new", MessageRole.USER, "hello")
        f.desk.refreshHistory(); runCurrent()
        assertFalse(f.desk.state.value.uncertain)
    }

    @Test fun endingVoiceDoesNotInterruptAHostTask() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.desk.send("work"); runCurrent()
        f.desk.voice(VoiceStreamState.THINKING)
        f.desk.stopVoice(); runCurrent()
        assertEquals(0, f.backend.interrupts)
        assertEquals(VoiceStreamState.IDLE, f.desk.state.value.voice)
        assertTrue(f.desk.state.value.working)
        f.desk.interrupt(); runCurrent()
        assertEquals(1, f.backend.interrupts)
        assertTrue(f.desk.state.value.working) // Only a host event can confirm it stopped.
    }

    @Test fun switchingSessionDiscardsLateHistoryAndStopsVoice() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        val late = CompletableDeferred<List<ChatMessage>>()
        f.backend.historyGate = late
        f.desk.openSession(f.one); runCurrent()
        f.desk.voice(VoiceStreamState.LISTENING)
        f.backend.historyGate = null
        // Selecting another session while a history fetch is pending is currently disabled.
        f.desk.openProfile("other"); runCurrent()
        f.desk.openSession(f.other); runCurrent()
        late.complete(listOf(ChatMessage("late", MessageRole.ASSISTANT, "wrong profile"))); runCurrent()
        assertEquals("other", f.desk.state.value.profile)
        assertTrue(f.desk.state.value.messages.none { it.text == "wrong profile" })
        assertEquals(VoiceStreamState.IDLE, f.desk.state.value.voice)
    }

    @Test fun duplicateDecisionTapsSendOnceAndRetiredRequestCannotReturn() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        val prompt = ApprovalPrompt("r1", command = "read a file")
        f.backend.approval = prompt
        f.desk.refreshHistory(); runCurrent()
        f.backend.responseGate = CompletableDeferred()
        f.desk.respond(f.binding, "r1", "once")
        f.desk.respond(f.binding, "r1", "once"); runCurrent()
        assertEquals(1, f.backend.responses)
        f.backend.responseGate!!.complete(Unit); runCurrent()
        assertNull(f.desk.state.value.pending)
        f.desk.refreshHistory(); runCurrent()
        assertNull(f.desk.state.value.pending)
    }

    @Test fun lostDecisionResponseStaysDisabledUntilAnAuthoritativeRefresh() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.backend.approval = ApprovalPrompt("r1", command = "read")
        f.desk.refreshHistory(); runCurrent()
        f.backend.failResponse = true
        f.desk.respond(f.binding, "r1", "once"); runCurrent()
        f.desk.respond(f.binding, "r1", "once"); runCurrent()
        assertEquals(1, f.backend.responses)
        assertTrue(f.desk.state.value.decisionSending)
        f.backend.approval = null
        f.desk.refreshHistory(); runCurrent()
        assertNull(f.desk.state.value.pending)
        assertFalse(f.desk.state.value.decisionSending)
    }

    @Test fun destructiveApprovalRequiresPresenceAndAnOldBindingCannotApprove() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.backend.approval = ApprovalPrompt("r1", command = "rm -rf build")
        f.desk.refreshHistory(); runCurrent()
        f.desk.respond(f.binding, "r1", "once"); runCurrent()
        assertEquals(0, f.backend.responses)
        f.desk.respond(f.binding.copy(profile = "other"), "r1", "once", true); runCurrent()
        assertEquals(0, f.backend.responses)
        f.desk.respond(f.binding, "r1", "deny"); runCurrent()
        assertEquals(1, f.backend.responses)
    }

    @Test fun secretRequestsAreNeverSubmittedThroughTheGenericDecisionUi() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.backend.approval = ApprovalPrompt("secret", kind = "secret", command = "credential")
        f.desk.refreshHistory(); runCurrent()
        f.desk.respond(f.binding, "secret", "password", true); runCurrent()
        assertEquals(0, f.backend.responses)
    }

    @Test fun resumedRunningTaskBlocksNewSubmission() = runTest {
        val f = Fixture(backgroundScope)
        f.backend.running = true
        f.ready(); runCurrent()
        assertTrue(f.desk.state.value.working)
        f.desk.send("duplicate"); runCurrent()
        assertEquals(0, f.backend.sends)
    }

    @Test fun unrelatedEventsCannotChangeTheSelectedConversation() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.backend.bus.emit(BusFrame.Chat(ChatEvent.AssistantDelta("foreign"), "two")); runCurrent()
        assertTrue(f.desk.state.value.messages.isEmpty())
        f.backend.bus.emit(BusFrame.Chat(ChatEvent.AssistantDelta("ours"), "one")); runCurrent()
        assertEquals("ours", f.desk.state.value.messages.single().text)
    }

    @Test fun draftsRemainBoundToTheirConversationAndAreNotSentOnSelection() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.desk.draft("unfinished")
        f.desk.openSession(f.two); runCurrent()
        assertEquals("", f.desk.state.value.draft)
        f.desk.openSession(f.one); runCurrent()
        assertEquals("unfinished", f.desk.state.value.draft)
        assertEquals(0, f.backend.sends)
    }

    @Test fun terminalEventBeforeAcknowledgementCannotAdmitAnOverlappingSend() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.desk.send("first"); runCurrent()
        f.backend.turnEvents.emit(ChatEvent.Final("done")); runCurrent()
        assertFalse(f.desk.state.value.canSend)
        f.desk.send("second"); runCurrent()
        assertEquals(1, f.backend.sends)
        assertNull(f.store.pending(f.binding))
    }

    @Test fun compactionWithoutReturnedMessagesPreservesHistoryAndClearsItsJournal() = runTest {
        val f = Fixture(backgroundScope)
        f.backend.messages = listOf(ChatMessage("one", MessageRole.ASSISTANT, "Remember this"))
        f.ready(); runCurrent()
        f.desk.compact(); runCurrent()
        assertEquals(1, f.backend.compactions)
        assertEquals("Remember this", f.desk.state.value.messages.single().text)
        assertFalse(f.store.compactionPending(f.binding))
        assertTrue(f.desk.state.value.canSend)
    }

    @Test fun unconfirmedCompactionIsNotRetriedAcrossRestart() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.backend.compactResult = CompressionStatus.PENDING
        f.desk.compact(); runCurrent()
        assertTrue(f.desk.state.value.compactionUncertain)
        assertFalse(f.desk.state.value.canSend)
        f.desk.compact(); runCurrent()
        f.desk.close()
        val restored = DeskCoordinator(f.backend, f.store, backgroundScope)
        restored.connect(DeskConnection(f.binding.origin)); runCurrent()
        assertTrue(restored.state.value.compactionUncertain)
        restored.compact(); runCurrent()
        assertEquals(1, f.backend.compactions)
    }

    @Test fun compactionCannotRaceAToolTurnOrAnOpenMicrophone() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        f.desk.voice(VoiceStreamState.LISTENING); f.desk.compact(); runCurrent()
        assertEquals(0, f.backend.compactions)
        f.desk.voice(VoiceStreamState.IDLE); f.desk.send("work"); runCurrent()
        f.desk.compact(); runCurrent()
        assertEquals(0, f.backend.compactions)
    }

    @Test fun cameraFrameIsStagedBeforeThePromptAndReleasedAfterFailure() = runTest {
        val f = Fixture(backgroundScope); f.ready(); runCurrent()
        val bytes = byteArrayOf(1, 2, 3)
        f.backend.failTurn = true
        f.desk.send("look", bytes); runCurrent()
        assertEquals(listOf("image", "prompt"), f.backend.order)
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(f.desk.state.value.uncertain)
    }

    private class Fixture(scope: CoroutineScope) {
        val store = MemoryStore()
        val backend = FakeBackend(store)
        val desk = DeskCoordinator(backend, store, scope)
        val binding = DeskBinding("https://host.test/prefix", "default", "one")
        val one = SessionRef("one", "default", "First", 0)
        val two = SessionRef("two", "default", "Second", 0)
        val other = SessionRef("third", "other", "Other", 0)
        fun connect() = desk.connect(DeskConnection(binding.origin))
        fun ready() { store.saveSession(binding); connect() }
    }
    private class MemoryStore : DeskPersistence {
        var saved: DeskConnection? = null
        var writable = true
        val journals = mutableMapOf<DeskBinding, OutboxItem>()
        val sessions = mutableMapOf<Pair<String, String>, String>()
        val drafts = mutableMapOf<DeskBinding, String>()
        val compactions = mutableSetOf<DeskBinding>()
        override fun compactionPending(binding: DeskBinding) = binding in compactions
        override fun saveCompactionPending(binding: DeskBinding, pending: Boolean): Boolean {
            if (!writable) return false
            if (pending) compactions.add(binding) else compactions.remove(binding)
            return true
        }
        override fun connection() = saved
        override fun saveConnection(connection: DeskConnection) { saved = connection }
        override fun profile(origin: String) = "default"
        override fun saveProfile(origin: String, profile: String) = Unit
        override fun session(origin: String, profile: String) = sessions[origin to profile].orEmpty()
        override fun saveSession(binding: DeskBinding) { sessions[binding.origin to binding.profile] = binding.session }
        override fun pending(binding: DeskBinding) = journals[binding]
        override fun savePending(binding: DeskBinding, item: OutboxItem?): Boolean {
            if (!writable) return false
            if (item == null) journals.remove(binding) else journals[binding] = item
            return true
        }
        override fun draft(binding: DeskBinding) = drafts[binding].orEmpty()
        override fun saveDraft(binding: DeskBinding, text: String) { drafts[binding] = text }
    }
    private class FakeBackend(val store: MemoryStore) : DeskBackend {
        override val label = "Test"
        override var capabilities = DeskCapabilities()
        var eventSubscriptions = 0
        var creates = 0; var sends = 0; var responses = 0; var interrupts = 0
        var compactions = 0
        var compactResult = CompressionStatus.COMPRESSED
        val order = mutableListOf<String>()
        var journalAtDispatch: OutboxItem? = null
        var failTurn = false; var failResponse = false; var running = false
        var approval: ApprovalPrompt? = null
        var messages: List<ChatMessage> = emptyList()
        var historyGate: CompletableDeferred<List<ChatMessage>>? = null
        var responseGate: CompletableDeferred<Unit>? = null
        val bus = MutableSharedFlow<BusFrame>(extraBufferCapacity = 16)
        val turnEvents = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
        override suspend fun connect(connection: DeskConnection) = listOf(ProfileRef("default", "Personal", "P"), ProfileRef("other", "Other", "O"))
        override suspend fun selectProfile(profile: String) = if (profile == "default") listOf(SessionRef("one", profile, "First", 0), SessionRef("two", profile, "Second", 0))
            else listOf(SessionRef("third", profile, "Other", 0))
        override suspend fun history(binding: DeskBinding): List<ChatMessage> {
            val gate = historyGate
            return if (gate == null) messages else withContext(NonCancellable) { gate.await() }
        }
        override suspend fun createThread(profile: String): SessionRef { creates++; return SessionRef("new", profile, "New", 0) }
        override fun events(profile: String): Flow<BusFrame> { eventSubscriptions++; return bus }
        override fun belongsTo(binding: DeskBinding, eventSession: String?) = binding.session == eventSession
        override fun turn(binding: DeskBinding, text: String): Flow<ChatEvent> = flow {
            sends++; order += "prompt"; journalAtDispatch = store.pending(binding)
            if (failTurn) error("lost response")
            emitAll(turnEvents)
        }
        override suspend fun pending(binding: DeskBinding) = approval
        override fun runtime(binding: DeskBinding) = SessionRuntimeSnapshot(running)
        override suspend fun respond(binding: DeskBinding, prompt: ApprovalPrompt, choice: String) {
            responses++; if (failResponse) error("lost decision response"); responseGate?.await()
        }
        override suspend fun interrupt(binding: DeskBinding) { interrupts++ }
        override suspend fun compact(binding: DeskBinding): SessionCompression {
            compactions++
            check(store.compactionPending(binding))
            return SessionCompression(compactResult, false, "", "", "", "", 100, 20, null)
        }
        override suspend fun stageImage(binding: DeskBinding, jpeg: ByteArray) { order += "image" }
        override fun close() = Unit
    }
}
