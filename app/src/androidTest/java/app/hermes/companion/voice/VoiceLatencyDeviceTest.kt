package app.hermes.companion.voice

import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

/** Synthetic output only: no activity, recognizer, microphone or Hermes prompt. */
@RunWith(AndroidJUnit4::class)
class VoiceLatencyDeviceTest {
    @Test fun measurePlatformReadinessAndSelectedVoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val status = AtomicLong(-1)
        val startedAt = SystemClock.elapsedRealtime()
        lateinit var platform: TextToSpeech
        instrumentation.runOnMainSync {
            platform = TextToSpeech(instrumentation.targetContext) {
                status.set(it.toLong())
                ready.countDown()
            }
        }
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS.toLong(), status.get())
            val readyMs = SystemClock.elapsedRealtime() - startedAt
            instrumentation.runOnMainSync {
                val result = platform.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
                    platform.language = Locale.US
                Log.i("VoiceLatency", "platform_ready_ms=$readyMs engine=${platform.defaultEngine} voice=${platform.voice?.name} network_voice=${platform.voice?.isNetworkConnectionRequired}")
            }
        } finally { instrumentation.runOnMainSync { platform.shutdown() } }
    }

    @Test fun measureOutputReuseAcrossThreeCompletedTurns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstAudio = AtomicLong(-1)
        val turnStart = AtomicLong()
        val completion = AtomicReference(CountDownLatch(1))
        val error = AtomicReference("")
        lateinit var output: TextToSpeechEngine
        instrumentation.runOnMainSync {
            output = TextToSpeechEngine(instrumentation.targetContext,
                onStart = { firstAudio.compareAndSet(-1, SystemClock.elapsedRealtime() - turnStart.get()) },
                onAllCompleted = { completion.get().countDown() },
                onError = { error.set(it); completion.get().countDown() },
                audioAttributes = VoiceAudioSession.attributes)
        }
        try {
            for (turn in 1..3) {
                firstAudio.set(-1)
                completion.set(CountDownLatch(1))
                turnStart.set(SystemClock.elapsedRealtime())
                instrumentation.runOnMainSync {
                    // The conversation engine also stops output when re-entering listening.
                    output.stop()
                    output.feedToken("Synthetic voice timing test. ")
                    output.finishFeed()
                }
                assertTrue(completion.get().await(20, TimeUnit.SECONDS))
                assertEquals("", error.get())
                assertTrue(firstAudio.get() >= 0)
                Log.i("VoiceLatency", "reused_turn=$turn first_playback_callback_ms=${firstAudio.get()} complete_ms=${SystemClock.elapsedRealtime() - turnStart.get()}")
            }
        } finally { instrumentation.runOnMainSync { output.shutdown() } }
    }

    @Test fun measureSystemOutputWithAndWithoutAStreamingClauseBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (boundary in listOf(true, false, true, false)) {
            val started = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val firstAudio = AtomicLong(-1)
            val error = AtomicReference("")
            val createdAt = SystemClock.elapsedRealtime()
            lateinit var output: TextToSpeechEngine
            instrumentation.runOnMainSync {
                output = TextToSpeechEngine(instrumentation.targetContext,
                    onStart = {
                        firstAudio.compareAndSet(-1, SystemClock.elapsedRealtime() - createdAt)
                        started.countDown()
                    },
                    onAllCompleted = { completed.countDown() },
                    onError = { error.set(it); started.countDown(); completed.countDown() },
                    audioAttributes = VoiceAudioSession.attributes)
                output.feedToken(if (boundary) "Synthetic voice timing test. " else "Synthetic voice timing test")
            }
            try {
                if (!boundary) {
                    // A controlled model pause, not a measurement of the live model.
                    assertFalse("Unterminated text unexpectedly began playback", started.await(1500, TimeUnit.MILLISECONDS))
                }
                val finishAt = SystemClock.elapsedRealtime() - createdAt
                instrumentation.runOnMainSync { output.finishFeed() }
                assertTrue("System speech never started", started.await(20, TimeUnit.SECONDS))
                assertTrue("System speech did not finish", completed.await(20, TimeUnit.SECONDS))
                assertEquals("", error.get())
                assertTrue(firstAudio.get() >= 0)
                if (!boundary) assertTrue(firstAudio.get() >= finishAt)
                Log.i("VoiceLatency", "boundary=$boundary first_playback_callback_ms=${firstAudio.get()} finish_feed_ms=$finishAt complete_ms=${SystemClock.elapsedRealtime() - createdAt}")
            } finally {
                instrumentation.runOnMainSync { output.shutdown() }
            }
        }
    }
}
