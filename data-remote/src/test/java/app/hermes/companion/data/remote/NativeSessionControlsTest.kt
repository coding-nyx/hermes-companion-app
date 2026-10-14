package app.hermes.companion.data.remote

import app.hermes.companion.model.SessionRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeSessionControlsTest {
    private val session = SessionRef("stored", "ash", "fixture", 0)
    @Test fun sessionSettingsUseTheCapturedRuntimeAndNeverGlobalScope() = runBlocking {
        fixture { api, origin, calls ->
            val settings = api.sessionSettings(origin, session)
            assertEquals("fixture-model", settings.model); assertEquals("fixture-provider", settings.provider)
            assertEquals("low", settings.reasoning); assertEquals("normal", settings.fast)
            val result = api.setSessionSetting(origin, session, "model", "new-model --provider other --session")
            assertTrue(result.confirmationRequired); assertFalse(result.deferred)
            val params = calls.last()["params"]!!.jsonObject
            assertEquals("live", params["session_id"]!!.jsonPrimitive.content)
            assertEquals("ash", params["profile"]!!.jsonPrimitive.content)
            assertEquals("session", params["scope"]!!.jsonPrimitive.content)
            assertFalse(params["confirm_expensive_model"]!!.jsonPrimitive.boolean)
            assertEquals(1, calls.count { it["method"]!!.jsonPrimitive.content == "config.set" })
        }
    }
    @Test fun nativeCatalogFiltersMalformedRowsAndRedirectReportsTheHostOutcome() = runBlocking {
        fixture { api, origin, calls ->
            assertEquals(listOf("status"), api.commandCatalog(origin, session).map { it.name })
            val result = api.dispatchCommand(origin, session, "status", "arg\nwith \"quotes\"")
            assertEquals("exec", result.type); assertEquals("result", result.output)
            assertEquals("arg\nwith \"quotes\"", calls.last()["params"]!!.jsonObject["arg"]!!.jsonPrimitive.content)
            assertEquals("queued", api.redirectSession(origin, session, "change direction").status)
            assertEquals("session.redirect", calls.last()["method"]!!.jsonPrimitive.content)
        }
    }
    @Test fun transportFailureDoesNotRetryConfigOrDispatch() = runBlocking {
        fixture(fail = true) { api, origin, calls ->
            assertTrue(runCatching { api.setSessionSetting(origin, session, "reasoning", "low") }.isFailure)
            assertEquals(1, calls.count { it["method"]!!.jsonPrimitive.content == "config.set" })
        }
    }
    private suspend fun fixture(fail: Boolean = false, check: suspend (DashboardClient, String, List<JsonObject>) -> Unit) {
        val calls = CopyOnWriteArrayList<JsonObject>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":false,"instance_id":"controls-fixture"}}}""") }
                override fun onMessage(ws: WebSocket, text: String) {
                    val request = Json.parseToJsonElement(text).jsonObject; calls += request
                    val id = request["id"]!!.jsonPrimitive.content; val method = request["method"]!!.jsonPrimitive.content
                    val value = when (method) {
                        "session.resume" -> """{"session_id":"live","stored_session_id":"stored"}"""
                        "model.options" -> """{"model":"fixture-model","provider":"fixture-provider"}"""
                        "config.get" -> if (request["params"]!!.jsonObject["key"]!!.jsonPrimitive.content == "reasoning") """{"value":"low"}""" else """{"value":"normal"}"""
                        "config.set" -> if (fail) { ws.close(1001, "lost response"); return } else """{"key":"model","confirm_required":true,"confirm_message":"Confirm cost","scope":"session"}"""
                        "commands.catalog" -> """{"pairs":[["/status","Status"],null,[],["bad name","bad"],["status","duplicate"]]}"""
                        "slash.exec" -> { ws.send("""{"jsonrpc":"2.0","id":"$id","error":{"code":4002,"message":"skill command: use command.dispatch for /status"}}"""); return }
                        "command.dispatch" -> """{"type":"exec","output":"result"}"""
                        "session.redirect" -> """{"status":"queued","text":"change direction"}"""
                        else -> error(method)
                    }
                    ws.send("""{"jsonrpc":"2.0","id":"$id","result":$value}""")
                }
            }))
            server.start(); val origin = server.url("/").toString().trimEnd('/'); val api = DashboardClient()
            try { api.wsHello(origin, "ash"); check(api, origin, calls) } finally { api.closeRpc() }
        }
    }
}
