package app.hermes.companion

import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class RoomOwnershipDeviceTest {
    private class Api : RoomConversationApi {
        val histories = mutableListOf<CompletableDeferred<Pair<RoomRef?, List<ChatMessage>>>>()
        val posts = mutableListOf<CompletableDeferred<Int>>()
        val approvals = mutableListOf<CompletableDeferred<Unit>>()
        val roster = CompletableDeferred<List<RoomRef>>()
        override suspend fun list(origin: String) = roster.await()
        override suspend fun history(origin: String, room: String, after: Int) = CompletableDeferred<Pair<RoomRef?, List<ChatMessage>>>().also { histories += it }.await()
        override suspend fun post(origin: String, room: String, text: String) = CompletableDeferred<Int>().also { posts += it }.await()
        override suspend fun approve(origin: String, room: String, request: String, decision: String) { CompletableDeferred<Unit>().also { approvals += it }.await() }
        override fun events(origin: String, room: String): Flow<ChatEvent> = flow { awaitCancellation() }
    }
    private class Fixture(test: TestScope) : AutoCloseable {
        val state = MutableStateFlow(CompanionState(origin = "http://lab", activeProfileId = "ash"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val api = Api(); val seen = RoomSeenStore.inMemory(); var epoch = 0L
        val journal = app.hermes.companion.data.local.RoomDeliveryStore()
        val manager = RoomSessionManager(HostClientPool(), seen, state, scope, navigationEpoch = { epoch }, roomApi = api,
            deliveryStore = journal, journalDispatcher = StandardTestDispatcher(test.testScheduler))
        fun room(id: String = "a") = RoomRef(id = id, title = id, participants = emptyList())
        fun open(id: String = "a") = manager.openRoom(room(id))
        override fun close() { scope.cancel() }
    }
    @Test fun oldHistoryCannotUpdateAClosedAndReopenedRoom() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.manager.closeRoom(); f.open(); runCurrent()
            f.api.histories[0].complete(f.room().copy(seq = 9) to listOf(ChatMessage("old", MessageRole.USER, "old")))
            runCurrent(); assertTrue(f.state.value.messages.isEmpty()); assertEquals(0, f.seen.roomSeen("http://lab", "a"))
            f.api.histories[1].complete(f.room().copy(seq = 2) to listOf(ChatMessage("new", MessageRole.USER, "new")))
            runCurrent(); assertEquals("new", f.state.value.messages.single().text)
        }
    }
    @Test fun oldHistoryCannotCrossHostsWithTheSameRoomId() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(origin = "http://other", draft = "other", error = "other error")
            f.api.histories[0].complete(f.room().copy(seq = 9) to listOf(ChatMessage("old", MessageRole.USER, "old")))
            runCurrent(); assertTrue(f.state.value.messages.isEmpty()); assertEquals("other", f.state.value.draft)
            assertEquals("other error", f.state.value.error); assertEquals(0, f.seen.roomSeen("http://lab", "a"))
        }
    }
    @Test fun postAcknowledgmentCannotRenameRowsInAnotherRoom() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(draft = "send")
            f.manager.post(); runCurrent(); f.open("b"); runCurrent()
            f.state.value = f.state.value.copy(messages = listOf(ChatMessage("new", MessageRole.USER, "new")), draft = "new draft")
            f.api.posts[0].complete(40); runCurrent()
            assertEquals("new", f.state.value.messages.single().id); assertEquals("new draft", f.state.value.draft)
            assertEquals(0, f.seen.roomSeen("http://lab", "a"))
        }
    }
    @Test fun oldPostFailureCannotReplaceNewChatDraftOrError() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(draft = "send")
            f.manager.post(); runCurrent(); f.manager.closeRoom()
            f.state.value = f.state.value.copy(openSessionId = "chat", draft = "chat draft", error = "chat error")
            f.epoch++; f.api.posts[0].completeExceptionally(java.io.IOException("lost")); runCurrent()
            assertEquals("chat draft", f.state.value.draft); assertEquals("chat error", f.state.value.error)
        }
    }
    @Test fun failedPostKeepsTextTypedWhileItWasSending() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(draft = "send")
            f.manager.post(); runCurrent(); f.state.value = f.state.value.copy(draft = "new text")
            f.api.posts[0].completeExceptionally(java.io.IOException("lost")); runCurrent()
            assertEquals("new text", f.state.value.draft); assertEquals(MessageDelivery.UNCERTAIN, f.state.value.messages.single().delivery)
        }
    }
    @Test fun lostAcknowledgmentSurvivesManagerRecreationWithoutRestoringTheSubmittedDraft() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(draft = "send once")
            f.manager.post(); runCurrent(); f.api.posts[0].completeExceptionally(java.io.IOException("lost ack")); runCurrent()
            assertEquals("", f.state.value.draft); assertEquals(MessageDelivery.UNCERTAIN, f.state.value.messages.single().delivery)
            f.manager.closeRoom()
            val recreated = RoomSessionManager(HostClientPool(), f.seen, f.state, f.scope, roomApi = f.api,
                deliveryStore = f.journal, journalDispatcher = StandardTestDispatcher(testScheduler))
            recreated.openRoom(f.room()); runCurrent()
            assertEquals("send once", f.state.value.messages.single().text); assertEquals(MessageDelivery.UNCERTAIN, f.state.value.messages.single().delivery)
            assertEquals("", f.state.value.draft); assertEquals(1, f.api.posts.size)
        }
    }
    @Test fun checkingDeliveryUsesNewHistoryEvidenceAndNeverPostsAgain() = runTest {
        Fixture(this).use { f ->
            val old = ChatMessage("rm-1", MessageRole.USER, "identical")
            f.open(); runCurrent(); f.api.histories[0].complete(f.room().copy(seq = 1) to listOf(old)); runCurrent()
            f.state.value = f.state.value.copy(draft = "identical"); f.manager.post(); runCurrent()
            f.api.posts[0].completeExceptionally(java.io.IOException("lost")); runCurrent()
            val id = f.state.value.messages.last().id
            f.manager.retryMessage(id); runCurrent(); f.api.histories[1].complete(f.room().copy(seq = 1) to listOf(old)); runCurrent()
            assertEquals(MessageDelivery.UNCERTAIN, f.state.value.messages.last().delivery); assertEquals(1, f.api.posts.size)
            f.manager.retryMessage(id); runCurrent()
            val landed = ChatMessage("rm-2", MessageRole.USER, "identical")
            f.api.histories[2].complete(f.room().copy(seq = 2) to listOf(old, landed)); runCurrent()
            assertEquals(listOf("rm-1", "rm-2"), f.state.value.messages.map { it.id }); assertEquals(1, f.api.posts.size)
            assertTrue(f.journal.list(app.hermes.companion.data.local.DraftOwner("http://lab", "ash", "room:a")).isEmpty())
        }
    }
    @Test fun malformedReceiptAndServerFailureRemainUncertainAndDuplicateSubmissionIsBlocked() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(draft = "one")
            f.manager.post(); runCurrent(); f.api.posts[0].complete(0); runCurrent()
            assertEquals(MessageDelivery.UNCERTAIN, f.state.value.messages.single().delivery)
            f.state.value = f.state.value.copy(draft = "one"); f.manager.post(); runCurrent()
            assertEquals(1, f.api.posts.size); assertEquals("one", f.state.value.draft)
            f.state.value = f.state.value.copy(draft = "two"); f.manager.post(); runCurrent()
            f.api.posts[1].completeExceptionally(app.hermes.companion.data.remote.DashboardException("http_503", "server failed")); runCurrent()
            assertEquals(MessageDelivery.UNCERTAIN, f.state.value.messages.last().delivery)
        }
    }
    @Test fun explicitRejectionCanRetryButDoesNotOverwriteANewerDraft() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(draft = "rejected")
            f.manager.post(); runCurrent(); f.api.posts[0].completeExceptionally(app.hermes.companion.data.remote.DashboardException("http_403", "denied")); runCurrent()
            val row = f.state.value.messages.single(); assertEquals(MessageDelivery.FAILED, row.delivery)
            f.state.value = f.state.value.copy(draft = "new draft"); f.manager.retryMessage(row.id); runCurrent()
            assertEquals("new draft", f.state.value.draft); assertEquals(1, f.api.posts.size)
            f.state.value = f.state.value.copy(draft = ""); f.manager.retryMessage(row.id); runCurrent()
            assertEquals(2, f.api.posts.size); f.api.posts[1].complete(3); runCurrent()
            assertEquals("rm-3", f.state.value.messages.single().id); assertEquals(MessageDelivery.SENT, f.state.value.messages.single().delivery)
        }
    }
    @Test fun historyCheckCannotUpdateAnotherRoom() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(draft = "one")
            f.manager.post(); runCurrent(); f.api.posts[0].completeExceptionally(java.io.IOException("lost")); runCurrent()
            f.manager.retryMessage(f.state.value.messages.single().id); runCurrent(); f.open("b"); runCurrent()
            f.state.value = f.state.value.copy(draft = "kept", error = "new error")
            f.api.histories[1].complete(f.room().copy(seq = 9) to listOf(ChatMessage("rm-9", MessageRole.USER, "one"))); runCurrent()
            assertEquals("kept", f.state.value.draft); assertEquals("new error", f.state.value.error); assertTrue(f.state.value.messages.isEmpty())
        }
    }
    @Test fun anApprovalResponseOnlyClearsItsExactStillPendingRequest() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(approval = ApprovalPrompt("one", "command", "first"))
            f.manager.respondApproval("allow"); runCurrent()
            f.state.value = f.state.value.copy(approval = ApprovalPrompt("two", "command", "second"))
            f.api.approvals[0].complete(Unit); runCurrent()
            assertEquals("two", f.state.value.approval?.requestId)
        }
    }
    @Test fun aPendingDeepLinkLookupCannotReopenOverUserNavigation() = runTest {
        Fixture(this).use { f ->
            f.manager.openRoomById("a"); runCurrent(); f.epoch++
            f.state.value = f.state.value.copy(openSessionId = "chat", draft = "keep")
            f.api.roster.complete(listOf(f.room())); runCurrent()
            assertNull(f.state.value.openRoomId); assertEquals("chat", f.state.value.openSessionId); assertEquals("keep", f.state.value.draft)
        }
    }
    @Test fun lateInitialHistoryCannotEraseANewPost() = runTest {
        Fixture(this).use { f ->
            f.open(); runCurrent(); f.state.value = f.state.value.copy(draft = "send")
            f.manager.post(); runCurrent(); f.api.histories[0].complete(f.room() to emptyList()); runCurrent()
            assertEquals("send", f.state.value.messages.single().text); assertTrue(f.state.value.streaming)
        }
    }
    @Test fun oneEchoAdoptsOnlyOneOfTwoIdenticalPendingRows() = runTest {
        Fixture(this).use { f ->
            val rows = listOf(ChatMessage("u-room-one", MessageRole.USER, "same", delivery = MessageDelivery.SENDING),
                ChatMessage("u-room-two", MessageRole.USER, "same", delivery = MessageDelivery.SENDING))
            val next = f.manager.applyRoomEvent(f.state.value.copy(messages = rows), ChatEvent.RoomPost(4, "same"))
            assertEquals(listOf("rm-4", "u-room-two"), next.messages.map { it.id })
            assertEquals(listOf(MessageDelivery.SENT, MessageDelivery.SENDING), next.messages.map { it.delivery })
        }
    }
    @Test fun anotherPostCannotTurnARejectedRowIntoASuccess() = runTest {
        Fixture(this).use { f ->
            val row = ChatMessage("u-room-rejected", MessageRole.USER, "same", delivery = MessageDelivery.FAILED)
            val next = f.manager.applyRoomEvent(f.state.value.copy(messages = listOf(row)), ChatEvent.RoomPost(4, "same"))
            assertEquals(listOf("u-room-rejected", "rm-4"), next.messages.map { it.id })
            assertEquals(MessageDelivery.FAILED, next.messages.first().delivery)
        }
    }

}
