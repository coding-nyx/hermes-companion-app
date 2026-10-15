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

                val mutedResult=input.result
                engine.setMicrophoneMuted(true)
                assertEquals(VoiceStreamState.MUTED,engine.state)
                mutedResult("must not submit while muted")
                assertEquals(1,prompts.size)
                engine.setMicrophoneMuted(false)
                assertEquals(VoiceStreamState.LISTENING,engine.state)
                input.result("second synthetic question")
                engine.onAssistantDelta("second synthetic answer")
                engine.onAssistantTurnCompleted()
                assertEquals(4, input.starts)
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

    @Test fun silencePausesUntilResumeAndSpokenPreviewStaysBrief() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val input = Input().apply { reportsReadiness = true }
            val audio = Audio()
            val readiness = mutableListOf<Boolean>()
            val prompts = mutableListOf<String>()
            val notices = mutableListOf<String>()
            lateinit var output: Output
            val engine = VoiceStreamEngine(instrumentation.targetContext, scope, prompts::add, {}, {},
                voiceInput = input,
                outputFactory = { BriefSpeechOutput(Output(it).also { output = it }) },
                audioSession = audio, onReadinessChange = readiness::add, onInputPaused = notices::add)
            try {
                engine.startStream()
                assertTrue(readiness.isEmpty())
                input.ready()
                assertEquals(listOf(true), readiness)
                val expiredResult = input.result
                val expiredError = input.error
                input.error(SpeechRecognizer.ERROR_NO_MATCH)
                assertEquals(VoiceStreamState.MUTED, engine.state)
                assertTrue(engine.isMicrophoneMuted)
                assertTrue(audio.held)
                assertEquals(1, notices.size)
                expiredError(SpeechRecognizer.ERROR_NO_MATCH)
                expiredResult("late duplicate")
                assertEquals(1, input.starts)
                assertTrue(prompts.isEmpty())
                engine.setMicrophoneMuted(false)
                assertEquals(2, input.starts)
                input.ready()
                input.result("synthetic request")
                val answer = "First sentence. Second sentence. Third sentence stays written."
                engine.onAssistantDelta(answer)
                engine.onAssistantFinal(answer)
                engine.onAssistantTurnCompleted()
                assertEquals("First sentence. Second sentence. The rest is in the chat.", output.spoken.joinToString(""))
                assertEquals(listOf("synthetic request"), prompts)
                assertEquals(3, input.starts)
                assertEquals(VoiceStreamState.LISTENING, engine.state)
            } finally { engine.shutdown(); scope.cancel() }
            assertFalse(audio.held)
        }
    }

    private class Input : SpeechInput {
        override var reportsReadiness = false
        var ready: () -> Unit = {}
        var starts = 0
        var result: (String) -> Unit = {}
        var error: (Int) -> Unit = {}
        var processing: () -> Unit = {}
        override fun startListening(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit,
            onErrorCode: ((Int) -> Unit)?, onStateChange: (Boolean) -> Unit, onActivity: () -> Unit, onProcessing: () -> Unit) {
            ready = { onStateChange(true) }
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
