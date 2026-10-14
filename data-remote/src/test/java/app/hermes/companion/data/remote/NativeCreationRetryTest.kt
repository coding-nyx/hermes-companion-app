package app.hermes.companion.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeCreationRetryTest {
    @Test fun businessFailureIsNotAValidationReceiptAndNeverRetriesCreation() = runBlocking {
        for (message in listOf("session initialization failed after allocation",
            "Unsupported provider; request contained idempotency_key",
            "invalid params for session.create: model: Extra inputs are not permitted",
            "invalid params for session.branch_stored: idempotency_key: Extra inputs are not permitted",
            "invalid params for session.create: idempotency_key: Extra inputs are not permitted; allocation failed")) fixture(4000, message) { api, origin, calls ->
            assertEquals("rpc_4000", (runCatching { api.createSession(origin, "fixture", "test", idempotencyKey = "key", requireRpc = true) }.exceptionOrNull() as DashboardException).code)
            assertEquals(1, calls.size)
        }
    }
    @Test fun installedHostPreDispatchValidationRetriesOnlyWithoutTheRejectedField() = runBlocking {
        fixture(4000, "invalid params for session.create: idempotency_key: Extra inputs are not permitted — the client and the Hermes backend are out of sync (different versions); run `hermes update` and restart both") { api, origin, calls ->
            assertEquals("stored", api.createSession(origin, "fixture", "test", idempotencyKey = "key", requireRpc = true).id)
            assertEquals(2, calls.size)
            assertEquals(calls[0]["params"]!!.jsonObject.filterKeys { it != "idempotency_key" }, calls[1]["params"]!!.jsonObject)
        }
    }
    @Test fun explicitlyRejectedOptionalFieldRetriesOnceWithOtherwiseIdenticalParameters() = runBlocking {
        fixture(4000, "Unknown parameter: idempotency_key") { api, origin, calls ->
            val created = api.createSession(origin, "fixture", "test", idempotencyKey = "key", requireRpc = true)
            assertEquals("stored", created.id); assertEquals(2, calls.size)
            val first = calls[0]["params"]!!.jsonObject; val second = calls[1]["params"]!!.jsonObject
            assertEquals("key", first["idempotency_key"]!!.jsonPrimitive.content)
            assertEquals(first.filterKeys { it != "idempotency_key" }, second)
        }
    }
    @Test fun standardInvalidParamsPermitsTheExistingCompatibilityPathButGenericErrorsDoNot() = runBlocking {
        fixture(-32602, "Invalid parameters") { api, origin, calls ->
            assertEquals("stored", api.createSession(origin, "fixture", "test", idempotencyKey = "key", requireRpc = true).id)
            assertEquals(2, calls.size)
        }
        fixture(-32603, "Internal failure") { api, origin, calls ->
            assertTrue(runCatching { api.createSession(origin, "fixture", "test", idempotencyKey = "key", requireRpc = true) }.isFailure)
            assertEquals(1, calls.size)
        }
    }
    private suspend fun fixture(code: Int, message: String, check: suspend (DashboardClient, String, List<JsonObject>) -> Unit) {
        val calls = CopyOnWriteArrayList<JsonObject>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"method":"event","params":{"type":"gateway.ready","payload":{"instance_id":"creation-fixture"}}}""") }
                override fun onMessage(ws: WebSocket, text: String) {
                    val request = Json.parseToJsonElement(text).jsonObject
                    assertEquals("session.create", request["method"]!!.jsonPrimitive.content)
                    calls += request
                    val id = request["id"]!!.jsonPrimitive.content
                    val reply = if (calls.size == 1) buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", code); put("message", message) })
                    } else buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", id)
                        put("result", buildJsonObject { put("session_id", "live"); put("stored_session_id", "stored"); put("title", "test") })
                    }
                    ws.send(reply.toString())
                }
            }))
            val origin = server.url("/").toString().trimEnd('/'); val api = DashboardClient()
            try { api.wsHello(origin, "fixture"); check(api, origin, calls) } finally { api.closeRpc() }
        }
    }
}
