package app.hermes.companion.voice

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.remote.*
import app.hermes.companion.model.SynthesizedSpeech
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString

/** Silent hardware playback and native transport only. No mic, operator speech or chat prompt. */
@RunWith(AndroidJUnit4::class)
class StreamingSpeechDeviceTest {
    @Test fun isolatedNativeContractStreamsThroughTheCoordinatorAndHardwareDrain() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, value: String) {
                    if (value.contains("\"done\"")) {
                        ws.send("{\"type\":\"start\",\"sample_rate\":24000,\"channels\":1}")
                        repeat(5) { ws.send(ByteArray(2400).toByteString()) }
                        ws.send("{\"type\":\"end\"}"); ws.close(1000, null)
                    }
                }
            }))
            val origin = server.url("/").toString().trimEnd('/')
            val client = DashboardClient().apply { sessionToken = "synthetic-loopback-token" }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val done = CountDownLatch(1)
            var error = ""; var starts = 0; var completed = 0
            val fallback = object : SpeechPlayer {
                override fun play(audio: SynthesizedSpeech, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit) =
                    error("Contract fixture must not fall back")
                override fun stop() = Unit
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var output: StreamingHostSpeechOutput
            val began = System.nanoTime()
            instrumentation.runOnMainSync {
                output = StreamingHostSpeechOutput(scope, NativeSpeechTransport(client, origin, "synthetic-profile"),
                    AndroidPcmSpeechPlayer(scope), fallback,
                    SpeechCallbacks({ starts++ }, { completed++; done.countDown() }, { error = it; done.countDown() }))
                output.feedToken("Scripted speech playback."); output.finishFeed()
            }
            try {
                assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(error.isBlank())
                assertEquals(1, starts); assertEquals(1, completed)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) >= 150)
                assertEquals(1, server.requestCount)
                assertEquals(NativeSpeechSupport.SUPPORTED, client.speechStreamSupport(origin, "synthetic-profile"))
            } finally { instrumentation.runOnMainSync { output.shutdown(); scope.cancel() } }
        }
    }
    @Test fun silentPcmUsesTheRealAudioTrackAndWaitsForDrain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        val previousMode = manager.mode
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = AndroidPcmSpeechPlayer(scope)
        try {
            withTimeout(10_000) {
                val elapsed = measureTimeMillis {
                    player.begin(SpeechPcmFormat(24_000))
                    player.enqueue(ByteArray(24_000 * 2 / 4)) // 250ms silence, never microphone audio.
                    player.finishAndDrain()
                }
                assertTrue("Completion must wait for the device playback head", elapsed >= 150)
            }
        } finally { player.stop(); scope.cancel() }
        assertEquals(previousMode, manager.mode)
    }

    @Test fun cancellingRealPcmPlaybackReleasesTheTrackAndAllowsAnotherStream() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = AndroidPcmSpeechPlayer(scope)
        try {
            player.begin(SpeechPcmFormat(24_000))
            val producer = async(Dispatchers.IO) { runCatching { player.enqueue(ByteArray(24_000 * 2 * 4)) } }
            delay(50); player.stop()
            withTimeout(3_000) { producer.await() }
            player.begin(SpeechPcmFormat(24_000))
            player.enqueue(ByteArray(960)); withTimeout(3_000) { player.finishAndDrain() }
        } finally { player.stop(); scope.cancel() }
    }

    @Test fun nativeHostStreamDrainsThroughTheCoordinatorAndRealAudioTrackWithoutFallback() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Live native PCM acceptance remains pending: lab Edge synthesis returns upstream 403.",
            args.getString("validateNativeProvider") == "true")
        val origin = requireNotNull(args.getString("nativeOrigin"))
        val profile = requireNotNull(args.getString("profile"))
        val client = DashboardClient().apply { useLoopback() }
        assertNotNull("Native authentication failed", client.adoptLoopbackToken(origin))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val track = AndroidPcmSpeechPlayer(scope)
        val receivedPcm = AtomicBoolean()
        val started = AtomicBoolean()
        val done = CountDownLatch(1)
        var error = ""
        var failureCode = ""
        val silentPlayer = object : PcmSpeechPlayer {
            override suspend fun begin(format: SpeechPcmFormat, onStart: () -> Unit) { track.begin(format, onStart) }
            override suspend fun enqueue(bytes: ByteArray) {
                receivedPcm.set(bytes.isNotEmpty())
                // Exercise real timing/format/drain without making the operator listen to a test.
                bytes.fill(0); track.enqueue(bytes)
            }
            override suspend fun finishAndDrain() { track.finishAndDrain() }
            override fun stop() = track.stop()
        }
        val events = CopyOnWriteArrayList<String>()
        val native = NativeSpeechTransport(client, origin, profile)
        val transport = object : StreamingSpeechTransport by native {
            override suspend fun openSpeechStream(): NativeSpeechStream {
                val stream = native.openSpeechStream()
                return object : NativeSpeechStream by stream {
                    override val events = stream.events.onEach { event ->
                        events += when (event) {
                            is SpeechStreamEvent.Start -> "start:${event.format.sampleRate}"
                            is SpeechStreamEvent.Pcm -> "pcm:${event.bytes.size}"
                            else -> event::class.simpleName.orEmpty()
                        }
                    }
                }
            }
        }
        val fallback = object : SpeechPlayer {
            override fun play(audio: SynthesizedSpeech, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit) =
                error("This device check requires direct native PCM")
            override fun stop() = Unit
        }
        lateinit var output: StreamingHostSpeechOutput
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            output = StreamingHostSpeechOutput(scope, transport, silentPlayer, fallback,
                SpeechCallbacks({ started.set(true) }, { done.countDown() }, { error = it; done.countDown() }),
                onFailureCode = { failureCode = it })
            output.feedToken("Native streaming transport test. One two three.")
            output.finishFeed()
        }
        try {
            assertTrue("Native streaming playback timed out", done.await(60, TimeUnit.SECONDS))
            assertTrue("Native streaming playback failed ($failureCode, $events): $error", error.isBlank())
            assertTrue(started.get()); assertTrue(receivedPcm.get())
        } finally { instrumentation.runOnMainSync { output.shutdown(); scope.cancel() } }
    }
}
