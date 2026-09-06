package app.hermes.companion.data.remote

import app.hermes.companion.domain.RewindSubmit
import app.hermes.companion.model.BusFrame
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.DeviceCred
import app.hermes.companion.model.MessageRole
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
            assertTrue(path.contains("order=latest"))
            val events = client.streamTurn(origin, "sess-cod-1", "coder", "ping").toList()
            assertTrue(events.any { it is ChatEvent.AssistantDelta && it.text == "ok" })
            assertTrue(events.any { it is ChatEvent.Completed })
            val posted = server.takeRequest()
            assertEquals("/api/sessions/sess-cod-1/chat/stream?profile=coder", posted.path)
            assertTrue(posted.body.readUtf8().contains("\"profile\":\"coder\""))
        }
    }

    @Test
    fun createAndStreamCarryModelOverride() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"session":{"id":"n1","profile":"coder","title":"new thread"}}""",
                ),
            )
            val sse = Buffer().writeUtf8(
                "event: assistant.delta\ndata: {\"text\":\"ok\"}\n\nevent: run.completed\ndata: {}\n\n",
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val created = client.createSession(origin, "coder", model = "sonnet-4.6")
            assertEquals("n1", created.id)
            val createdBody = server.takeRequest().body.readUtf8()
            assertTrue(createdBody.contains("\"model\":\"sonnet-4.6\""))
            client.streamTurn(origin, "n1", "coder", "ping", model = "sonnet-4.6").toList()
            val posted = server.takeRequest().body.readUtf8()
            assertTrue(posted.contains("\"model\":\"sonnet-4.6\""))
        }
    }

    @Test
    fun deleteSessionSendsProfileAndSurfacesForbidden() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            client.deleteSession(origin, "sess-cod-1", "coder")
            val recorded = server.takeRequest()
            assertEquals("DELETE", recorded.method)
            assertEquals("/api/sessions/sess-cod-1?profile=coder", recorded.path)
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"profile_mismatch"}"""))
            val forbidden = runCatching { client.deleteSession(origin, "sess-ops-1", "coder") }.exceptionOrNull()
            assertTrue(forbidden is DashboardException)
            assertEquals("http_403", (forbidden as DashboardException).code)
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
            assertTrue(recorded.path.orEmpty().contains("order=latest"))
            assertEquals("rest", page.source)
            assertEquals("m1", page.messages.first().id)
            assertEquals("m40", page.messages.last().id)
            assertFalse(page.messages.any { it.id == "m41" })
        }
    }

    @Test
    fun parseMessagesPairsToolCallsAndResolvesNamesAndArgs() {
        val json = """
            {
              "messages": [
                {
                  "id": "1",
                  "role": "user",
                  "content": "check date"
                },
                {
                  "id": "2",
                  "role": "assistant",
                  "content": "",
                  "tool_calls": [
                    {
                      "id": "call_123",
                      "function": {
                        "name": "terminal",
                        "arguments": "{\"command\": \"date -u +%Y-%m-%d\"}"
                      }
                    },
                    {
                      "id": "call_456",
                      "function": {
                        "name": "skill_view",
                        "arguments": "{\"name\": \"hermes-agent\"}"
                      }
                    }
                  ]
                },
                {
                  "id": "3",
                  "role": "tool",
                  "tool_name": "terminal",
                  "tool_call_id": "call_123",
                  "content": "{\"output\": \"2026-08-04\", \"exit_code\": 0}"
                },
                {
                  "id": "4",
                  "role": "tool",
                  "tool_name": "skill_view",
                  "tool_call_id": "call_456",
                  "content": "# hermes-agent skill"
                }
              ]
            }
        """.trimIndent()
        val messages = parseMessages(json)
        assertEquals(3, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("check date", messages[0].text)

        assertEquals(MessageRole.TOOL, messages[1].role)
        assertEquals("terminal", messages[1].toolName)
        assertEquals("date -u +%Y-%m-%d", messages[1].toolDetail)
        assertEquals("2026-08-04", messages[1].text)

        assertEquals(MessageRole.TOOL, messages[2].role)
        assertEquals("skill_view", messages[2].toolName)
        assertEquals("hermes-agent", messages[2].toolDetail)
        assertEquals("# hermes-agent skill", messages[2].text)
    }

    @Test
    fun parseSseMapsHermesAndMockFrames() {
        val delta = DashboardClient.parseSse("assistant.delta", """{"text":"hi"}""")
        val tool = DashboardClient.parseSse("tool.started", """{"name":"terminal","detail":"ls"}""")
        val done = DashboardClient.parseSse("run.completed", "{}")
        assertEquals(ChatEvent.AssistantDelta("hi"), delta)
        assertEquals(ChatEvent.ToolStarted("terminal", "ls"), tool)
        assertEquals(
            ChatEvent.ToolStarted("skill_view", "axolotl"),
            DashboardClient.parseSse("tool.started", """{"name":"skill_view","context":"axolotl"}"""),
        )
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
        assertFalse(rows.single().ended)
    }

    @Test
    fun parseSessionsMarksEndedTelegramRows() {
        val rows = parseSessions(
            """{"sessions":[
              {"id":"tg-1","profile":"knight","title":"Nyx","end_reason":"agent_close","message_count":24},
              {"id":"live-1","profile":"knight","title":"open"}
            ]}""",
        )
        assertTrue(rows.first { it.id == "tg-1" }.ended)
        assertFalse(rows.first { it.id == "live-1" }.ended)
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
    fun parseHistoryToolRowsUseContextAndArgs() {
        val messages = parseMessages(
            """
            {"messages":[
              {"role":"tool","name":"terminal","context":"ls -la /tmp","args":{"command":"ls -la /tmp"}},
              {"role":"tool","name":"skill_view","args":{"name":"axolotl"}}
            ]}
            """.trimIndent(),
        )
        assertEquals("terminal", messages[0].toolName)
        assertEquals("ls -la /tmp", messages[0].toolDetail)
        assertEquals("ls -la /tmp", messages[0].text)
        assertEquals("skill_view", messages[1].toolName)
        assertEquals("axolotl", messages[1].toolDetail)
        assertEquals("axolotl", messages[1].text)
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

    @Test
    fun approvePairRemoteCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_id":"dev_test","status":"approved","profile":"knight"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"status":"approved","device_id":"dev_test","profile":"knight","credential":"cred-abc"}""",
                ),
            )
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val result = client.approvePair(origin, "XYZ789")
            assertTrue(result.approved)
            assertEquals("dev_test", result.deviceId)
            assertEquals("cred-abc", result.credential)
            val approveReq = server.takeRequest()
            assertEquals("/companion/device/pair/XYZ789/approve", approveReq.path)
            assertEquals("POST", approveReq.method)
            val pollReq = server.takeRequest()
            assertEquals("/companion/device/pair/XYZ789", pollReq.path)
        }
    }

    @Test
    fun authorizationHeaderContainsBearerToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            val client = DashboardClient(server.toOkHttp(), attachToken = true)
            client.sessionToken = "secret-token-12345"
            client.gated = false
            val origin = server.url("/").toString().trimEnd('/')

            client.probe(origin)
            val recorded = server.takeRequest()
            assertEquals("Bearer secret-token-12345", recorded.getHeader("Authorization"))
            assertEquals("secret-token-12345", recorded.getHeader("X-Hermes-Session-Token"))
        }
    }

    @Test
    fun hostMetricsAndGitAndTerminalRemoteCalls() = runBlocking {
        MockWebServer().use { server ->
            // Metrics response
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"ok":true,"metrics":{
                        "cpu":{"percent":18.5,"cores":8,"load_avg":[1.2,1.5,1.8]},
                        "memory":{"total_bytes":16000000000,"used_bytes":8000000000,"free_bytes":8000000000,"percent":50.0},
                        "disk":{"total_bytes":500000000000,"used_bytes":200000000000,"free_bytes":300000000000,"percent":40.0},
                        "system":{"platform":"Linux","release":"6.1","architecture":"x86_64","python_version":"3.11","uptime_seconds":3600},
                        "hermes":{"pid":4242,"status":"running"}
                    }}
                    """.trimIndent(),
                ),
            )
            // Git status response
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"ok":true,"branch":"feat/term","tracking":"origin/feat/term","ahead":1,"behind":0,
                     "staged_files":["file1.kt"],"modified_files":["file2.kt"],"untracked_files":["new.txt"]}
                    """.trimIndent(),
                ),
            )
            // Terminal exec response
            server.enqueue(
                MockResponse().setBody(
                    """{"ok":true,"exit_code":0,"stdout":"echo output\n","stderr":""}""",
                ),
            )

            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')

            val metrics = client.getHostMetrics(origin)
            assertEquals(18.5, metrics.cpu.percent, 0.01)
            assertEquals(4242, metrics.hermes.pid)
            assertEquals("running", metrics.hermes.status)

            val git = client.getGitStatus(origin)
            assertEquals("feat/term", git.branch)
            assertEquals(listOf("file1.kt"), git.stagedFiles)
            assertEquals(1, git.ahead)

            val term = client.executeTerminalCommand(origin, "echo 'hello'")
            assertTrue(term.ok)
            assertEquals(0, term.exitCode)
            assertEquals("echo output\n", term.stdout)
        }
    }

    @Test
    fun fiveOhTwoDashboardUnreachableMapsHostDown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(502).setBody("""{"error":"dashboard_unreachable"}"""),
            )
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val thrown = runCatching { client.listSessions(origin, "coder") }.exceptionOrNull()
            assertTrue(thrown is DashboardException)
            assertEquals("host_dashboard_down", (thrown as DashboardException).code)
            assertTrue(thrown.message.orEmpty().contains("start hermes dashboard"))
        }
    }

    @Test
    fun streamTurnPostsImageParts() = runBlocking {
        MockWebServer().use { server ->
            val sse = Buffer().writeUtf8(
                "event: assistant.delta\ndata: {\"text\":\"ok\"}\n\nevent: run.completed\ndata: {}\n\n",
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val events = client.streamTurn(
                origin,
                "sess-1",
                "coder",
                "see this",
                partsJson = ""","parts":[{"type":"image","url":"/companion/media/abc"}]""",
            ).toList()
            assertTrue(events.any { it is ChatEvent.Completed })
            val posted = server.takeRequest().body.readUtf8()
            assertTrue(posted.contains("\"parts\""))
            assertTrue(posted.contains("/companion/media/abc"))
            assertTrue(posted.contains("\"type\":\"image\""))
        }
    }

    @Test
    fun emptyRpcHistoryFallsBackToRest() = runBlocking {
        MockWebServer().use { server ->
            val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":false,"heartbeat":false,"instance_id":"h1"}}}""",
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = Json.parseToJsonElement(text).jsonObject
                            val id = obj["id"]!!.jsonPrimitive.content
                            val method = obj["method"]!!.jsonPrimitive.content
                            methods += method
                            when (method) {
                                "session.resume" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"live-empty","stored_session_id":"tg-empty"}}""",
                                )
                                "session.history" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"messages":[]}}""",
                                )
                            }
                        }
                    },
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"messages":[{"id":"m-rest","role":"user","content":"from-rest"}]}""",
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            client.wsHello(origin, "knight")
            server.takeRequest()
            val page = client.pageMessages(origin, "tg-empty", "knight")
            assertEquals("from-rest", page.messages.single().text)
            assertEquals("rest", page.source)
            assertTrue(methods.contains("session.resume"))
            assertTrue(methods.contains("session.history"))
            val rest = server.takeRequest()
            assertTrue(rest.path.orEmpty().contains("/api/sessions/tg-empty/messages"))
            assertTrue(rest.path.orEmpty().contains("order=latest"))
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun olderPageEmptyRpcFallsBackToRest() = runBlocking {
        MockWebServer().use { server ->
            val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":false,"heartbeat":false,"instance_id":"h3"}}}""",
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = Json.parseToJsonElement(text).jsonObject
                            val id = obj["id"]!!.jsonPrimitive.content
                            val method = obj["method"]!!.jsonPrimitive.content
                            methods += method
                            when (method) {
                                "session.history" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"messages":[]}}""",
                                )
                            }
                        }
                    },
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"messages":[
                      {"id":"m-old","role":"user","content":"older-rest"},
                      {"id":"m-newest","role":"assistant","content":"newest"}
                    ]}""",
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            client.wsHello(origin, "knight")
            server.takeRequest()
            val page = client.pageMessages(origin, "tg-older", "knight", beforeId = "m-newest")
            assertEquals("older-rest", page.messages.single().text)
            assertEquals("rest", page.source)
            assertTrue(methods.contains("session.history"))
            assertFalse(methods.contains("session.resume"))
            val rest = server.takeRequest()
            assertTrue(rest.path.orEmpty().contains("/api/sessions/tg-older/messages"))
            assertTrue(rest.path.orEmpty().contains("before=m-newest"))
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun endedSessionSkipsResumeAndFallsBackToRest() = runBlocking {
        MockWebServer().use { server ->
            val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":false,"heartbeat":false,"instance_id":"h2"}}}""",
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = Json.parseToJsonElement(text).jsonObject
                            val id = obj["id"]!!.jsonPrimitive.content
                            val method = obj["method"]!!.jsonPrimitive.content
                            methods += method
                            when (method) {
                                "session.resume" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"spawned","stored_session_id":"tg-ended"}}""",
                                )
                                "session.history" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"messages":[]}}""",
                                )
                            }
                        }
                    },
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"messages":[{"id":"m2","role":"assistant","content":"archived"}]}""",
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            client.wsHello(origin, "knight")
            server.takeRequest()
            val page = client.pageMessages(origin, "tg-ended", "knight", ended = true)
            assertEquals("archived", page.messages.single().text)
            assertEquals("rest", page.source)
            assertFalse(methods.contains("session.resume"))
            assertTrue(methods.contains("session.history"))
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }


    @Test
    fun emptyRpcSessionsFallsBackToRest() = runBlocking {
        MockWebServer().use { server ->
            val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":false,"heartbeat":false,"instance_id":"sess-empty"}}}""",
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = Json.parseToJsonElement(text).jsonObject
                            val id = obj["id"]!!.jsonPrimitive.content
                            val method = obj["method"]!!.jsonPrimitive.content
                            methods += method
                            when (method) {
                                "session.list" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"sessions":[]}}""",
                                )
                            }
                        }
                    },
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"sessions":[
                      {"id":"s-rest-1","profile":"default","title":"from-rest"},
                      {"id":"s-rest-2","profile":"default","title":"also-rest"}
                    ]}""",
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            client.wsHello(origin, "default")
            server.takeRequest()
            val sessions = client.listSessions(origin, "default")
            assertEquals(listOf("s-rest-1", "s-rest-2"), sessions.map { it.id })
            assertTrue(methods.contains("session.list"))
            val rest = server.takeRequest()
            assertTrue(rest.path.orEmpty().contains("/api/sessions"))
            assertTrue(rest.path.orEmpty().contains("profile=default"))
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun shortRpcSessionsPrefersRicherRest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":false,"heartbeat":false,"instance_id":"sess-short"}}}""",
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = Json.parseToJsonElement(text).jsonObject
                            val id = obj["id"]!!.jsonPrimitive.content
                            val method = obj["method"]!!.jsonPrimitive.content
                            if (method == "session.list") {
                                webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"sessions":[
                                      {"id":"only-rpc","profile":"coder","title":"short"}
                                    ]}}""",
                                )
                            }
                        }
                    },
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"sessions":[
                      {"id":"only-rpc","profile":"coder","title":"short"},
                      {"id":"extra-rest","profile":"coder","title":"richer"},
                      {"id":"third","profile":"coder","title":"also"}
                    ]}""",
                ),
            )
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            client.wsHello(origin, "coder")
            server.takeRequest()
            val sessions = client.listSessions(origin, "coder")
            assertEquals(3, sessions.size)
            assertTrue(sessions.any { it.id == "extra-rest" })
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun restHistory404IsEmptyPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
            val client = DashboardClient(server.toOkHttp())
            val origin = server.url("/").toString().trimEnd('/')
            val page = client.pageMessages(origin, "brand-new", "coder")
            assertTrue(page.messages.isEmpty())
            assertFalse(page.hasMore)
            assertEquals("rest", page.source)
            val req = server.takeRequest()
            assertTrue(req.path.orEmpty().contains("/api/sessions/brand-new/messages"))
        }
    }

    @Test
    fun emptyRpcHistoryRest404IsEmptySuccess() = runBlocking {
        MockWebServer().use { server ->
            val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":false,"heartbeat":false,"instance_id":"h-new"}}}""",
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = Json.parseToJsonElement(text).jsonObject
                            val id = obj["id"]!!.jsonPrimitive.content
                            val method = obj["method"]!!.jsonPrimitive.content
                            methods += method
                            when (method) {
                                "session.resume" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"live-new","stored_session_id":"brand-new"}}""",
                                )
                                "session.history" -> webSocket.send(
                                    """{"jsonrpc":"2.0","id":"$id","result":{"messages":[]}}""",
                                )
                            }
                        }
                    },
                ),
            )
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
            val http = server.toOkHttp()
            val client = DashboardClient(http)
            val origin = server.url("/").toString().trimEnd('/')
            client.wsHello(origin, "coder")
            server.takeRequest()
            val page = client.pageMessages(origin, "brand-new", "coder")
            assertTrue(page.messages.isEmpty())
            assertFalse(page.hasMore)
            assertEquals("rest", page.source)
            assertTrue(methods.contains("session.history"))
            val rest = server.takeRequest()
            assertTrue(rest.path.orEmpty().contains("/api/sessions/brand-new/messages"))
            client.closeRpc()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun MockWebServer.toOkHttp() = okhttp3.OkHttpClient.Builder()
        .build()
}


class CleartextPolicyTest {
    @Test
    fun cleartextToPublicHostIsRefusedBeforeNetwork() = runBlocking {
        val client = DashboardClient(okhttp3.OkHttpClient())
        val failure = runCatching { client.probe("http://hermes.example.invalid:9120") }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure!!.message.orEmpty().contains("cleartext_denied"))
    }

    @Test
    fun cleartextToLoopbackIsAllowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"auth_required":false}"""))
            val client = DashboardClient(okhttp3.OkHttpClient())
            val origin = server.url("/").toString().trimEnd('/')
            val status = client.probe(origin)
            assertFalse(status.authRequired)
        }
    }
}

