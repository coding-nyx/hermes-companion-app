package app.hermes.companion.voice

import android.app.KeyguardManager
import android.media.AudioManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.MainActivity
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.model.SynthesizedSpeech
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** No recognition task or chat prompt. Capture cancellation never uploads audio. */
@RunWith(AndroidJUnit4::class)
class HostSpeechDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private fun prepare(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            it.setShowWhenLocked(false)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val keyguard = i.targetContext.getSystemService(KeyguardManager::class.java)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (keyguard.isKeyguardLocked && System.nanoTime() < deadline) Thread.sleep(100)
        assertFalse("Unlock the device for foreground audio tests", keyguard.isKeyguardLocked)
    }
    @Test fun nativeHostSpeechPlaysThroughTheNewMemoryPlayerAndReleasesAudio() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val origin = requireNotNull(args.getString("nativeOrigin"))
        val profile = requireNotNull(args.getString("profile"))
        val client = DashboardClient().apply { useLoopback() }
        assertNotNull(client.adoptLoopbackToken(origin))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            prepare(scenario)
            val audio = VoiceAudioSession(i.targetContext)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val done = CountDownLatch(1)
            var started = false
            var error = ""
            lateinit var output: HostSpeechOutput
            i.runOnMainSync {
                assertTrue("Another call owns audio", audio.acquire { error = "focus lost"; done.countDown() })
                assertTrue(audio.useSpeaker(true))
                output = HostSpeechOutput(scope, NativeSpeechTransport(client, origin, profile), AndroidSpeechPlayer(),
                    SpeechCallbacks({ started = true }, { done.countDown() }, { error = it; done.countDown() }))
                output.feedToken("Host speech playback test. One two three.")
                output.finishFeed()
            }
            try {
                assertTrue("Host playback timed out", done.await(45, TimeUnit.SECONDS))
                assertTrue("Host playback failed: $error", error.isBlank())
                assertTrue("Player never started", started)
            } finally { i.runOnMainSync { output.shutdown(); audio.release(); scope.cancel() } }
            assertEquals(AudioManager.MODE_NORMAL, i.targetContext.getSystemService(AudioManager::class.java).mode)
        }
    }
    @Test fun cancellingRealMicrophoneCaptureNeverUploadsOrReturnsALateResult() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            prepare(scenario)
            val ready = CountDownLatch(1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val uploads = java.util.concurrent.atomic.AtomicInteger()
            var error = ""
            val input = HostSpeechInput(scope, AndroidSpeechCapture(i.targetContext), object : SpeechTransport {
                override suspend fun transcribe(wav: ByteArray): String { uploads.incrementAndGet(); return "unexpected" }
                override suspend fun synthesize(text: String): SynthesizedSpeech = error("unused")
            })
            i.runOnMainSync { input.startListening(onResult = { error = "unexpected result" },
                onError = { error = it; ready.countDown() }, onStateChange = { if (it) ready.countDown() }) }
            try {
                assertTrue("Recorder never became ready", ready.await(8, TimeUnit.SECONDS))
                assertTrue("Recorder failed: $error", error.isBlank())
                i.runOnMainSync { input.stopListening() }
                Thread.sleep(250)
                assertEquals(0, uploads.get())
                assertTrue(error.isBlank())
            } finally { i.runOnMainSync { input.stopListening(); scope.cancel() } }
        }
    }
}
