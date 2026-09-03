package app.hermes.companion.data.remote

import app.hermes.companion.model.BusFrame
import app.hermes.companion.model.ChatEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardClientTest {
    @Test
    fun listSessionsSendsProfileQueryAndDropsForeignRows() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"sessions":[
                      {"id":"a","profile":"coder","title":"fix auth"},
                      {"id":"b","profile":"ops","title":"should not appear"}
                    ]}
                    """.trimIndent(),
                ),
            )
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val sessions = client.listSessions(origin, "coder")
            assertEquals(listOf("a"), sessions.map { it.id })
            val recorded = server.takeRequest()
            assertEquals("/api/sessions?profile=coder", recorded.path)
            assertTrue(sessions.none { it.profileId != "coder" })
        }
    }

    @Test
    fun messagesAndStreamCarryProfile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"}]}""",
                ),
            )
            val sse = Buffer().writeUtf8(
                "event: assistant.delta\ndata: {\"text\":\"ok\"}\n\nevent: run.completed\ndata: {}\n\n",
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val messages = client.listMessages(origin, "sess-cod-1", "coder")
            assertEquals("hi", messages.first().text)
            assertEquals("/api/sessions/sess-cod-1/messages?profile=coder", server.takeRequest().path)
            val events = client.streamTurn(origin, "sess-cod-1", "coder", "ping").toList()
            assertTrue(events.any { it is ChatEvent.AssistantDelta && it.text == "ok" })
            assertTrue(events.any { it is ChatEvent.Completed })
            val posted = server.takeRequest()
            assertEquals("/api/sessions/sess-cod-1/chat/stream?profile=coder", posted.path)
            assertTrue(posted.body.readUtf8().contains("\"profile\":\"coder\""))
        }
    }

    @Test
    fun parseSseMapsHermesAndMockFrames() {
        val delta = DashboardClient.parseSse("assistant.delta", """{"text":"hi"}""")
        val tool = DashboardClient.parseSse("tool.started", """{"name":"terminal","detail":"ls"}""")
        val done = DashboardClient.parseSse("run.completed", "{}")
        assertEquals(ChatEvent.AssistantDelta("hi"), delta)
        assertEquals(ChatEvent.ToolStarted("terminal", "ls"), tool)
        assertEquals(ChatEvent.Completed, done)
        val apr = DashboardClient.parseSse(
            "approval.request",
            """{"request_id":"apr-1","command":"rm -rf build/","choices":["once","deny"]}""",
        )
        assertTrue(apr is ChatEvent.Approval)
        assertEquals("rm -rf build/", (apr as ChatEvent.Approval).prompt.command)
    }

    @Test
    fun parseGatewayReadyFromJsonRpc() {
        val raw = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":true,"heartbeat":true,"instance_id":"abc"}}}"""
        val hello = GatewaySocket.parseReady(raw)
        assertEquals(true, hello?.heartbeat)
        assertEquals("abc", hello?.instanceId)
    }

    @Test
    fun listProfilesParsesDashboardAndBridgeShapes() {
        val dashboard = parseProfiles(
            """{"profiles":[{"id":"coder","display_name":"Coder","model":"sonnet-4.6","gateway":"running","session_count":2}]}""",
        )
        val bridge = parseProfiles(
            """{"profiles":[{"profile_id":"ops","display_name":"Ops"}]}""",
        )
        val names = parseProfiles("""{"profiles":["personal"]}""")
        assertEquals("COD", dashboard.single().glyph)
        assertEquals("ops", bridge.single().id)
        assertEquals("PER", names.single().glyph)
        val live = parseProfiles(
            """{"profiles":[{"name":"knight","display_name":"","model":"gpt-5.6-terra","gateway_running":true}]}""",
        )
        assertEquals("knight", live.single().id)
        assertEquals("running", live.single().gateway)
    }

    @Test
    fun parseSessionsFromRpcList() {
        val rows = parseSessions(
            """{"sessions":[{"id":"20260901_193952_8a33ee0b","title":"Nyx","preview":"hi","started_at":1756750000,"source":"telegram"}]}""",
        )
        assertEquals("20260901_193952_8a33ee0b", rows.single().id)
        assertEquals("Nyx", rows.single().title)
        assertEquals(1756750000_000L, rows.single().updatedAtEpochMs)
    }

    @Test
    fun parseHistoryContentBlocks() {
        val messages = parseMessages(
            """{"count":1,"messages":[{"role":"assistant","content":[{"type":"text","text":"hello "} ,{"type":"text","text":"lab"}]}]}""",
        )
        assertEquals("hello lab", messages.single().text)
    }

    @Test
    fun parseRpcMessageDeltaAndComplete() {
        val delta = RpcCodec.parseLine(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"live-a","payload":{"text":"ok"}}}""",
        ) as RpcInbound.Event
        assertEquals(ChatEvent.AssistantDelta("ok"), RpcCodec.toChatEvent(delta.event))
        val done = RpcCodec.parseLine(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","payload":{}}}""",
        ) as RpcInbound.Event
        assertEquals(ChatEvent.Completed, RpcCodec.toChatEvent(done.event))
    }

    @Test
    fun parseClarifyExpireAndSessionsChanged() {
        val clarify = RpcCodec.parseLine(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request","session_id":"s1","payload":{"request_id":"cl-1","question":"compact now?","options":["yes","later","deny"]}}}""",
        ) as RpcInbound.Event
        val chat = RpcCodec.toChatEvent(clarify.event) as ChatEvent.Approval
        assertEquals("clarify", chat.prompt.kind)
        assertEquals("compact now?", chat.prompt.command)
        assertEquals(listOf("yes", "later", "deny"), chat.prompt.choices)
        val expired = RpcCodec.parseLine(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"approval.expire","payload":{"request_id":"apr-1"}}}""",
        ) as RpcInbound.Event
        assertEquals(ChatEvent.PromptExpired("apr-1"), RpcCodec.toChatEvent(expired.event))
        val client = DashboardClient(okhttp3.OkHttpClient())
        val patch = client.decodeBus(
            RpcEvent(
                type = "sessions.changed",
                sessionId = "a",
                payload = kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"id":"a","profile":"coder","op":"upsert","title":"fix auth"}""",
                ).jsonObject,
            ),
            "coder",
        ) as BusFrame.SessionPatch
        assertEquals("a", patch.change.session.id)
        assertEquals("coder", patch.change.session.profileId)
        val refetch = client.decodeBus(
            RpcEvent(type = "sessions.changed", payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"op":"upsert"}""").jsonObject),
            "coder",
        )
        assertEquals(BusFrame.SessionRefetch, refetch)
    }

    @Test
    fun rpcPingOverPersistentSocket() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":true,"heartbeat":true,"instance_id":"t1"}}}""",
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = Json.parseToJsonElement(text).jsonObject
                            val id = obj["id"]!!.jsonPrimitive.content
                            val method = obj["method"]!!.jsonPrimitive.content
                            if (method == "gateway.ping") {
                                webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":{"ok":true}}""")
                            }
                        }
                    },
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            client.wsHello(origin, "coder")
            client.ping()
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun rpcListResumeAndSubmitOverPersistentSocket() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":true,"heartbeat":false,"instance_id":"t1"}}}""",
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = Json.parseToJsonElement(text).jsonObject
                            val id = obj["id"]!!.jsonPrimitive.content
                            val method = obj["method"]!!.jsonPrimitive.content
                            when (method) {
                                "session.list" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"sessions":[{"id":"a","title":"fix","profile":"coder"}]}}""",
                                )
                                "session.resume" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"live-a","stored_session_id":"a","messages":[{"role":"user","content":"hi"}]}}""",
                                )
                                "prompt.submit" -> {
                                    webSocket.send(
                                        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"live-a","payload":{"text":"ok"}}}""",
                                    )
                                    webSocket.send(
                                        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"live-a","payload":{}}}""",
                                    )
                                    webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":{"status":"streaming"}}""")
                                }
                            }
                        }
                    },
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            val hello = client.wsHello(origin, "coder")
            assertEquals("t1", hello.instanceId)
            val sessions = client.listSessions(origin, "coder")
            assertEquals(listOf("a"), sessions.map { it.id })
            val messages = client.listMessages(origin, "a", "coder")
            assertEquals("hi", messages.first().text)
            val events = client.streamTurn(origin, "a", "coder", "ping").toList()
            assertTrue(events.any { it is ChatEvent.AssistantDelta && it.text == "ok" })
            assertTrue(events.any { it is ChatEvent.Completed })
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun MockWebServer.toOkHttp() = okhttp3.OkHttpClient.Builder()
        .build()
}
