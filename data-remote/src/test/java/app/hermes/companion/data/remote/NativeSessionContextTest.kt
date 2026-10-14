package app.hermes.companion.data.remote

import app.hermes.companion.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeSessionContextTest {
    @Test fun contextUsesCapturedProfileAndResumedRuntime() = runBlocking {
        exercise("context") { client, origin, calls ->
            val context = client.sessionContext(origin, session)
            assertEquals(320L, context.used)
            assertEquals(10.0, context.percent!!, 0.001)
            assertEquals("Tools", context.categories.single().label)
            assertEquals(listOf("session.resume", "session.context_breakdown"), calls.map { it.string("method") })
            val params = calls.last()["params"]!!.jsonObject
            assertEquals("live-context", params.string("session_id"))
            assertEquals("coder", params.string("profile"))
        }
    }

    @Test fun compressionIsDedicatedAndOmitsBlankFocusTopic() = runBlocking {
        exercise("compressed") { client, origin, calls ->
            val result = client.compressSession(origin, session, "  ")
            assertEquals(CompressionStatus.COMPRESSED, result.status)
            assertNull(result.messages)
            assertEquals("session.compress", calls.last().string("method"))
            assertFalse(calls.last()["params"]!!.jsonObject.containsKey("focus_topic"))
            assertEquals(1, calls.count { it.string("method") == "session.compress" })
        }
    }

    @Test fun pendingIsNotCompletedOrRetriedAndTopicIsTrimmed() = runBlocking {
        exercise("pending") { client, origin, calls ->
            val result = client.compressSession(origin, session, "  preserve decisions  ")
            assertEquals(CompressionStatus.PENDING, result.status)
            assertNull(result.messages)
            assertEquals("preserve decisions", calls.last()["params"]!!.jsonObject.string("focus_topic"))
            assertEquals(2, calls.size)
        }
    }

    @Test fun lostResponseAndUnsupportedNeverReplayCompression() = runBlocking {
        for (mode in listOf("disconnect", "unsupported", "busy")) {
            exercise(mode) { client, origin, calls ->
                assertTrue(runCatching { client.compressSession(origin, session) }.isFailure)
                assertEquals(1, calls.count { it.string("method") == "session.compress" })
            }
        }
    }

    @Test fun parsingKeepsZeroUnknownAndMalformedCountsDistinct() {
        val zero = parseSessionContext(json("""{"context_used":0,"context_max":1000,"estimated_total":900,"context_estimated":false}"""))
        assertEquals(0L, zero.used); assertEquals(0.0, zero.percent!!, 0.0)
        assertEquals(false, zero.estimated)
        val missing = parseSessionContext(json("""{"estimated_total":900,"categories":[null,{}, {"label":"Bad","tokens":-1}]}"""))
        assertNull(missing.used); assertNull(missing.maximum); assertNull(missing.percent)
        assertTrue(missing.categories.isEmpty())
        val malformed = parseSessionContext(json("""{"context_used":{},"context_max":-5,"context_estimated":[],"model":{}}"""))
        assertNull(malformed.used); assertNull(malformed.maximum); assertNull(malformed.estimated)
    }

    @Test fun smallPercentIsNeverMistakenForFraction() {
        val value = parseSessionContext(json("""{"context_used":5,"context_max":1000,"context_percent":0.5}"""))
        assertEquals(0.5, value.percent!!, 0.00001)
    }

    @Test fun emptyTranscriptIsDistinctFromAbsentAndSummaryAbortIsAuthoritative() {
        val result = parseSessionCompression(json("""{"status":"compressed","messages":[],"summary":{"aborted":true}}"""))
        assertEquals(CompressionStatus.ABORTED, result.status)
        assertEquals(emptyList<ChatMessage>(), result.messages)
        val locked = parseSessionCompression(json("""{"lock_held":true,"message":"Already in progress"}"""))
        assertTrue(locked.lockHeld); assertEquals(CompressionStatus.UNKNOWN, locked.status)
        assertNull(locked.messages)
    }

    private val session = SessionRef("stored-context", "coder", "Synthetic context", 0)
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()

    private suspend fun exercise(mode: String, check: suspend (DashboardClient, String, List<JsonObject>) -> Unit) {
        val calls = CopyOnWriteArrayList<JsonObject>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send("""{"method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":false,"instance_id":"context-native"}}}""")
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    val row = json(text); calls += row
                    val id = row.string("id")
                    when {
                        row.string("method") == "session.resume" -> ws.send("""{"id":"$id","result":{"session_id":"live-context","stored_session_id":"stored-context"}}""")
                        mode == "disconnect" -> ws.close(1001, "response lost")
                        mode == "unsupported" || mode == "busy" -> ws.send("""{"id":"$id","error":{"code":${if (mode == "busy") 4009 else -32601},"message":"$mode"}}""")
                        mode == "context" -> ws.send("""{"id":"$id","result":{"context_used":320,"context_max":3200,"context_estimated":true,"categories":[{"id":"tools","label":"Tools","tokens":100}]}}""")
                        else -> ws.send("""{"id":"$id","result":{"status":"$mode"}}""")
                    }
                }
            }))
            val client = DashboardClient()
            try { check(client, server.url("/").toString().trimEnd('/'), calls) }
            finally { client.closeRpc() }
        }
    }
}
