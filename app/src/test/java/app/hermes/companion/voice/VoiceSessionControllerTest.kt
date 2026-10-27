package app.hermes.companion.voice

import android.app.Application
import app.hermes.companion.*
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class VoiceSessionControllerTest {
    @Test fun muteDuringConnectionSetupAppliesBeforeTheFirstCaptureFrame() = runBlocking {
        Fixture(settingsDelay = 200).use { f ->
            f.voice.start()
            f.voice.setMicrophoneMuted(true)
            withTimeout(10000) { while (f.mic.starts == 0) delay(10) }
            assertTrue(f.mic.muted)
            assertTrue(f.state.value.voiceMicrophoneMuted)
            assertFalse(f.state.value.voiceMicrophoneReady)
            assertTrue(f.chat.sent.isEmpty())
        }
    }
    @Test fun stoppingSpeechDoesNotCancelWorkOrReplayTheLateFinalAndTheNextTurnStillWorks() = runBlocking {
        Fixture().use { f ->
            f.start()
            f.mic.utterance(byteArrayOf(1, 2))
            withTimeout(10000) { while (f.chat.sent.size != 1) delay(10) }
            f.voice.onAssistantDelta("First answer. ")
            assertEquals(listOf("First answer. "), f.outputs.single().tokens)
            f.voice.stopPlayback()
            f.voice.onAssistantFinal("First answer with a late final.")
            f.voice.onAssistantTurnCompleted()
            assertTrue(f.outputs.single().finals.isEmpty())
            assertEquals(0, f.outputs.single().finishes)
            assertTrue(f.voice.isActive)
            assertEquals(VoiceStreamState.LISTENING, f.state.value.voiceStreamState)
            f.mic.utterance(byteArrayOf(3, 4))
            withTimeout(10000) { while (f.chat.sent.size != 2) delay(10) }
            f.voice.onAssistantDelta("Next answer.")
            f.voice.onAssistantFinal("Next answer.")
            f.voice.onAssistantTurnCompleted()
            assertEquals(listOf("Next answer."), f.outputs.single().finals)
            assertEquals(1, f.outputs.single().finishes)
            assertNotEquals(f.chat.sent[0].turnId, f.chat.sent[1].turnId)
            assertEquals(f.chat.sent[0].callId, f.chat.sent[1].callId)
        }
    }

    @Test fun muteFencesInFlightTranscriptionAndOldPlaybackCannotChangeAReplacementCall() = runBlocking {
        Fixture(transcriptionDelay = 200).use { f ->
            f.start()
            val oldCallbacks = f.outputs.single().callbacks
            f.mic.utterance(byteArrayOf(1, 2))
            withContext(Dispatchers.IO) { f.server.takeRequest() } // settings request
            withContext(Dispatchers.IO) { f.server.takeRequest() } // transcription admitted
            f.voice.setMicrophoneMuted(true)
            f.voice.setMicrophoneMuted(false)
            delay(400)
            assertTrue(f.chat.sent.isEmpty())
            f.voice.stopStream()
            f.start()
            oldCallbacks.onError("stale playback failure")
            oldCallbacks.onCompleted()
            assertTrue(f.voice.isActive)
            assertEquals(VoiceStreamState.LISTENING, f.state.value.voiceStreamState)
            assertNull(f.state.value.error)
        }
    }

    private class Fixture(transcriptionDelay: Long = 0, settingsDelay: Long = 0) : AutoCloseable {
        val server = MockWebServer()
        val app: CompanionApp
        val state: MutableStateFlow<CompanionState>
        val chat = Conversation()
        val mic = Capture()
        val outputs = mutableListOf<Output>()
        val voice: VoiceSessionController
        init {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            app = CompanionApp()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.requestUrl?.encodedPath == "/api/audio/settings") MockResponse().setBody("""{"ok":true,"version":1,"fields":[],"live":{"mode":"chained","available":false},"streaming":true}""").setBodyDelay(settingsDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
                    else MockResponse().setBody("""{"ok":true,"transcript":"hello","provider":"fixture"}""").setBodyDelay(transcriptionDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            server.start()
            state = MutableStateFlow(CompanionState(origin = server.url("/").toString().trimEnd('/'), activeProfileId = "fixture",
                openSessionId = "s", openSessionRef = SessionRef("s", "fixture", "Voice", 0), speechBackend = SpeechBackend.HOST))
            voice = VoiceSessionController(app, state, chat, audioFactory = { Audio() }, captureFactory = { mic },
                speechOutputFactory = { _, _, cb, _ -> Output(cb).also(outputs::add) })
        }
        suspend fun start() { voice.start(); withTimeout(10000) { state.first { it.voiceMicrophoneReady } } }
        override fun close() { voice.stopStream(); app.appScope.cancel(); server.close(); Dispatchers.resetMain() }
    }
    private class Conversation : VoiceConversation {
        override val conversationEpoch = 0L
        override var onAssistantDelta: ((String) -> Unit)? = null
        override var onTurnCompleted: (() -> Unit)? = null
        override var onTurnInterrupted: (() -> Unit)? = null
        override var onTurnFailed: (() -> Unit)? = null
        val sent = java.util.concurrent.CopyOnWriteArrayList<VoiceTurnMetadata>()
        override fun newThread() = Unit
        override suspend fun ensureVoiceSession() = SessionRef("s", "fixture", "Voice", 0)
        override fun sendPrompt(text: String, displayText: String, voice: VoiceTurnMetadata?) { sent += requireNotNull(voice) }
        override fun steerVoice(text: String, voice: VoiceTurnMetadata) = Unit
        override fun interrupt() = Unit
    }
    private class Capture : CallSpeechCapture {
        override var muted = false
        @Volatile var starts = 0
        var utterance: (ByteArray) -> Unit = {}
        override fun start(scope: CoroutineScope, onReady: (Boolean) -> Unit, onSpeech: () -> Unit, onUtterance: (ByteArray) -> Unit, onError: (String) -> Unit) {
            utterance = { onSpeech(); onUtterance(it) }; onReady(!muted); starts++
        }
        override fun stop(): Job? = null
    }
    private class Audio : VoiceAudioSessionControl {
        override fun acquire(onLost: () -> Unit) = true
        override fun useSpeaker(enabled: Boolean) = true
        override fun release() = Unit
    }
    private class Output(val callbacks: SpeechCallbacks) : SpeechOutput {
        val tokens = mutableListOf<String>(); val finals = mutableListOf<String>(); var finishes = 0
        override fun feedToken(delta: String) { tokens += delta; callbacks.onStart() }
        override fun reconcileFinal(text: String) { finals += text }
        override fun finishFeed() { finishes++; callbacks.onCompleted() }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }
}
