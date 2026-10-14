package app.hermes.companion.voice

import android.app.Application
import android.speech.SpeechRecognizer
import app.hermes.companion.VoiceStreamState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class VoiceStreamEngineTest {
    @Test fun aDuplicateTranscriptFromThePreviousTurnCannotSubmitANewPrompt() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            val oldResult = f.input.result
            oldResult("first question"); runCurrent()
            f.engine.onAssistantTurnCompleted(); runCurrent()
            assertEquals(2, f.input.starts)
            oldResult("first question"); runCurrent()
            assertEquals(listOf("first question"), f.prompts)
            assertEquals(VoiceStreamState.LISTENING, f.engine.state)
            f.input.result("second question"); runCurrent()
            assertEquals(listOf("first question", "second question"), f.prompts)
        }
    }

    @Test fun aLateRecognizerErrorCannotStopTheNextListeningTurn() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            val oldError = f.input.error
            f.input.result("question"); runCurrent()
            f.engine.onAssistantTurnCompleted(); runCurrent()
            oldError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); runCurrent()
            assertEquals(VoiceStreamState.LISTENING, f.engine.state)
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun lateProcessingCannotArmATranscriptionDeadlineForTheNextTurn() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            val oldProcessing = f.input.processing
            f.input.result("question"); runCurrent()
            f.engine.onAssistantTurnCompleted(); runCurrent()
            oldProcessing(); runCurrent()
            assertEquals(VoiceStreamState.LISTENING, f.engine.state)
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun aResultAfterRecognizerFailureCannotSubmitDuringTheRetryDelay() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            val oldResult = f.input.result
            f.input.error(SpeechRecognizer.ERROR_NETWORK); runCurrent()
            oldResult("late result"); runCurrent()
            assertTrue(f.prompts.isEmpty())
            advanceTimeBy(500); runCurrent()
            assertEquals(2, f.input.starts)
            f.input.result("retried question"); runCurrent()
            assertEquals(listOf("retried question"), f.prompts)
        }
    }

    @Test fun transcriptionOutlivesTheListeningDeadlineAndStillSubmitsOnce() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            f.input.processing(); runCurrent(); advanceTimeBy(1_100); runCurrent()
            assertEquals(VoiceStreamState.TRANSCRIBING, f.engine.state)
            f.input.result("recognized"); runCurrent()
            assertEquals(listOf("recognized"), f.prompts)
            assertEquals(VoiceStreamState.THINKING, f.engine.state)
        }
    }
    @Test fun aLostTranscriptionCallbackHasABoundedDeadline() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent(); f.input.processing(); runCurrent()
            advanceTimeBy(2_100); runCurrent()
            assertEquals(VoiceStreamState.IDLE, f.engine.state)
            assertEquals(listOf("Speech transcription timed out"), f.errors)
            assertEquals(1, f.audio.releases)
        }
    }
    @Test fun rapidToggleCannotReopenTheMicrophoneAfterStop() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); f.engine.stopStream(); runCurrent()
            assertEquals(0, f.input.starts)
            assertFalse(f.engine.isActive)
        }
    }

    @Test fun oneVoiceTurnOwnsSpeechAndReopensOnlyAfterPlayback() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            f.input.result("hello"); runCurrent()
            assertEquals(VoiceStreamState.THINKING, f.engine.state)
            assertEquals(listOf("hello"), f.prompts)
            f.engine.onAssistantDelta("answer."); runCurrent()
            assertEquals(VoiceStreamState.SPEAKING, f.engine.state)
            f.engine.onAssistantTurnCompleted(); runCurrent()
            assertEquals(VoiceStreamState.LISTENING, f.engine.state)
            assertEquals(2, f.input.starts)
            f.engine.stopStream(); runCurrent()
            assertEquals(1, f.audio.releases)
            assertEquals(1, f.outputs.single().shutdowns)
        }
    }

    @Test fun ordinaryTypedRepliesAreNotSpokenDuringMicrophoneListening() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            f.engine.onAssistantDelta("unrelated typed reply"); f.engine.onAssistantTurnCompleted(); runCurrent()
            assertTrue(f.outputs.single().text.isEmpty())
            assertEquals(1, f.input.starts)
            assertEquals(VoiceStreamState.LISTENING, f.engine.state)
        }
    }

    @Test fun stalePlaybackAndRetryCallbacksCannotAffectANewConversation() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            val old = f.outputs.single().callbacks
            f.input.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); runCurrent()
            f.engine.stopStream(); runCurrent()
            f.engine.startStream(); runCurrent()
            val starts = f.input.starts
            old.onStart(); old.onCompleted(); old.onError("old error")
            advanceTimeBy(600); runCurrent()
            assertEquals(starts, f.input.starts)
            assertTrue(f.errors.isEmpty())
            assertEquals(VoiceStreamState.LISTENING, f.engine.state)
        }
    }

    @Test fun recognizerRestartsDoNotPostponeTheSilenceDeadline() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            advanceTimeBy(400); f.input.error(SpeechRecognizer.ERROR_NO_MATCH); runCurrent()
            advanceTimeBy(400); f.input.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT); runCurrent()
            advanceTimeBy(250); runCurrent()
            assertEquals(VoiceStreamState.IDLE, f.engine.state)
            assertEquals(1, f.audio.releases)
        }
    }

    @Test fun focusLossEndsVoiceAndNeverAutomaticallyRestartsCapture() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            f.input.result("hello"); runCurrent()
            f.engine.onAssistantDelta("speech."); runCurrent()
            f.audio.lost(); runCurrent()
            advanceTimeBy(2_000); runCurrent()
            assertEquals(VoiceStreamState.IDLE, f.engine.state)
            assertEquals(1, f.input.starts)
            assertEquals(1, f.audio.releases)
        }
    }

    @Test fun permissionFailureAndAnExistingCallNeverLeaveAudioOwned() = runTest {
        Fixture(this).use { f ->
            f.audio.granted = false
            f.engine.startStream(); runCurrent()
            assertEquals(0, f.input.starts)
            assertEquals(VoiceStreamState.IDLE, f.engine.state)
            f.audio.granted = true
            f.engine.startStream(); runCurrent()
            f.input.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); runCurrent()
            assertEquals(VoiceStreamState.IDLE, f.engine.state)
            assertEquals(1, f.audio.releases)
            assertEquals(2, f.errors.size)
        }
    }

    @Test fun anEmptyReplyAndASpeechFailureBothReachATerminalState() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            f.input.result("hello"); runCurrent()
            f.engine.onAssistantTurnCompleted(); runCurrent()
            assertEquals(VoiceStreamState.LISTENING, f.engine.state)
            f.outputs.single().callbacks.onError("playback failed"); runCurrent()
            assertEquals(VoiceStreamState.IDLE, f.engine.state)
            assertEquals(listOf("playback failed"), f.errors)
        }
    }

    @Test fun shutdownReleasesResourcesEvenWhenTheOwningScopeIsCancelled() = runTest {
        Fixture(this).use { f ->
            f.engine.startStream(); runCurrent()
            f.scope.cancel(); f.engine.shutdown()
            assertFalse(f.engine.isActive)
            assertEquals(1, f.audio.releases)
            assertEquals(1, f.outputs.single().shutdowns)
        }
    }

    private class Fixture(test: TestScope) : AutoCloseable {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val input = Input()
        val audio = Audio()
        val outputs = mutableListOf<Output>()
        val prompts = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val engine = VoiceStreamEngine(RuntimeEnvironment.getApplication(), scope, prompts::add, {}, {}, errors::add,
            inactivityTimeoutMs = 1_000, voiceInput = input, outputFactory = { Output(it).also(outputs::add) },
            audioSession = audio, mainDispatcher = dispatcher, nowMs = { test.testScheduler.currentTime }, transcriptionTimeoutMs = 2_000)
        override fun close() { engine.shutdown(); scope.cancel() }
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
        val text = mutableListOf<String>()
        var shutdowns = 0
        override fun feedToken(delta: String) { text += delta; callbacks.onStart() }
        override fun finishFeed() = callbacks.onCompleted()
        override fun stop() = Unit
        override fun shutdown() { shutdowns++ }
    }
    private class Audio : VoiceAudioSessionControl {
        var granted = true
        var held = false
        var releases = 0
        var lost: () -> Unit = {}
        override fun acquire(onLost: () -> Unit): Boolean { lost = onLost; held = granted; return granted }
        override fun release() { if (held) { releases++; held = false } }
        override fun useSpeaker(enabled: Boolean) = held
    }
}
