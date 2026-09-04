package app.hermes.companion.data.remote

import app.hermes.companion.domain.RewindSubmit
import app.hermes.companion.model.BusFrame
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.DeviceCred
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
import org.junit.Assert.assertFalse
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
            val path = server.takeRequest().path.orEmpty()
            assertTrue(path.startsWith("/api/sessions/sess-cod-1/messages?"))
            assertTrue(path.contains("profile=coder"))
            assertTrue(path.contains("limit="))
            val events = client.streamTurn(origin, "sess-cod-1", "coder", "ping").toList()
            assertTrue(events.any { it is ChatEvent.AssistantDelta && it.text == "ok" })
            assertTrue(events.any { it is ChatEvent.Completed })
            val posted = server.takeRequest()
            assertEquals("/api/sessions/sess-cod-1/chat/stream?profile=coder", posted.path)
            assertTrue(posted.body.readUtf8().contains("\"profile\":\"coder\""))
        }
    }

    @Test
    fun pageMessagesSendsBeforeAndClipsTail() = runBlocking {
        MockWebServer().use { server ->
            val body = (1..120).joinToString(",", prefix = """{"messages":[""", postfix = "]}") { i ->
                """{"id":"m$i","role":"user","content":"$i"}"""
            }
            server.enqueue(MockResponse().setBody(body))
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val page = client.pageMessages(origin, "sess-long", "coder", beforeId = "m41")
            val recorded = server.takeRequest()
            assertTrue(recorded.path.orEmpty().contains("before=m41"))
            assertTrue(recorded.path.orEmpty().contains("limit="))
            assertEquals("m1", page.messages.first().id)
            assertEquals("m40", page.messages.last().id)
            assertFalse(page.messages.any { it.id == "m41" })
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
    fun parseStatusReadsPlatformsAndPressure() {
        val status = parseStatus(
            """
            {
              "auth_required": false,
              "version": "0.20.6",
              "gateway_running": true,
              "gateway_state": "running",
              "gateway_platforms": {
                "telegram": {"state": "connected"},
                "ops:discord": {"state": "error", "error_code": "no_token", "error_message": "no token"}
              },
              "memory": {"pressure": "ok"},
              "disk": {"pressure": "elevated"}
            }
            """.trimIndent(),
        )
        assertEquals("0.20.6", status.version)
        assertTrue(status.gatewayRunning)
        assertEquals("running", status.gatewayState)
        assertEquals("connected", status.platforms.first { it.name == "telegram" }.state)
        assertEquals("no token", status.platforms.first { it.name == "ops:discord" }.error)
        assertEquals("ok", status.memoryPressure)
        assertEquals("elevated", status.diskPressure)
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
                                "session.history" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"messages":[{"role":"user","content":"hi"}]}}""",
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

    @Test
    fun messagesPreferDurableRowId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"messages":[{"id":"uuid-1","row_id":42,"role":"user","content":"hi"}]}""",
                ),
            )
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val messages = client.listMessages(origin, "sess-cod-1", "coder")
            assertEquals("42", messages.first().id)
        }
    }

    @Test
    fun rewindSubmitSendsTruncateAndOrdinaryDoesNot() = runBlocking {
        val submitted = mutableListOf<String>()
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
                                "session.resume" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"live-a","stored_session_id":"a","messages":[{"role":"user","row_id":11,"content":"hi"}]}}""",
                                )
                                "prompt.submit" -> {
                                    submitted += text
                                    webSocket.send(
                                        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"live-a","payload":{"text":"ok"}}}""",
                                    )
                                    webSocket.send(
                                        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"live-a","payload":{}}}""",
                                    )
                                    val rewind = text.contains("truncate_before_row_id")
                                    val survivors = if (rewind) ""","survivor_user_row_ids":[]""" else ""
                                    webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":{"status":"streaming"$survivors}}""")
                                }
                            }
                        }
                    },
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            client.wsHello(origin, "coder")
            val rewindEvents = client.streamTurn(
                origin,
                "a",
                "coder",
                "edited",
                RewindSubmit(11, empty = true),
            ).toList()
            assertTrue(rewindEvents.any { it is ChatEvent.Rewound })
            assertTrue(submitted.last().contains("\"truncate_before_row_id\":11"))
            assertTrue(submitted.last().contains("\"confirm_truncate\":true"))
            assertTrue(submitted.last().contains("\"confirm_empty_truncate\":true"))
            client.streamTurn(origin, "a", "coder", "followup").toList()
            assertFalse(submitted.last().contains("truncate_before_row_id"))
            assertFalse(submitted.last().contains("confirm_truncate"))
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun gatedLoginMintsFreshTicketAndOmitsToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Set-Cookie", "hermes_session=abc; Path=/")
                    .setBody("""{"ok":true}"""),
            )
            server.enqueue(MockResponse().setBody("""{"ticket":"tick-1"}"""))
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":false,"heartbeat":false,"instance_id":"g1"}}}""",
                            )
                        }
                    },
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            client.passwordLogin(origin, "nyx", "secret")
            val login = server.takeRequest()
            assertEquals("/auth/password-login", login.path)
            val loginBody = login.body.readUtf8()
            assertTrue(loginBody.contains("\"username\":\"nyx\""))
            assertTrue(loginBody.contains("\"provider\":\"basic\""))
            client.wsHello(origin, "coder")
            val ticketReq = server.takeRequest()
            assertEquals("/api/auth/ws-ticket", ticketReq.path)
            assertTrue(ticketReq.getHeader("Cookie").orEmpty().contains("hermes_session"))
            val wsReq = server.takeRequest()
            assertTrue(wsReq.path.orEmpty().contains("ticket=tick-1"))
            assertFalse(wsReq.path.orEmpty().contains("token="))
            client.closeRpc()
            server.enqueue(MockResponse().setBody("""{"ticket":"tick-2"}"""))
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":false,"heartbeat":false,"instance_id":"g2"}}}""",
                            )
                        }
                    },
                ),
            )
            client.wsHello(origin, "coder")
            server.takeRequest()
            val ws2 = server.takeRequest()
            assertTrue(ws2.path.orEmpty().contains("ticket=tick-2"))
            assertFalse(ws2.path.orEmpty().contains("tick-1"))
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun deviceLaneTicketIsSubprotocolNotQuery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"ticket":"dt-1","capabilities":["device.snapshot"],"ttl_sec":30}""",
                ),
            )
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"type":"mobile.controller.hello","device_id":"dev_ab"}""")
                        }
                    },
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            val cred = DeviceCred("dev_ab", "coder", "secret-cred", origin)
            client.openDeviceLane(origin, cred)
            val register = server.takeRequest()
            assertEquals("/companion/device/register", register.path)
            val body = register.body.readUtf8()
            assertTrue(body.contains("\"device_id\":\"dev_ab\""))
            assertTrue(body.contains("\"credential\":\"secret-cred\""))
            val ws = server.takeRequest()
            assertEquals("/companion/device/ws", ws.path)
            assertFalse(ws.path.orEmpty().contains("ticket="))
            assertFalse(ws.path.orEmpty().contains("?"))
            val proto = ws.getHeader("Sec-WebSocket-Protocol").orEmpty()
            assertTrue(proto.contains("hermes-mobile-control-v1"))
            assertTrue(proto.contains("hermes-mobile-control-ticket.dt-1"))
            client.closeDeviceLane()
            server.enqueue(
                MockResponse().setBody(
                    """{"ticket":"dt-2","capabilities":["device.snapshot"],"ttl_sec":30}""",
                ),
            )
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"type":"mobile.controller.hello","device_id":"dev_ab"}""")
                        }
                    },
                ),
            )
            client.openDeviceLane(origin, cred)
            server.takeRequest()
            val ws2 = server.takeRequest()
            val proto2 = ws2.getHeader("Sec-WebSocket-Protocol").orEmpty()
            assertTrue(proto2.contains("dt-2"))
            assertFalse(proto2.contains("dt-1"))
            client.closeDeviceLane()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun offerPairThenPollUntilApproved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"pending","profile":"coder"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"pending","profile":"coder"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"status":"approved","device_id":"dev_ab","profile":"coder","credential":"cred-1"}""",
                ),
            )
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val offered = client.offerPair(origin, "K7M2QX", "coder")
            assertEquals("pending", offered.status)
            val offerReq = server.takeRequest()
            assertEquals("/companion/device/pair", offerReq.path)
            val offerBody = offerReq.body.readUtf8()
            assertTrue(offerBody.contains("\"code\":\"K7M2QX\""))
            assertTrue(offerBody.contains("\"profile\":\"coder\""))
            assertEquals("pending", client.pollPair(origin, "K7M2QX").status)
            val approved = client.pollPair(origin, "K7M2QX")
            assertTrue(approved.approved)
            assertEquals("dev_ab", approved.deviceId)
            assertEquals("cred-1", approved.credential)
            assertEquals("/companion/device/pair/K7M2QX", server.takeRequest().path)
            assertEquals("/companion/device/pair/K7M2QX", server.takeRequest().path)
        }
    }

    private fun MockWebServer.toOkHttp() = okhttp3.OkHttpClient.Builder()
        .build()
}
