package app.hermes.companion

import android.app.Application
import app.hermes.companion.data.local.*
import app.hermes.companion.data.remote.*
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real manager, Room outbox, OkHttp socket, codec and reducer together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class NativeChatIntegrationTest {
    @Test fun completedTurnCannotBeResurrectedByAStaleRunningHistorySnapshot() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.manager.newThread()
            f.state.update { it.copy(draft = "finish the turn") }
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.SENT } }
            val before = f.histories.get()
            // The final event arrives before the host's running flag is cleared.
            f.resumeRunning = true
            f.finish("finished answer")
            f.await { f.histories.get() > before && f.state.value.streamingSessionId == null }
            assertFalse(f.state.value.streaming)
            assertTrue(f.state.value.messages.any { it.text == "finished answer" })
            assertFalse(f.state.value.messages.any { it.text == "already speaking" })
        }
    }
    @Test fun nativeFilesAreStagedBeforeSubmittingTheirReference() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.manager.newThread()
            val file = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "native-chat-attachment.txt").apply { writeText("file contents") }
            f.state.update { it.copy(draft = "read this", pendingAttachments = listOf(
                ChatAttachment("attachment", file.absolutePath, file.name, "text/plain", ChatBlockKind.FILE))) }
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.SENT } }
            assertEquals("read this\n\n@file:/workspace/report.txt", f.receivedText)
            assertEquals(1, f.attachments.get())
            assertEquals(0, f.rejections.get())
            f.finish("read it")
            f.await { !f.state.value.streaming }
        }
    }

    @Test fun standaloneCompatibilityKeepsItsExplicitPartsDialect() = runBlocking {
        Fixture(legacy = true).use { f ->
            f.connect()
            f.manager.newThread()
            val file = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "legacy-chat-attachment.txt").apply { writeText("file contents") }
            f.state.update { it.copy(draft = "read\tthis", pendingAttachments = listOf(
                ChatAttachment("attachment", file.absolutePath, file.name, "text/plain", ChatBlockKind.FILE))) }
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.SENT } }
            assertEquals("read\tthis", f.receivedText)
            assertTrue(f.receivedParts.contains("file"))
            assertEquals(0, f.rejections.get())
            f.finish("read it")
            f.await { !f.state.value.streaming }
        }
    }

    @Test fun firstSendCreatesOneThreadAndStreamsDeliveryAndFinalContent() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.manager.newThread()
            f.state.update { it.copy(draft = "a tab\tand a newline\ninside the prompt") }
            val deliveries = CopyOnWriteArrayList<MessageDelivery>()
            val observer = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.state.collect { st -> st.messages.firstOrNull { it.role == MessageRole.USER }?.let { deliveries += it.delivery } }
            }
            f.manager.send()
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.SENT } }
            assertEquals(1, f.creates.get())
            assertEquals(1, f.submits.get())
            assertTrue(f.state.value.streaming)
            assertTrue(f.outbox.pending(f.origin, "coder").isEmpty())
            assertEquals("a tab\tand a newline\ninside the prompt", f.receivedText)
            f.finish("authoritative answer")
            f.await { !f.state.value.streaming && f.state.value.messages.any { it.text == "authoritative answer" } }
            assertTrue(MessageDelivery.QUEUED in deliveries)
            assertTrue(MessageDelivery.SENDING in deliveries)
            assertTrue(MessageDelivery.SENT in deliveries)
            assertEquals(0, f.rejections.get())
            observer.cancel()
        }
    }

    @Test fun newButtonCreatesAHostSessionAndRetainsItBeforeItsFirstMessage() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.manager.createThread()
            f.manager.createThread()
            f.await { !f.state.value.creatingThread && f.state.value.openSessionId == "stored-1" }
            assertEquals(1, f.creates.get())
            assertEquals(listOf("stored-1"), f.manager.retainCreated(f.origin, "coder", emptyList()).map { it.id })
            assertEquals(0, f.submits.get())
        }
    }

    @Test fun acceptedInputIsNeverResentWhenItsReplyConnectionDrops() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.manager.newThread()
            f.state.update { it.copy(draft = "do the work") }
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.SENT } }
            f.socket!!.close(1001, "network changed")
            f.await { f.state.value.streamingSessionId == null }
            f.manager.flushOutbox()
            assertEquals(1, f.submits.get())
            assertTrue(f.outbox.pending(f.origin, "coder").isEmpty())
            assertEquals(MessageDelivery.SENT, f.state.value.messages.first().delivery)
        }
    }

    @Test fun aLostAcknowledgmentRemainsUncertainInsteadOfRepeatingWork() = runBlocking {
        Fixture().use { f ->
            f.loseAck = true
            f.connect()
            f.manager.newThread()
            f.state.update { it.copy(draft = "do the work") }
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.UNCERTAIN } }
            assertEquals(1, f.submits.get())
            val pending = f.outbox.pending(f.origin, "coder").single()
            assertNotNull(pending.baselineUserIds)
            assertTrue(pending.lastError.startsWith("uncertain"))
            f.connect()
            f.manager.flushOutbox()
            assertEquals(1, f.submits.get())
            assertTrue(f.outbox.pending(f.origin, "coder").isEmpty())
        }
    }

    @Test fun reopeningARunningNativeSessionRestoresItsPartialReplyAndBusyState() = runBlocking {
        Fixture().use { f ->
            f.resumeRunning = true
            f.connect()
            f.manager.openSession(SessionRef("existing", "coder", "Existing", 1))
            f.await { !f.state.value.transcriptLoading }
            assertTrue(f.state.value.streaming)
            assertEquals("already speaking", f.state.value.messages.last().text)
        }
    }

    @Test fun aLateHistoryPageCannotEraseAnAcknowledgedMessage() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.holdFirstHistory = true
            f.manager.openSession(SessionRef("existing", "coder", "Existing", 1))
            f.await { f.heldHistoryId != null }
            f.state.update { it.copy(draft = "new question") }
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.SENT } }
            f.releaseHistory()
            f.await { !f.state.value.transcriptLoading }
            assertTrue(f.state.value.messages.any { it.text == "new question" })
            assertTrue(f.state.value.streaming)
            f.finish("answer")
            f.await { !f.state.value.streaming }
        }
    }

    @Test fun aLateOpenHistoryPageCannotRestartACompletedTurn() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.holdFirstHistory = true
            f.resumeRunning = true
            f.manager.openSession(SessionRef("existing", "coder", "Existing", 1))
            f.await { f.heldHistoryId != null }
            f.state.update { it.copy(draft = "new question") }
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.SENT } }
            f.finish("answer")
            f.await { f.state.value.streamingSessionId == null }
            f.releaseHistory()
            f.await { !f.state.value.transcriptLoading }
            assertFalse(f.state.value.streaming)
            assertTrue(f.state.value.messages.any { it.text == "answer" })
            assertFalse(f.state.value.messages.any { it.text == "old history" })
        }
    }

    @Test fun leavingForANewDraftDoesNotCancelAnAcceptedTurn() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.manager.newThread()
            f.state.update { it.copy(draft = "question") }
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.SENT } }
            f.manager.newThread()
            assertEquals("local:draft", f.state.value.openSessionId)
            f.finish("answer in original thread")
            f.await { f.state.value.streamingSessionId == null }
            assertEquals(1, f.submits.get())
            assertTrue(f.outbox.pending(f.origin, "coder").isEmpty())
            assertTrue(f.state.value.messages.isEmpty())
        }
    }

    @Test fun sendingPromotesAndRetiresTheRecoveredLocalDraftWithoutResurrectingIt() = runBlocking {
        Fixture(recovery = true).use { f ->
            f.connect(); f.manager.newThread()
            f.state.value = f.state.value.copy(draft = "recoverable input")
            f.manager.send()
            f.await { f.state.value.messages.any { it.delivery == MessageDelivery.SENT } }
            f.manager.closeChat(); f.manager.newThread()
            assertEquals("", f.state.value.draft)
            f.manager.openSession(SessionRef("stored-1", "coder", "one", 1))
            assertEquals("", f.state.value.draft)
            assertEquals(1, f.submits.get()); f.finish("answer")
        }
    }

    @Test fun latePromotionPreservesNewLocalTextAndDoesNotResubmitTheOriginal() = runBlocking {
        Fixture(recovery = true).use { f ->
            f.connect(); f.holdCreate = true; f.manager.newThread()
            f.state.value = f.state.value.copy(draft = "original")
            f.manager.send(); f.await { f.heldCreateId != null }
            f.manager.closeChat(); f.manager.newThread()
            f.state.value = f.state.value.copy(draft = "new local text")
            f.releaseCreate(); f.await { f.submits.get() == 1 }
            assertEquals("new local text", f.state.value.draft)
            f.manager.closeChat(); f.manager.newThread()
            assertEquals("new local text", f.state.value.draft)
            f.manager.openSession(SessionRef("stored-1", "coder", "one", 1))
            assertEquals("", f.state.value.draft); assertEquals("original", f.receivedText)
            f.finish("answer")
        }
    }

    @Test fun aFailedNewRequestLeavesTheOriginalComposerRecoverableAndAllowsRetry() = runBlocking {
        Fixture(recovery = true).use { f ->
            f.connect(); f.manager.newThread()
            f.state.value = f.state.value.copy(draft = "unfinished")
            f.rejectCreate = true; f.manager.createThread()
            f.await { f.creates.get() == 1 && !f.state.value.creatingThread }
            assertEquals("unfinished", f.state.value.draft)
            assertNotNull(f.state.value.error)
            f.rejectCreate = false; f.manager.createThread()
            f.await { f.state.value.openSessionId == "stored-2" && !f.state.value.creatingThread }
            assertEquals("", f.state.value.draft)
            f.manager.newThread(); assertEquals("unfinished", f.state.value.draft)
            assertEquals(0, f.submits.get())
        }
    }

    private class Fixture(val legacy: Boolean = false, recovery: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val http = OkHttpClient()
        val api = DashboardClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = CompanionDatabase.inMemory(RuntimeEnvironment.getApplication())
        val outbox = OutboxStore(db)
        val state = MutableStateFlow(CompanionState())
        private val draftPrefs = RuntimeEnvironment.getApplication().getSharedPreferences("native-drafts-" + java.util.UUID.randomUUID(), android.content.Context.MODE_PRIVATE)
        private val drafts = if (recovery) ComposerDraftStore(draftPrefs) else null
        @Volatile var holdCreate = false
        @Volatile var rejectCreate = false
        @Volatile var heldCreateId: String? = null
        val creates = AtomicInteger()
        val submits = AtomicInteger()
        val rejections = AtomicInteger()
        val attachments = AtomicInteger()
        val histories = AtomicInteger()
        @Volatile var receivedParts = ""
        val rows = CopyOnWriteArrayList<JsonObject>()
        @Volatile var socket: WebSocket? = null
        @Volatile var receivedText = ""
        @Volatile var loseAck = false
        @Volatile var holdFirstHistory = false
        @Volatile var resumeRunning = false
        @Volatile var heldHistoryId: String? = null
        @Volatile var activeId = "runtime-existing"
        val origin: String
        val manager: ChatSessionManager

        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.startsWith("/api/ws") == true) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            socket = webSocket
                            event("gateway.ready", null, buildJsonObject { put("heartbeat", false); put("instance_id", if (legacy) "companion-standalone" else "test") })
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val root = Json.parseToJsonElement(text).jsonObject
                            val id = root["id"]!!.jsonPrimitive.content
                            val method = root["method"]!!.jsonPrimitive.content
                            val p = root["params"]!!.jsonObject
                            val allowed = when (method) {
                                "session.create" -> setOf("profile", "title", "model", "idempotency_key")
                                "session.resume" -> setOf("profile", "session_id", "omit_messages", "defer_history", "inline_images")
                                "session.history" -> setOf("profile", "session_id")
                                "prompt.submit" -> setOf("profile", "session_id", "text") +
                                    if (legacy) setOf("model", "parts", "client_id") else emptySet()
                                "file.attach" -> setOf("profile", "session_id", "data_url", "name")
                                else -> emptySet()
                            }
                            if (p.keys.any { it !in allowed }) {
                                rejections.incrementAndGet()
                                webSocket.send("""{"jsonrpc":"2.0","id":"$id","error":{"code":4000,"message":"unknown params"}}""")
                                return
                            }
                            when (method) {
                                "file.attach" -> {
                                    attachments.incrementAndGet()
                                    result(id, """{"attached":true,"ref_text":"@file:/workspace/report.txt","ref_path":"/workspace/report.txt"}""")
                                }
                                "session.create" -> {
                                    val n = creates.incrementAndGet()
                                    activeId = "runtime-$n"
                                    if (rejectCreate) webSocket.send("""{"jsonrpc":"2.0","id":"$id","error":{"code":4001,"message":"create rejected"}}""")
                                    else if (holdCreate) heldCreateId = id
                                    else result(id, """{"session_id":"runtime-$n","stored_session_id":"stored-$n","info":{"profile_name":"coder"}}""")
                                }
                                "session.resume" -> result(id, if (resumeRunning)
                                    """{"session_id":"$activeId","stored_session_id":"existing","running":true,"inflight":{"assistant":"already speaking","streaming":true}}"""
                                    else """{"session_id":"$activeId","stored_session_id":"existing"}""")
                                "session.history" -> {
                                    histories.incrementAndGet()
                                    if (holdFirstHistory && heldHistoryId == null) {
                                        heldHistoryId = id
                                    } else result(id, buildJsonObject { put("messages", JsonArray(rows.toList())) }.toString())
                                }
                                "prompt.submit" -> {
                                    submits.incrementAndGet()
                                    receivedText = p["text"]!!.jsonPrimitive.content
                                    receivedParts = p["parts"]?.toString().orEmpty()
                                    rows += buildJsonObject { put("row_id", rows.size + 1); put("role", "user"); put("content", receivedText) }
                                    if (loseAck) webSocket.close(1001, "ack lost")
                                    else result(id, """{"status":"streaming","user_row_id":1}""")
                                }
                            }
                        }
                    })
                    if (request.path == "/companion/media") return MockResponse().setBody("""{"url":"/companion/media/file.txt"}""")
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start()
            origin = server.url("/").toString().trimEnd('/')
            state.value = CompanionState(origin = origin, activeProfileId = "coder")
            manager = ChatSessionManager(HostClientPool { api }, TranscriptCache(db), outbox, state, scope, drafts)
        }

        suspend fun connect() { api.wsHello(origin, "coder") }
        suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(5) }
        fun result(id: String, json: String) { socket!!.send("""{"jsonrpc":"2.0","id":"$id","result":$json}""") }
        fun event(type: String, sid: String?, payload: JsonObject) {
            val params = buildJsonObject { put("type", type); sid?.let { put("session_id", it) }; put("payload", payload) }
            socket!!.send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "event"); put("params", params) }.toString())
        }
        fun finish(text: String) {
            rows += buildJsonObject { put("row_id", rows.size + 1); put("role", "assistant"); put("content", text) }
            event("message.delta", activeId, buildJsonObject { put("text", "partial") })
            event("message.complete", activeId, buildJsonObject { put("text", text); put("status", "complete") })
        }
        fun releaseCreate() { result(heldCreateId!!, """{"session_id":"runtime-1","stored_session_id":"stored-1","info":{"profile_name":"coder"}}""") }
        fun releaseHistory() { result(heldHistoryId!!, """{"messages":[{"row_id":0,"role":"user","content":"old history"}]}""") }
        override fun close() {
            scope.cancel()
            api.closeRpc()
            server.close()
            db.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
