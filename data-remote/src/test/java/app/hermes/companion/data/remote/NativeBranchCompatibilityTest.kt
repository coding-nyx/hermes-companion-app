package app.hermes.companion.data.remote

import app.hermes.companion.model.SessionRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeBranchCompatibilityTest {
    @Test fun explicitOptionalFieldRejectionCanRetryWithoutTheKey() = runBlocking {
        exercise("field") { client, origin, calls ->
            val result = client.branchSession(origin, SessionRef("parent", "coder", "Original", 0), "stable-key")
            assertEquals("branch-stored", result.id)
            assertEquals(2, calls.size)
            assertEquals("stable-key", calls[0]["idempotency_key"]!!.jsonPrimitive.content)
            assertFalse(calls[1].containsKey("idempotency_key"))
            assertEquals("parent", calls[1]["parent_session_id"]!!.jsonPrimitive.content)
        }
    }

    @Test fun unrelatedValidationAndLostResponsesNeverRepeatTheMutation() = runBlocking {
        for (failure in listOf("unrelated", "business", "wrong_method", "disconnect")) {
            exercise(failure) { client, origin, calls ->
                val result = runCatching { client.branchSession(origin, SessionRef("parent", "coder", "Original", 0), "stable-key") }
                assertTrue(result.isFailure)
                assertEquals(1, calls.size)
            }
        }
    }

    private suspend fun exercise(failure: String, check: suspend (DashboardClient, String, List<JsonObject>) -> Unit) {
        val calls = CopyOnWriteArrayList<JsonObject>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send("""{"method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":false,"instance_id":"old-native"}}}""")
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    val id = obj["id"]!!.jsonPrimitive.content
                    calls += obj["params"]!!.jsonObject
                    when {
                        calls.size > 1 -> ws.send("""{"id":"$id","result":{"session_id":"branch-live","stored_session_id":"branch-stored","message_count":2,"info":{"profile":"coder"}}}""")
                        failure == "disconnect" -> ws.close(1001, "response lost")
                        else -> {
                            val message = when (failure) {
                                "field" -> "idempotency_key: Extra inputs are not permitted"
                                "business" -> "Branch allocation failed; idempotency_key was present"
                                "wrong_method" -> "invalid params for session.create: idempotency_key: Extra inputs are not permitted"
                                else -> "parent_session_id is invalid"
                            }
                            ws.send("""{"id":"$id","error":{"code":4000,"message":"$message"}}""")
                        }
                    }
                }
            }))
            val client = DashboardClient()
            try { check(client, server.url("/").toString().trimEnd('/'), calls) }
            finally { client.closeRpc() }
        }
    }
}
