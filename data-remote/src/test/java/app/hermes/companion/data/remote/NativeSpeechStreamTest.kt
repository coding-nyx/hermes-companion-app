package app.hermes.companion.data.remote

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class NativeSpeechStreamTest {
    @Test fun streamUpgradeRetainsTheHostCleartextBoundary() = runBlocking {
        val stream = DashboardClient().apply { sessionToken = "synthetic-token" }
            .openSpeechStream("http://untrusted.example", "knight")
        try {
            assertTrue(withTimeout(5_000) { stream.events.toList().single() } is SpeechStreamEvent.Failed)
        } finally { stream.stop() }
    }
    @Test fun loopbackUsesItsAcceptedBootstrapTokenWithoutMintingAGatedTicket() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { ws.send(start) }
                override fun onMessage(ws: WebSocket, value: String) {
                    if (value.contains("\"done\"")) { ws.send("{\"type\":\"end\"}"); ws.close(1000, null) }
                }
            }))
            val client = DashboardClient(OkHttpClient(), true).apply { sessionToken = "bootstrap-token" }
            val origin = server.url("/proxy").toString()
            val stream = client.openSpeechStream(origin, "knight")
            try {
                assertTrue(stream.finish())
                assertEquals(listOf(SpeechStreamEvent.Ready, SpeechStreamEvent.Start(SpeechPcmFormat(24_000)), SpeechStreamEvent.End),
                    withTimeout(5_000) { stream.events.toList() })
                val request = server.takeRequest()
                assertEquals("/proxy/api/audio/speak-stream", request.requestUrl!!.encodedPath)
                assertEquals("bootstrap-token", request.requestUrl!!.queryParameter("token"))
                assertNull(request.requestUrl!!.queryParameter("ticket")); assertEquals(1, server.requestCount)
                assertEquals(NativeSpeechSupport.SUPPORTED, client.speechStreamSupport(origin, "knight"))
                assertEquals(NativeSpeechSupport.UNKNOWN, client.speechStreamSupport(origin, "other-profile"))
                client.useLoopback()
                assertEquals(NativeSpeechSupport.UNKNOWN, client.speechStreamSupport(origin, "knight"))
            } finally { stream.stop() }
        }
    }

    @Test fun unauthenticatedLoopbackFailsWithoutOpeningOrTryingAnotherAuthMode() = runBlocking {
        MockWebServer().use { server ->
            val error = runCatching { DashboardClient().openSpeechStream(server.url("/").toString().trimEnd('/'), "knight") }.exceptionOrNull()
            assertEquals("speech_stream_auth", (error as DashboardException).code)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun streamUsesScopedTicketProxyPathAndIncrementalTextWithoutReplaying() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ticket":"synthetic-ticket"}""")
                .setHeader("Set-Cookie", "session=synthetic-cookie; Path=/"))
            val incoming = CopyOnWriteArrayList<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send("""{"type":"start","sample_rate":24000,"channels":1}""")
                }
                override fun onMessage(ws: WebSocket, value: String) {
                    incoming += value
                    if (value.contains("\"done\"")) {
                        ws.send(byteArrayOf(1, 2, 3, 4).toByteString())
                        ws.send("""{"type":"end"}"""); ws.close(1000, null)
                    }
                }
            }))
            val client = DashboardClient(OkHttpClient(), true).apply { gated = true }
            val stream = client.openSpeechStream(server.url("/proxy").toString(), "my profile")
            try {
                assertTrue(stream.sendText("First. ")); assertTrue(stream.sendText("Second.")); assertTrue(stream.finish())
                assertFalse(stream.sendText("too late")); assertFalse(stream.finish())
                val events = withTimeout(5_000) { stream.events.toList() }
                assertEquals(SpeechStreamEvent.Ready, events[0])
                assertEquals(SpeechPcmFormat(24_000), (events[1] as SpeechStreamEvent.Start).format)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), (events[2] as SpeechStreamEvent.Pcm).bytes)
                assertEquals(SpeechStreamEvent.End, events[3])
                val ticket = server.takeRequest(); val upgrade = server.takeRequest()
                assertEquals("/proxy/api/auth/ws-ticket", ticket.requestUrl!!.encodedPath)
                assertNull(ticket.getHeader("Authorization"))
                assertEquals("/proxy/api/audio/speak-stream", upgrade.requestUrl!!.encodedPath)
                assertEquals("my profile", upgrade.requestUrl!!.queryParameter("profile"))
                assertEquals("synthetic-ticket", upgrade.requestUrl!!.queryParameter("ticket"))
                assertEquals("session=synthetic-cookie", upgrade.getHeader("Cookie"))
                assertEquals(listOf("{\"text\":\"First. \"}", "{\"text\":\"Second.\"}", "{\"done\":true}"), incoming.toList())
            } finally { stream.stop() }
        }
    }

    @Test fun onlyExplicitUnsupportedUpgradeResponsesPermitCompatibilityFallback() = runBlocking {
        for (code in listOf(404, 405, 501, 401, 403, 500, 302)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"ticket":"test"}"""))
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/redirect")))
                val client = DashboardClient().apply { gated = true }
                val origin = server.url("/").toString().trimEnd('/')
                val stream = client.openSpeechStream(origin, "knight")
                try {
                    val event = withTimeout(5_000) { stream.events.toList().single() }
                    if (code in listOf(404, 405, 501)) assertEquals(SpeechStreamEvent.Unsupported, event)
                    else assertTrue("Code $code must remain an error", event is SpeechStreamEvent.Failed)
                    assertEquals(if (code in listOf(404, 405, 501)) NativeSpeechSupport.UNSUPPORTED else NativeSpeechSupport.UNKNOWN,
                        client.speechStreamSupport(origin, "knight"))
                    assertEquals(NativeSpeechSupport.UNKNOWN, client.speechStreamSupport(origin, "other-profile"))
                    assertEquals(2, server.requestCount)
                } finally { stream.stop() }
            }
        }
    }

    @Test fun ticketFailureCannotBeMisclassifiedAsMissingSpeechSupport() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val error = runCatching { DashboardClient().apply { gated = true }.openSpeechStream(server.url("/").toString().trimEnd('/'), "knight") }.exceptionOrNull()
            assertEquals("http_404", (error as DashboardException).code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun ticketRedirectIsRejectedBeforeCredentialsCanFollowIt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
            val client = DashboardClient(OkHttpClient(), true).apply { gated = true }
            val error = runCatching { client.openSpeechStream(server.url("/").toString().trimEnd('/'), "knight") }.exceptionOrNull()
            assertEquals("http_302", (error as DashboardException).code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun malformedOrderingFormatsAndOversizedFramesAreRejected() {
        fun invalid(action: (SpeechStreamDecoder) -> Unit) {
            assertEquals("speech_stream_invalid", (runCatching { action(SpeechStreamDecoder()) }.exceptionOrNull() as DashboardException).code)
        }
        invalid { it.binary(byteArrayOf(1, 2).toByteString()) }
        invalid { it.text("not json") }
        invalid { it.text("""{"type":"start","sample_rate":24000,"channels":2}""") }
        invalid { it.text("""{"type":"start","sample_rate":0,"channels":1}""") }
        invalid { it.text("""{"type":"end"}""") }
        invalid { it.text("""{"type":"unknown"}""") }
        invalid { it.text(start); it.text(start) }
        invalid { it.text(start); it.binary(byteArrayOf(1).toByteString()) }
        invalid { it.text(start); it.binary(ByteArray(SpeechStreamDecoder.MAX_PCM_FRAME + 2).toByteString()) }
        invalid { it.text(start); it.binary(byteArrayOf(1, 2).toByteString()); it.text("""{"type":"fallback"}""") }
        invalid { it.text(start); it.text("""{"type":"end"}"""); it.binary(byteArrayOf(1, 2).toByteString()) }
    }

    @Test fun cancellationUnblocksABackpressuredReaderWithoutCollectingIt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ticket":"test"}"""))
            val started = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send(start); repeat(20) { ws.send(ByteArray(48_000).toByteString()) }; started.complete(Unit)
                }
            }))
            val stream = DashboardClient().apply { gated = true }.openSpeechStream(server.url("/").toString().trimEnd('/'), "knight")
            withTimeout(5_000) { started.await() }
            stream.stop()
            assertFalse(stream.sendText("after cancel"))
        }
    }

    companion object { private const val start = "{\"type\":\"start\",\"sample_rate\":24000,\"channels\":1}" }
}
