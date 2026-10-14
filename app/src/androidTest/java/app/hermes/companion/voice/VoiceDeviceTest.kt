package app.hermes.companion.voice

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.MainActivity
import app.hermes.companion.VoiceStreamState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real device audio; no Hermes prompts, telephone calls, or recorded audio are sent. */
@RunWith(AndroidJUnit4::class)
class VoiceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun prepareUnlocked(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            // Normal foreground presentation lets the operator see and dismiss keyguard.
            it.setShowWhenLocked(false)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (keyguard.isKeyguardLocked && System.nanoTime() < deadline) Thread.sleep(100)
        assertFalse("Unlock the test phone with its normal authentication", keyguard.isKeyguardLocked)
    }

    @Test fun speakerPlaybackAndConversationTeardownUseRealDeviceAudio() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            prepareUnlocked(scenario)
            val manager = context.getSystemService(AudioManager::class.java)
            val initialMode = manager.mode
            assertEquals("Finish any other audio call before testing", AudioManager.MODE_NORMAL, initialMode)
            val input = SyntheticInput()
            val phases = CopyOnWriteArrayList<VoiceStreamState>()
            val errors = CopyOnWriteArrayList<String>()
            val listening = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            lateinit var engine: VoiceStreamEngine
            main {
                engine = VoiceStreamEngine(context, scope,
                    onSendPrompt = { engine.onAssistantDelta("Hermes audio test. One two three."); engine.onAssistantTurnCompleted() },
                    onInterrupt = {}, voiceInput = input,
                    onStateChange = { state ->
                        phases += state
                        if (state == VoiceStreamState.LISTENING) {
                            if (input.starts > 0) completed.countDown() else listening.countDown()
                        }
                    }, onError = { errors += it; completed.countDown() }, inactivityTimeoutMs = 30_000)
                engine.startStream()
            }
            try {
                assertTrue("Voice failed to start: $errors", listening.await(8, TimeUnit.SECONDS))
                assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
                val routeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                while (manager.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && System.nanoTime() < routeDeadline) Thread.sleep(50)
                assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, manager.communicationDevice?.type)
                main { input.result("synthetic test turn") }
                assertTrue("Speech playback timed out: $errors", completed.await(25, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertTrue("Speech failed: $errors", errors.isEmpty())
                assertTrue(phases.contains(VoiceStreamState.THINKING))
                assertTrue(phases.contains(VoiceStreamState.SPEAKING))
                assertEquals(2, input.starts)
            } finally {
                main { engine.shutdown(); scope.cancel() }
            }
            assertEquals(VoiceStreamState.IDLE, engine.state)
            assertEquals(initialMode, manager.mode)
        }
    }

    @Test fun emptyFeedBeforeTtsInitializationStillCompletes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            prepareUnlocked(scenario)
            val done = CountDownLatch(1)
            val errors = CopyOnWriteArrayList<String>()
            lateinit var output: TextToSpeechEngine
            main {
                output = TextToSpeechEngine(context, onAllCompleted = { done.countDown() }, onError = { errors += it; done.countDown() })
                output.finishFeed()
            }
            try {
                assertTrue("Empty speech feed never completed", done.await(10, TimeUnit.SECONDS))
                assertTrue("TTS unavailable: $errors", errors.isEmpty())
            } finally { main { output.shutdown() } }
        }
    }

    @Test fun microphoneCanCaptureAndReleasePcmOnTheDevice() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            prepareUnlocked(scenario)
            val rate = 16_000
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            assertTrue("PCM recording format unavailable", size > 0)
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
            try {
                assertEquals(AudioRecord.STATE_INITIALIZED, recorder.state)
                recorder.startRecording()
                assertEquals(AudioRecord.RECORDSTATE_RECORDING, recorder.recordingState)
                val buffer = ShortArray(rate)
                val n = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                assertTrue("Microphone read failed: $n", n > 0)
                assertFalse("Android silenced the microphone client", recorder.activeRecordingConfiguration?.isClientSilenced == true)
                // Samples are discarded. Silence is valid; recognition accuracy is a separate check.
                recorder.stop()
                assertEquals(AudioRecord.RECORDSTATE_STOPPED, recorder.recordingState)
            } finally { recorder.release() }
        }
    }

    private class SyntheticInput : SpeechInput {
        @Volatile var starts = 0
        var result: (String) -> Unit = {}
        override fun startListening(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit,
            onErrorCode: ((Int) -> Unit)?, onStateChange: (Boolean) -> Unit, onActivity: () -> Unit, onProcessing: () -> Unit) { starts++; result = onResult }
        override fun stopListening() = Unit
    }
}