class HostClientPoolTest {
    @Test
    fun tokensAndCookiesNeverCrossHosts() = runBlocking {
        MockWebServer().use { lab ->
            MockWebServer().use { hub ->
                lab.enqueue(MockResponse().setBody("""{"profiles":[]}"""))
                hub.enqueue(MockResponse().setBody("""{"profiles":[]}"""))
                val pool = HostClientPool { DashboardClient(okhttp3.OkHttpClient(), attachToken = true) }
                val labOrigin = lab.url("/").toString().trimEnd('/')
                val hubOrigin = hub.url("/").toString().trimEnd('/')
                val labClient = pool.forOrigin(labOrigin)
                val hubClient = pool.forOrigin(hubOrigin)
                assertTrue(labClient !== hubClient)
                assertTrue(pool.forOrigin("$labOrigin/") === labClient)
                labClient.sessionToken = "lab-only"
                labClient.listProfiles(labOrigin)
                hubClient.listProfiles(hubOrigin)
                val labReq = lab.takeRequest()
                val hubReq = hub.takeRequest()
                assertEquals("Bearer lab-only", labReq.getHeader("Authorization"))
                assertEquals(null, hubReq.getHeader("Authorization"))
                assertEquals(null, hubReq.getHeader("X-Hermes-Session-Token"))
                pool.evict(labOrigin)
                assertTrue(pool.existing(labOrigin) == null)
            }
        }
    }

    @Test
    fun poolKeyNormalises() {
        assertEquals("http://100.85.151.99:9120", HostClientPool.key("HTTP://100.85.151.99:9120/x"))
        assertEquals("https://h.ts.net", HostClientPool.key("https://h.ts.net:443/"))
        assertEquals("", HostClientPool.key(""))
    }
}
