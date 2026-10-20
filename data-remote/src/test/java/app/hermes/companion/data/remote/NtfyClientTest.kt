package app.hermes.companion.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NtfyClientTest {
    private fun client() = NtfyClient(retryDelayMs = 10, maxRetryDelayMs = 40)
    private fun message(id: String) =
        """{"id":"$id","event":"message","message":"{\"type\":\"run.completed\",\"session_id\":\"s\",\"profile\":\"coder\"}"}"""
    private fun sse(vararg events: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "data: $it\n\n" })

    @Test fun reconnectsAfterEofWithCursorAndSuppressesReplayedIds() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(message("first")))
            server.enqueue(sse(
                """{"id":"keepalive","event":"keepalive"}""",
                message("first"),
                message("second"),
            ))
            val events = withTimeout(5_000) {
                client().events(server.url("/topic/sse?priority=high").toString()).take(2).toList()
            }
            assertEquals(listOf(message("first"), message("second")), events)
            assertEquals("/topic/sse?priority=high", server.takeRequest().path)
            val reconnect = server.takeRequest().requestUrl!!
            assertEquals("first", reconnect.queryParameter("since"))
            assertEquals("high", reconnect.queryParameter("priority"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun retriesTemporaryHttpErrorsWithoutReplayingTheWholeCache() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(sse(message("recovered")))
            assertEquals(message("recovered"), withTimeout(5_000) {
                client().events(server.url("/topic/sse").toString()).first()
            })
            repeat(3) { assertNull(server.takeRequest().requestUrl!!.queryParameter("since")) }
        }
    }

    @Test fun accessDenialsAndInvalidTopicsStopWithoutRetrying() = runBlocking {
        for (status in listOf(400, 401, 403, 404)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status))
                val result = runCatching {
                    withTimeout(5_000) { client().events(server.url("/topic/sse").toString()).first() }
                }
                assertEquals(status, (result.exceptionOrNull() as NtfyHttpException).status)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun cursorNeverLeaksFromOneTopicCollectionToAnother() = runBlocking {
        MockWebServer().use { server ->
            val client = client()
            for (topic in listOf("one", "two")) {
                server.enqueue(sse(message(topic)))
                withTimeout(5_000) { client.events(server.url("/$topic/sse").toString()).first() }
                val request = server.takeRequest()
                assertEquals("/$topic/sse", request.path)
                assertNull(request.requestUrl!!.queryParameter("since"))
            }
        }
    }

    @Test fun multilineSseDataIsOneEventAndIncompleteEofDataIsNotDelivered() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"type\":\"run.completed\",\n"))
            server.enqueue(MockResponse().setBody(
                "data: {\"type\":\"run.completed\",\ndata: \"session_id\":\"s\",\"profile\":\"coder\"}\n\n",
            ))
            val received = withTimeout(5_000) { client().events(server.url("/topic/sse").toString()).first() }
            assertEquals("{\"type\":\"run.completed\",\n\"session_id\":\"s\",\"profile\":\"coder\"}", received)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun consumerFailureIsNotRetriedAsANetworkFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(message("first")))
            val expected = IOException("consumer failed")
            val result = runCatching {
                withTimeout(5_000) {
                    client().events(server.url("/topic/sse").toString()).collect { throw expected }
                }
            }
            assertOriginalFailure(expected, result.exceptionOrNull())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun certificateAndCleartextPolicyFailuresAreNotRetried() = runBlocking {
        for (failure in listOf(
            javax.net.ssl.SSLHandshakeException("untrusted certificate"),
            IOException(app.hermes.companion.domain.OriginPolicy.CLEARTEXT_DENIED),
        )) {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val http = okhttp3.OkHttpClient.Builder().addInterceptor {
                calls.incrementAndGet()
                throw failure
            }.build()
            val result = runCatching {
                withTimeout(5_000) {
                    NtfyClient(http, retryDelayMs = 10).events("https://ntfy.example/topic/sse").first()
                }
            }
            assertOriginalFailure(failure, result.exceptionOrNull())
            assertEquals(1, calls.get())
        }
    }

    @Test fun cancellationStopsTheSubscriptionAndItsBackoff() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val job = launch {
                NtfyClient(retryDelayMs = 1_000).events(server.url("/topic/sse").toString()).collect()
            }
            val request = async(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
            assertNotNull(request.await())
            job.cancelAndJoin()
            delay(60)
            assertEquals(1, server.requestCount)
        }
    }

    private fun assertOriginalFailure(expected: IOException, actual: Throwable?) {
        assertNotNull(actual)
        assertEquals(expected.javaClass, actual!!.javaClass)
        assertEquals(expected.message, actual.message)
        // Coroutine stack-trace recovery may copy an exception while retaining the original
        // as its cause. Verify that original failure, not object identity of the recovered copy.
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }
}
