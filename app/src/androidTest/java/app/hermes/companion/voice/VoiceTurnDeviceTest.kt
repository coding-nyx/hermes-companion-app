package app.hermes.companion.voice

import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.VoiceStreamState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production engine on Android with scripted speech ports. No microphone, playback or host prompt. */
@RunWith(AndroidJUnit4::class)
class VoiceTurnDeviceTest {
    @Test fun twoTurnsRejectLateRecognitionAndReleaseTheConversationOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val input = Input()
            val audio = Audio()
            val prompts = mutableListOf<String>()
            val errors = mutableListOf<String>()
            lateinit var output: Output
            val engine = VoiceStreamEngine(instrumentation.targetContext, scope, prompts::add, {}, {}, errors::add,
                voiceInput = input, outputFactory = { Output(it).also { output = it } }, audioSession = audio)
            try {
                engine.startStream()
                assertEquals(VoiceStreamState.LISTENING, engine.state)
                val firstResult = input.result
                val firstError = input.error
                val firstProcessing = input.processing
                firstResult("first synthetic question")
                engine.onAssistantDelta("first synthetic answer")
                assertEquals(VoiceStreamState.SPEAKING, engine.state)
                engine.onAssistantTurnCompleted()
                assertEquals(VoiceStreamState.LISTENING, engine.state)

                firstResult("duplicate question")
                firstError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                firstProcessing()
                assertEquals(VoiceStreamState.LISTENING, engine.state)
                assertEquals(listOf("first synthetic question"), prompts)
                assertTrue(errors.isEmpty())

                input.result("second synthetic question")
                engine.onAssistantDelta("second synthetic answer")
                engine.onAssistantTurnCompleted()
                assertEquals(3, input.starts)
                assertEquals(listOf("first synthetic question", "second synthetic question"), prompts)
                assertEquals(listOf("first synthetic answer", "second synthetic answer"), output.spoken)

                val stoppedResult = input.result
                engine.stopStream()
                stoppedResult("after stop")
                assertEquals(VoiceStreamState.IDLE, engine.state)
                assertEquals(2, prompts.size)
                assertFalse(audio.held)
                assertEquals(1, audio.releases)
            } finally { engine.shutdown(); scope.cancel() }
        }
    }

    private class Input : SpeechInput {
        var starts = 0
        var result: (String) -> Unit = {}
        var error: (Int) -> Unit = {}
        var processing: () -> Unit = {}
        override fun startListening(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit,
            onErrorCode: ((Int) -> Unit)?, onStateChange: (Boolean) -> Unit, onActivity: () -> Unit, onProcessing: () -> Unit) {
            starts++; result = onResult; error = { onErrorCode?.invoke(it) }; processing = onProcessing
        }
        override fun stopListening() = Unit
    }

    private class Output(val callbacks: SpeechCallbacks) : SpeechOutput {
        val spoken = mutableListOf<String>()
        override fun feedToken(delta: String) { spoken += delta; callbacks.onStart() }
        override fun finishFeed() = callbacks.onCompleted()
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private class Audio : VoiceAudioSessionControl {
        var held = false
        var releases = 0
        override fun acquire(onLost: () -> Unit): Boolean { held = true; return true }
        override fun release() { if (held) { held = false; releases++ } }
        override fun useSpeaker(enabled: Boolean) = held
    }
}
