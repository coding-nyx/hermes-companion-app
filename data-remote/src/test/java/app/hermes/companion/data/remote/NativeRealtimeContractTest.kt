package app.hermes.companion.data.remote

import app.hermes.companion.model.NativeLiveEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class NativeRealtimeContractTest {
    private val sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    @Test fun exchangePreservesProxyProfileAndSdpWithoutProviderKeys() = runBlocking {
        MockWebServer().use { server ->
            val answer = buildJsonObject { put("ok", true); put("session", buildJsonObject { put("id", "live1") });
                put("transport", buildJsonObject { put("type", "webrtc"); put("sdp", sdp) }) }
            server.enqueue(MockResponse().setBody(answer.toString()))
            val api = DashboardClient()
            val result = api.createRealtimeSession(server.url("/hermes").toString().trimEnd('/'), "knight", sdp)
            assertEquals("live1", result.sessionId); assertEquals(sdp, result.sdp)
            val request = server.takeRequest()
            assertEquals("/hermes/api/audio/voice-live/session?profile=knight", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("sdp", "history"), body.keys); assertEquals(sdp, body["sdp"]!!.jsonPrimitive.content)
        }
    }
    @Test fun providerOrAuthenticationFailureNeverReplaysCreation() = runBlocking {
        for (status in listOf(401, 403, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            assertNotNull(runCatching { DashboardClient().createRealtimeSession(server.url("/").toString().trimEnd('/'), "ash", sdp) }.exceptionOrNull())
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun invalidTransportIsRejectedAndMalformedOfferNeverLeavesTheDevice() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"session":{"id":"live1"},"transport":{"type":"websocket","sdp":"v=0"}}"""))
            assertEquals("realtime_invalid", (runCatching { DashboardClient().createRealtimeSession(server.url("/").toString().trimEnd('/'), "ash", sdp) }.exceptionOrNull() as DashboardException).code)
            assertTrue(runCatching { DashboardClient().createRealtimeSession(server.url("/").toString(), "ash", "bad") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun publicDelegationEventNeverInventsTheMissingTranscript() {
        val event = NativeLiveProtocol.decode("""{"type":"session.delegation.created","delegation":{"id":"d1","target":"client"}}""")
        assertEquals(NativeLiveEvent.Delegation("d1"), event)
        assertNull(NativeLiveProtocol.decode("""{"type":"session.delegation.created","delegation":{"id":"d2","target":"server"}}"""))
        assertEquals(NativeLiveEvent.Caption("user", "hi"), NativeLiveProtocol.decode("""{"type":"session.input_transcript.delta","delta":"hi"}"""))
    }
    @Test fun resultChunksPreserveUnicodeAndBindTheExactDelegation() {
        val text = "😀héllo ".repeat(300)
        val messages = NativeLiveProtocol.append(text, "d1")
        val chunks = messages.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(text.trim(), chunks.joinToString("") { it["content"]!!.jsonPrimitive.content })
        assertTrue(chunks.all { it["content"]!!.jsonPrimitive.content.toByteArray().size <= 500 })
        assertTrue(chunks.all { it["type"]!!.jsonPrimitive.content == "session.commentary.append" && it["delegation_id"]!!.jsonPrimitive.content == "d1" })
        assertTrue(runCatching { NativeLiveProtocol.decode("x".repeat(NativeLiveProtocol.MAX_FRAME_BYTES + 1)) }.exceptionOrNull() is IllegalArgumentException)
    }
}
