package app.hermes.desk.providers

import app.hermes.companion.model.*
import app.hermes.desk.feature.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeProviderTest {
    private class Vault : ProviderVault {
        val documents = mutableMapOf<String, String>()
        override fun hostId() = "urn:uuid:test"
        override fun accounts() = emptyList<ChatGptAccount>()
        override fun saveAccount(account: ChatGptAccount) = Unit
        override fun clearTokens(key: String) = Unit
        override fun readDocument(key: String) = documents[key]?.let(::JSONObject)
        override fun writeDocument(key: String, value: JSONObject) { documents[key] = value.toString() }
    }
    private fun modelList() = MockResponse().setBody("""{"models":[{"visibility":"list","slug":"fixture-model","display_name":"Fixture"}]}""")
    private fun stream(vararg events: JSONObject) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })
    private fun event(type: String) = JSONObject().put("type", type)
    private fun complete() = event("response.completed").put("response", JSONObject().put("status", "completed"))
    private fun backend(server: MockWebServer, vault: Vault): ChatGptBackend = ChatGptBackend(vault, ChatGptAuth(vault),
        transport = ResponsesTransport(endpoint = server.url("/v1/responses").toString()), origin = server.url("/").toString().trimEnd('/'),
        profileSource = { listOf(ProfileRef("account-a", "Account A", "")) }, tokenSource = { "fixture-dashboard-credential" })

    @Test fun sendsFullHistoryAndEphemeralImageWithoutImportingOtherAccount() = runBlocking {
        MockWebServer().use { server ->
            val vault = Vault(); val backend = backend(server, vault)
            server.enqueue(modelList()); val session = backend.createThread("account-a"); server.takeRequest()
            val binding = DeskBinding(server.url("/").toString().trimEnd('/'), "account-a", session.id)
            server.enqueue(stream(event("response.created"), event("response.output_text.delta").put("delta", "Hello."), complete()))
            backend.stageImage(binding, byteArrayOf(17, 18, 19))
            val events = backend.turn(binding, "First thought").toList()
            assertTrue(events.last() == ChatEvent.Completed)
            val request = server.takeRequest()
            assertEquals("Bearer fixture-dashboard-credential", request.getHeader("Authorization"))
            val first = JSONObject(request.body.readUtf8())
            assertFalse(first.getBoolean("store")); assertTrue(first.getBoolean("stream")); assertFalse(first.has("previous_response_id"))
            assertTrue(first.toString().contains("data:image/jpeg;base64,ERIT"))
            assertFalse(vault.documents.values.any { it.contains("ERIT") || it.contains("fixture-dashboard-credential") })
            server.enqueue(stream(event("response.created"), event("response.output_text.delta").put("delta", "Again."), complete()))
            backend.turn(binding, "Follow up").toList()
            val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("input")
            assertEquals(3, second.length()); assertEquals("First thought", second.getJSONObject(0).getString("content"))
            assertEquals("Hello.", second.getJSONObject(1).getString("content"))
            try { backend.history(binding.copy(profile = "account-b")); fail("Cross-account history was accessible") } catch (_: IllegalStateException) {}
        }
    }
    @Test fun partialFailureIsNotReportedAsSuccessfulAndHistoryRecordsUncertainty() = runBlocking {
        MockWebServer().use { server ->
            val vault = Vault(); val backend = backend(server, vault)
            server.enqueue(modelList()); val session = backend.createThread("account-a"); server.takeRequest()
            val binding = DeskBinding(server.url("/").toString().trimEnd('/'), "account-a", session.id)
            server.enqueue(stream(event("response.created"), event("response.output_text.delta").put("delta", "Partial"), event("response.failed")))
            val seen = mutableListOf<ChatEvent>()
            try { backend.turn(binding, "Hello").toList(seen); fail("Failure was accepted") } catch (_: IllegalStateException) {}
            assertFalse(seen.any { it is ChatEvent.Final || it == ChatEvent.Completed })
            assertTrue(backend.history(binding).last().text.contains("completion was not confirmed"))
        }
    }
    @Test fun eofWithoutCompletedCannotBecomeSuccess() = runBlocking {
        MockWebServer().use { server ->
            val vault = Vault(); val backend = backend(server, vault)
            server.enqueue(modelList()); val session = backend.createThread("account-a"); server.takeRequest()
            val binding = DeskBinding(server.url("/").toString().trimEnd('/'), "account-a", session.id)
            server.enqueue(stream(event("response.created"), event("response.output_text.delta").put("delta", "Partial")))
            try { backend.turn(binding, "Hello").toList(); fail("EOF was accepted") } catch (_: IllegalStateException) {}
            assertTrue(backend.history(binding).last().text.contains("not confirmed"))
        }
    }
    @Test fun cancellingBlockedResponseClosesTransport() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val flow = ResponsesTransport(endpoint = server.url("/v1/responses").toString()).events(JSONObject(), "fixture")
            val job = launch(Dispatchers.IO) { flow.toList() }
            withContext(Dispatchers.IO) { server.takeRequest() }
            withTimeout(2000) { job.cancelAndJoin() }
        }
    }
    @Test fun refusesOversizedHistoryInsteadOfSilentlyDroppingContext() {
        val vault = Vault(); val backend = ChatGptBackend(vault, ChatGptAuth(vault))
        val thread = JSONObject().put("model", "fixture").put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "a".repeat(200_001))))
        try { backend.request(thread, "Follow up", null); fail("History was silently truncated") } catch (_: IllegalArgumentException) {}
    }
}
