package app.hermes.companion.data.remote

import app.hermes.companion.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeBotsContractTest {
    private val group = """{"room_id":"g","name":"Team","members":[{"member_id":"m","profile":"ash","handle":"ash","display_name":"Ash"}],"authority_gateway_id":"install","authority_epoch":1,"latest_seq":2}"""
    private val botMetadata = """{"profiles":[{"name":"ash","model":"m","provider":"p","description":"Research","ui_meta":{"hermes-bots":{"title":"Ash","sectionName":"Work","custom":true,"pinned":true}},"ui_meta_revisions":{"hermes-bots":4},"has_avatar":true,"canonical_session":{"id":"root","resolved_id":"tip","preview":"hello","last_active":1750000000}}],"bot_mode_protocol":true,"install_id":"install"}"""
    private val event = """{"room_id":"g","event_id":"user:hash","seq":2,"kind":"message.user","actor":{"kind":"user","id":"operator"},"payload":{"text":"hello","thread_id":"thread"},"created_at":1}"""

    @Test fun groupEventsRetainSpeakerIdentityAlongsideTheHostLabel() {
        val parsed = NativeBotsJson.event(Json.parseToJsonElement("""{"event_id":"e","seq":1,"kind":"message.member","actor":{"id":"seat","profile":"ash","display_name":"Hermes"},"payload":{"text":"Reply"}}""").jsonObject)
        assertEquals("seat", parsed.actorId); assertEquals("ash", parsed.actorProfile); assertEquals("Hermes", parsed.actor)
    }
    @Test fun groupRosterRetainsPeerScopeForSpeakerNames() {
        val remote = NativeBotsJson.group(Json.parseToJsonElement(group.replace("\"display_name\":\"Ash\"", "\"display_name\":\"Lab Ash\",\"target\":{\"kind\":\"peer\"}")).jsonObject)
        assertTrue(remote.members.single().peer)
    }

    @Test fun rosterRetainsCanonicalLineageMetadataRevisionAndIdentity() = runBlocking {
        fixture({ _, _ -> botMetadata }) { api, owner, _ ->
            val roster = api.roster(owner); val bot = roster.bots.single()
            assertEquals("install", roster.installationId); assertTrue(roster.protocol)
            assertEquals(BotOwner(owner.origin, "ash"), bot.owner); assertEquals("root", bot.canonicalId)
            assertEquals("tip", bot.resolvedId); assertEquals(4, bot.metadataRevision); assertTrue(bot.avatar)
            assertEquals(1_750_000_000_000, bot.updatedAt); assertEquals("Work", bot.section)
        }
    }
    @Test fun emptyRevisionMapMeansRevisionZeroRatherThanNoCasSupport() {
        val roster = NativeBotsJson.roster("https://example/a", Json.parseToJsonElement("""{"profiles":[{"name":"ash","ui_meta_revisions":{}}]}""").jsonObject)
        assertEquals(0, roster.bots.single().metadataRevision)
    }
    @Test fun canonicalLookupUsesHiddenExactTitleAndCompressionTip() = runBlocking {
        fixture({ method, _ -> if (method == "session.list") """{"sessions":[{"id":"root","resolved_id":"tip","title":"Bot Chat"}]}""" else error(method) }) { api, owner, calls ->
            val result = api.canonical(NativeBot(owner, "Ash", metadataJson = "{\"title\":\"Ash\"}"), "stable")
            assertEquals("tip", result.id)
            val params = calls.single()["params"]!!.jsonObject
            assertEquals("Bot Chat", params["title"]!!.jsonPrimitive.content)
            assertTrue(params["include_hidden"]!!.jsonPrimitive.boolean)
            assertEquals("ash", params["profile"]!!.jsonPrimitive.content)
        }
    }
    @Test fun knownCanonicalCannotBeForkedByAnEmptyOrFailedRegistry() = runBlocking {
        fixture({ _, _ -> "{\"sessions\":[]}" }) { api, owner, calls ->
            assertTrue(runCatching { api.canonical(NativeBot(owner, "Ash", canonicalId = "root", metadataJson = "{\"title\":\"Ash\"}"), "key") }.isFailure)
            assertEquals(listOf("session.list"), calls.map { it["method"]!!.jsonPrimitive.content })
        }
    }
    @Test fun newCanonicalIsHiddenMaterializedAndUsesTheOriginalRetryKey() = runBlocking {
        fixture({ method, _ -> when (method) {
            "session.list" -> "{\"sessions\":[]}"
            "session.create" -> "{\"session_id\":\"live\",\"stored_session_id\":\"stored\"}"
            "session.title" -> "{\"title\":\"Bot Chat\"}"
            else -> error(method)
        } }) { api, owner, calls ->
            assertEquals("stored", api.canonical(NativeBot(owner, "Ash", metadataJson = "{\"title\":\"Ash\"}"), "stable").id)
            val create = calls[1]["params"]!!.jsonObject
            assertTrue(create["hidden"]!!.jsonPrimitive.boolean); assertTrue(create["follow_profile_config"]!!.jsonPrimitive.boolean)
            assertEquals("stable", create["idempotency_key"]!!.jsonPrimitive.content)
            assertEquals("live", calls[2]["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content)
        }
    }
    @Test fun explicitLegacySchemaRejectionCanRetryWithoutTheUnsupportedCreationKey() = runBlocking {
        fixture({ method, fields -> when (method) {
            "session.list" -> "{\"sessions\":[]}"
            "session.create" -> if ("idempotency_key" in fields) throw DashboardException("rpc_4000", "invalid params for session.create: idempotency_key: Extra inputs are not permitted") else "{\"session_id\":\"live\",\"stored_session_id\":\"stored\"}"
            "session.title" -> "{}"
            else -> error(method)
        } }) { api, owner, calls ->
            assertEquals("stored", api.canonical(NativeBot(owner, "Ash", metadataJson = "{\"title\":\"Ash\"}"), "stable").id)
            val creates = calls.filter { it["method"]!!.jsonPrimitive.content == "session.create" }
            assertEquals(2, creates.size)
            assertEquals(creates[0]["params"]!!.jsonObject - "idempotency_key", creates[1]["params"]!!.jsonObject)
        }
    }
    @Test fun ambiguousCreationFailureNeverDropsTheOriginalIdempotencyKeyOrRetries() = runBlocking {
        fixture({ method, _ -> if (method == "session.list") "{\"sessions\":[]}" else throw DashboardException("rpc_-32000", "response lost") }) { api, owner, calls ->
            assertTrue(runCatching { api.canonical(NativeBot(owner, "Ash", metadataJson = "{\"title\":\"Ash\"}"), "stable") }.isFailure)
            assertEquals(1, calls.count { it["method"]!!.jsonPrimitive.content == "session.create" })
        }
    }
    @Test fun editsPreserveOtherPresentationFieldsAndSendCasRevision() = runBlocking {
        fixture({ method, _ -> when (method) { "profiles.list" -> botMetadata; "profiles.configure" -> """{"ok":true,"applied":{"ui_meta":true,"description":true}}"""; else -> error(method) } }) { api, owner, calls ->
            val bot = api.roster(owner).bots.single()
            api.configure(bot, BotEdit("ash", "New title", "New role"))
            val params = calls.last()["params"]!!.jsonObject
            val meta = params["ui_meta"]!!.jsonObject["hermes-bots"]!!.jsonObject
            assertTrue(meta["custom"]!!.jsonPrimitive.boolean); assertTrue(meta["pinned"]!!.jsonPrimitive.boolean)
            assertEquals("New title", meta["title"]!!.jsonPrimitive.content)
            assertEquals(4, params["ui_meta_expected_revisions"]!!.jsonObject["hermes-bots"]!!.jsonPrimitive.int)
        }
    }
    @Test fun metadataConflictsAreNotReportedAsSuccessfulSaves() = runBlocking {
        fixture({ _, _ -> """{"ok":true,"applied":{"ui_meta":false,"ui_meta_conflicts":{"hermes-bots":{"actual":5}}}}""" }) { api, owner, _ ->
            assertTrue(runCatching { api.configure(NativeBot(owner, "Ash", metadataRevision = 4), BotEdit("ash", "changed")) }.isFailure)
        }
    }
    @Test fun sectionAssignmentsReuseTheBackendsSectionIdentityAndCanBeRemoved() = runBlocking {
        fixture({ method, _ -> when (method) {
            "profiles.list" -> """{"profiles":[{"name":"other","ui_meta":{"hermes-bots":{"sectionId":"shared-section","sectionName":"Work"}}}]}"""
            "profiles.configure" -> """{"ok":true,"applied":{"ui_meta":true,"description":true}}"""
            else -> error(method)
        } }) { api, owner, calls ->
            val bot = NativeBot(owner, "Ash", metadataRevision = 0)
            api.configure(bot, BotEdit("ash", "Ash", section = "Work"))
            val metadata = calls.last()["params"]!!.jsonObject["ui_meta"]!!.jsonObject["hermes-bots"]!!.jsonObject
            assertEquals("shared-section", metadata["sectionId"]!!.jsonPrimitive.content)
            api.configure(bot.copy(metadataJson = metadata.toString(), section = "Work"), BotEdit("ash", "Ash"))
            val cleared = calls.last()["params"]!!.jsonObject["ui_meta"]!!.jsonObject["hermes-bots"]!!.jsonObject
            assertFalse("sectionId" in cleared); assertFalse("sectionName" in cleared)
        }
    }
    @Test fun groupSendCarriesStableEventThreadAndCapturedProfileOnly() = runBlocking {
        fixture({ method, _ -> when (method) { "groups.send" -> """{"accepted":true,"event":$event}"""; else -> error(method) } }) { api, owner, calls ->
            val op = NativeBotOperation("stable-event", owner, "send", "g", "hello", "thread")
            api.send(op); api.send(op)
            assertEquals(calls[0]["params"], calls[1]["params"])
            val params = calls[0]["params"]!!.jsonObject
            assertEquals(setOf("profile", "room_id", "event_id", "payload"), params.keys)
            assertEquals("stable-event", params["event_id"]!!.jsonPrimitive.content)
            assertEquals("thread", params["payload"]!!.jsonObject["thread_id"]!!.jsonPrimitive.content)
        }
    }
    @Test fun approvalsSendAllAttemptCoordinatesAndOnlySupportedChoices() = runBlocking {
        fixture({ _, _ -> "{\"approved\":true}" }) { api, owner, calls ->
            val action = NativeGroupAction("approval", "member", "task", 3, "request")
            api.approve(owner, "g", action, "once")
            val p = calls.single()["params"]!!.jsonObject
            assertEquals("member", p["member_id"]!!.jsonPrimitive.content); assertEquals("task", p["task_id"]!!.jsonPrimitive.content)
            assertEquals(3, p["execution_generation"]!!.jsonPrimitive.int); assertEquals("request", p["request_id"]!!.jsonPrimitive.content)
            assertTrue(runCatching { api.approve(owner, "g", action, "always") }.isFailure)
            assertEquals(1, calls.size)
        }
    }
    @Test fun driverRunningIsNotTheSameAsMembersWorkingAndIncompleteApprovalsAreHidden() {
        val snapshot = NativeBotsJson.snapshot(Json.parseToJsonElement("""{"room":$group,"driver_status":{"running":true,"working":false,"pending_actions":[{"kind":"approval","member_id":"m","task_id":"t","request_id":"r"},{"kind":"approval","member_id":"m","task_id":"t","execution_generation":2,"request_id":"r"}]}}""").jsonObject)
        assertFalse(snapshot.working); assertEquals(1, snapshot.actions.size)
    }
    @Test fun unsupportedNativeMethodsDoNotTryCompanionRoutes() = runBlocking {
        fixture({ _, _ -> throw DashboardException("rpc_-32601", "not supported") }) { api, owner, calls ->
            val error = runCatching { api.capabilities(owner) }.exceptionOrNull() as DashboardException
            assertEquals("rpc_-32601", error.code); assertEquals(1, calls.size)
        }
    }
    @Test fun profileSpecificBotSocketsLeaveOperatorSocketOpen() = runBlocking {
        val paths = CopyOnWriteArrayList<String>(); val calls = CopyOnWriteArrayList<JsonObject>()
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path.orEmpty()
                    return MockResponse().withWebSocketUpgrade(listener(calls) { method, _ -> if (method == "profiles.list") botMetadata else "{}" })
                }
            }
            server.start(); val origin = server.url("/").toString().trimEnd('/')
            val client = DashboardClient(); val pool = HostClientPool { client }; val api = DashboardNativeBotsApi(pool)
            try {
                client.wsHello(origin, "default"); api.roster(BotOwner(origin, "ash")); api.roster(BotOwner(origin, "research")); client.ping()
                assertEquals(3, paths.size); assertTrue(paths.any { it.contains("profile=default") }); assertTrue(paths.any { it.contains("profile=ash") })
                assertTrue(paths.any { it.contains("profile=research") })
            } finally { client.closeRpc(); client.closeBotSockets() }
        }
    }

    private fun listener(calls: MutableList<JsonObject>, reply: (String, JsonObject) -> String) = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":false,"instance_id":"bots-test"}}}""") }
        override fun onMessage(ws: WebSocket, text: String) {
            val req = Json.parseToJsonElement(text).jsonObject; calls += req
            val id = req["id"]!!.jsonPrimitive.content
            try { val result = reply(req["method"]!!.jsonPrimitive.content, req["params"]!!.jsonObject); ws.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""") }
            catch (e: DashboardException) { ws.send("""{"jsonrpc":"2.0","id":"$id","error":{"code":${e.code.removePrefix("rpc_").toIntOrNull() ?: -32601},"message":${JsonPrimitive(e.message ?: "unsupported")}}}""") }
        }
    }
    private suspend fun fixture(reply: (String, JsonObject) -> String, check: suspend (NativeBotsApi, BotOwner, List<JsonObject>) -> Unit) {
        val calls = CopyOnWriteArrayList<JsonObject>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(listener(calls, reply))); server.start()
            val origin = server.url("/").toString().trimEnd('/'); val pool = HostClientPool()
            try { check(DashboardNativeBotsApi(pool), BotOwner(origin, "ash"), calls) } finally { pool.evict(origin) }
        }
    }
}
