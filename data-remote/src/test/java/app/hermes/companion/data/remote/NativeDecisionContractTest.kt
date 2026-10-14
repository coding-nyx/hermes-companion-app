package app.hermes.companion.data.remote

import app.hermes.companion.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class NativeDecisionContractTest {
    @Test fun zeroAndFalseReceiptsExpireTheExactRequestAndCannotReappearFromRest() = runBlocking {
        for (resolved in listOf("0", "false")) fixture(resolved) { api, origin ->
            val prompt = ApprovalPrompt("fixture-request", command = "Synthetic")
            val failure = runCatching { api.respondPrompt(origin, "s1", "fixture", prompt, "once") }.exceptionOrNull()
            assertEquals("request_expired", (failure as DashboardException).code)
            assertTrue(api.isPromptRetired("s1", prompt.requestId))
            assertNull(api.pendingApproval(origin, "s1", "fixture"))
            assertFalse(api.isPromptRetired("s2", prompt.requestId))
        }
    }
    @Test fun successfulReceiptRetiresOnlyItsExactSessionAndRequest() = runBlocking {
        fixture("1") { api, origin ->
            api.respondPrompt(origin, "s1", "fixture", ApprovalPrompt("fixture-request", command = "Synthetic"), "deny")
            assertTrue(api.isPromptRetired("s1", "fixture-request"))
            assertFalse(api.isPromptRetired("s1", "second-request")); assertFalse(api.isPromptRetired("s2", "fixture-request"))
            assertNull(api.pendingApproval(origin, "s1", "fixture"))
        }
    }
    @Test fun expiredBusEventRejectsDelayedRepeatButDoesNotSuppressAnotherSession() {
        val api = DashboardClient()
        val payload = buildJsonObject { put("request_id", "fixture-request"); put("command", "Synthetic") }
        assertNotNull(api.decodeBus(RpcEvent("approval.expired", "s1", payload), "fixture"))
        assertNull(api.decodeBus(RpcEvent("approval.request", "s1", payload), "fixture"))
        assertNotNull(api.decodeBus(RpcEvent("approval.request", "s2", payload), "fixture"))
        assertNotNull(api.decodeBus(RpcEvent("approval.request", "s1",
            JsonObject(payload + ("request_id" to JsonPrimitive("second-request")))), "fixture"))
    }
    private suspend fun fixture(resolved: String, test: suspend (DashboardClient, String) -> Unit) {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().startsWith("/api/ws")) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"method":"event","params":{"type":"gateway.ready","payload":{"instance_id":"decision-fixture"}}}""") }
                        override fun onMessage(ws: WebSocket, text: String) {
                            val rpc = Json.parseToJsonElement(text).jsonObject
                            assertEquals("approval.respond", rpc["method"]!!.jsonPrimitive.content)
                            assertEquals("fixture-request", rpc["params"]!!.jsonObject["request_id"]!!.jsonPrimitive.content)
                            ws.send("""{"id":${rpc["id"]},"result":{"resolved":$resolved}}""")
                        }
                    })
                    return MockResponse().setBody("""{"request_id":"fixture-request","command":"Synthetic"}""")
                }
            }
            val api = DashboardClient(); val origin = server.url("/").toString().trimEnd('/')
            try { api.wsHello(origin, "fixture"); test(api, origin) } finally { api.closeRpc() }
        }
    }
}
