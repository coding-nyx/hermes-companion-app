package app.hermes.hands

import android.app.Application
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HandsHttpTransferBoundsTest {
    private fun server(command: (JSONObject) -> JSONObject) = HandsHttpServer(
        { if (it == "test-token") ClientTokens.Client("synthetic", "Test") else null }, { ok() }, { _, body -> command(body) }, 0)
    private fun request(host: HandsHttpServer, body: String, token: String = "test-token", declaredLength: Int? = null): String =
        Socket("127.0.0.1", host.localPort).use { socket ->
            socket.soTimeout = 5_000
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(("POST /v1/commands HTTP/1.1\r\nHost: localhost:${host.localPort}\r\n" +
                "Authorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: ${declaredLength ?: bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().write(bytes)
            socket.getInputStream().bufferedReader().readText()
        }
    @Test fun aFull192KiBFileChunkReachesTheAuthenticatedDispatcherWithoutTruncation() {
        val bytes = ByteArray(192 * 1024) { (it * 31).toByte() }
        val expected = sha(bytes)
        val calls = AtomicInteger()
        server { body ->
            calls.incrementAndGet()
            assertEquals("device.files", body.getString("action"))
            val received = Base64.getDecoder().decode(body.getJSONObject("arguments").getString("data_base64"))
            assertArrayEquals(bytes, received)
            ok(JSONObject().put("length", received.size).put("sha256", sha(received)))
        }.use { host ->
            host.start()
            val body = JSONObject().put("action", "device.files").put("arguments", JSONObject().put("action", "write_chunk")
                .put("transfer_id", "synthetic-only").put("data_base64", Base64.getEncoder().encodeToString(bytes))).toString()
            assertTrue(body.length > 32_768)
            val reply = request(host, body)
            assertTrue(reply.startsWith("HTTP/1.1 200"))
            assertEquals(expected, JSONObject(reply.substringAfter("\r\n\r\n")).getJSONObject("result").getString("sha256"))
            assertEquals(1, calls.get())
            // Authentication can close before draining an untrusted large body.
            assertTrue(request(host, "{\"action\":\"device.files\"}", token = "wrong").startsWith("HTTP/1.1 401"))
            assertEquals(1, calls.get())
        }
    }
    @Test fun exactByteLimitIsAcceptedAndOneByteOverNeverDispatches() {
        val calls = AtomicInteger()
        server { calls.incrementAndGet(); ok() }.use { host ->
            host.start()
            val prefix = "{\"action\":\"device.files\",\"arguments\":{\"padding\":\""
            val suffix = "\"}}"
            val boundary = prefix + "a".repeat(393_216 - prefix.length - suffix.length) + suffix
            assertEquals(393_216, boundary.toByteArray().size)
            assertTrue(request(host, boundary).startsWith("HTTP/1.1 200"))
            assertTrue(request(host, "", declaredLength = 393_217).startsWith("HTTP/1.1 400"))
            assertEquals(1, calls.get())
        }
    }
    @Test fun longOperationsHaveBoundedActionSpecificResponseDeadlinesAndRemoveThemAfterward() {
        listOf("device.files" to 125L, "device.voice.speak" to 125L, "device.status" to 30L).forEach { (action, expected) ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caller = Executors.newSingleThreadExecutor()
            try {
                server { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); ok() }.use { host ->
                    host.start()
                    val body = JSONObject().put("action", action).put("arguments", JSONObject().put("wait_ms", 999_999_999)).toString()
                    val pending = caller.submit<String> { request(host, body) }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val scheduler = HandsHttpServer::class.java.getDeclaredField("deadlines").apply { isAccessible = true }.get(host) as ScheduledThreadPoolExecutor
                    val remaining = (scheduler.queue.single() as java.util.concurrent.Delayed).getDelay(TimeUnit.SECONDS)
                    assertTrue("$action deadline was $remaining, expected capped $expected seconds", remaining in (expected - 2)..expected)
                    release.countDown()
                    assertTrue(pending.get(5, TimeUnit.SECONDS).startsWith("HTTP/1.1 200"))
                    // The response may reach the peer immediately before the handler's finally.
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                    while (scheduler.queue.isNotEmpty() && System.nanoTime() < until) Thread.yield()
                    assertTrue(scheduler.queue.isEmpty())
                }
            } finally { release.countDown(); caller.shutdownNow() }
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
