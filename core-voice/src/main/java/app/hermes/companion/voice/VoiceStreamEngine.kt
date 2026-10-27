package app.hermes.companion.voice

import android.content.Context
import android.os.SystemClock
import android.speech.SpeechRecognizer
import app.hermes.companion.VoiceStreamState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground, half-duplex speech → Hermes text → speech. This is not a realtime call engine. */
class VoiceStreamEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val onSendPrompt: (String) -> Unit,
    private val onInterrupt: () -> Unit,
    private val onStateChange: (VoiceStreamState) -> Unit,
    private val onError: (String) -> Unit = {},
    private val inactivityTimeoutMs: Long = 15_000L,
    private val voiceInput: SpeechInput = VoiceInputManager(context),
    private val outputFactory: (SpeechCallbacks) -> SpeechOutput = { cb ->
        TextToSpeechEngine(context, cb.onStart, cb.onCompleted, cb.onError, VoiceAudioSession.attributes)
    },
    private val audioSession: VoiceAudioSessionControl = VoiceAudioSession(context),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val transcriptionTimeoutMs: Long = 65_000,
    private val onMutedChange: (Boolean) -> Unit = {},
    private val onReadinessChange: (Boolean) -> Unit = {},
    private val onInputPaused: (String) -> Unit = {},
    private val readinessTimeoutMs: Long = 10_000,
) {
    private val generation = AtomicLong()
    private var activeGeneration = 0L
    private var streamState = VoiceStreamState.IDLE
    private var inactivityJob: Job? = null
    private var lastSpeechAt = 0L
    private var waitingForReply = false
    private var speaker = true
    private var tts: SpeechOutput? = null
    private var closed = false
    private var microphoneMuted = false
    private var microphoneReady = false
    val isMicrophoneMuted: Boolean get() = microphoneMuted
    // A conversation contains many recognition attempts. Fence each one.
    private var inputGeneration = 0L

    val state: VoiceStreamState get() = streamState
    val isActive: Boolean get() = streamState != VoiceStreamState.IDLE && activeGeneration == generation.get()

    private fun current(epoch: Long) = !closed && epoch == generation.get() && epoch == activeGeneration
    private fun onMain(epoch: Long = activeGeneration, action: () -> Unit) {
        scope.launch(mainDispatcher) { if (current(epoch)) action() }
    }

    fun toggle() { if (isActive) stopStream() else startStream() }

    fun startStream() {
        if (closed || isActive) return
        val epoch = generation.incrementAndGet()
        scope.launch(mainDispatcher) {
            if (closed || epoch != generation.get()) return@launch
            releaseResources()
            activeGeneration = epoch
            if (!audioSession.acquire { onMain(epoch) { fail("Voice stopped because another audio session needs the device") } }) {
                updateState(VoiceStreamState.IDLE)
                onError("Voice audio is unavailable; finish any active call and try again")
                return@launch
            }
            if (!audioSession.useSpeaker(speaker)) {
                fail("The selected voice audio route is unavailable")
                return@launch
            }
            lastSpeechAt = nowMs()
            waitingForReply = false
            updateState(VoiceStreamState.LISTENING)
            val output = outputFactory(SpeechCallbacks(
                onStart = { onMain(epoch) { if (waitingForReply) updateState(VoiceStreamState.SPEAKING) } },
                onCompleted = { onMain(epoch) {
                    if (waitingForReply) {
                        waitingForReply = false
                        startListeningLoop(newTurn = true)
                    }
                } },
                onError = { error -> onMain(epoch) { fail(error) } },
            ))
            if (!current(epoch)) { output.shutdown(); return@launch }
            tts = output
            startListeningLoop()
        }
    }

    fun stopStream() {
        val epoch = generation.incrementAndGet() // Fence callbacks immediately, even off main.
        scope.launch(mainDispatcher) {
            if (epoch != generation.get()) return@launch
            releaseResources()
            updateState(VoiceStreamState.IDLE)
        }
    }

    /** Muting fences recognition immediately. Reply playback may finish, but cannot reopen capture. */
    fun setMicrophoneMuted(muted: Boolean) = onMain {
        if (!isActive || microphoneMuted == muted) return@onMain
        microphoneMuted = muted
        onMutedChange(muted)
        if (muted) {
            stopInput()
            inactivityJob?.cancel(); inactivityJob = null
            if (streamState == VoiceStreamState.LISTENING || streamState == VoiceStreamState.TRANSCRIBING)
                updateState(VoiceStreamState.MUTED)
        } else if (streamState == VoiceStreamState.MUTED) startListeningLoop(newTurn = true)
    }

    fun useSpeaker(enabled: Boolean) {
        speaker = enabled
        if (isActive) onMain {
            if (!audioSession.useSpeaker(enabled)) fail("The selected voice audio route is unavailable")
        }
    }

    fun bargeIn() = onMain {
        tts?.stop()
        waitingForReply = false
        onInterrupt()
        startListeningLoop(newTurn = true)
    }

    fun onAssistantDelta(delta: String) = onMain {
        // Typed turns or an old reply must not become speech while the user is listening.
        if (waitingForReply) tts?.feedToken(delta)
    }

    fun onAssistantTurnCompleted() = onMain {
        if (waitingForReply) tts?.finishFeed()
    }

    fun onAssistantFinal(text: String) = onMain {
        if (waitingForReply) tts?.reconcileFinal(text)
    }

    fun onAssistantTurnInterrupted() = onMain {
        if (waitingForReply) {
            tts?.stop()
            waitingForReply = false
            startListeningLoop(newTurn = true)
        }
    }

    private fun startListeningLoop(newTurn: Boolean = false) {
        if (!isActive) return
        if (microphoneMuted) { updateState(VoiceStreamState.MUTED); return }
        inactivityJob?.cancel(); inactivityJob = null
        if (newTurn) lastSpeechAt = nowMs()
        tts?.stop()
        stopInput()
        updateState(VoiceStreamState.LISTENING)
        val epoch = activeGeneration
        val inputEpoch = inputGeneration
        fun ready() {
            if (inputEpoch != inputGeneration || streamState != VoiceStreamState.LISTENING) return
            setReady(true); lastSpeechAt = nowMs(); armInactivityTimer()
        }
        if (!voiceInput.reportsReadiness) ready()
        else inactivityJob = scope.launch(mainDispatcher) {
            delay(readinessTimeoutMs)
            if (current(epoch) && inputEpoch == inputGeneration && !microphoneReady && streamState == VoiceStreamState.LISTENING)
                fail("Microphone did not become ready. Try starting voice again.")
        }
        try {
            voiceInput.startListening(
                onPartial = { onMain(epoch) {
                    if (inputEpoch == inputGeneration && streamState == VoiceStreamState.LISTENING) {
                        ready()
                    }
                } },
                onResult = { text -> onMain(epoch) {
                    if (inputEpoch != inputGeneration) return@onMain
                    if (streamState != VoiceStreamState.LISTENING && streamState != VoiceStreamState.TRANSCRIBING) return@onMain
                    stopInput()
                    val clean = text.trim()
                    if (clean.isBlank()) pauseForSilence()
                    else {
                        inactivityJob?.cancel()
                        waitingForReply = true
                        updateState(VoiceStreamState.THINKING)
                        onSendPrompt(clean)
                    }
                } },
                onErrorCode = { code -> onMain(epoch) { if (inputEpoch == inputGeneration) recognitionError(code) } },
                onError = { error -> if (voiceInput.terminalErrors) onMain(epoch) {
                    if (inputEpoch == inputGeneration) fail(error)
                } },
                onStateChange = { isReady -> if (isReady) onMain(epoch) {
                    if (inputEpoch == inputGeneration && streamState == VoiceStreamState.TRANSCRIBING && voiceInput.resumesCaptureAfterProcessing) {
                        inactivityJob?.cancel(); inactivityJob = null
                        updateState(VoiceStreamState.LISTENING)
                    }
                    ready()
                } },
                onActivity = { onMain(epoch) {
                    if (inputEpoch == inputGeneration && streamState == VoiceStreamState.LISTENING) {
                        ready()
                    }
                } },
                onProcessing = { onMain(epoch) {
                    if (inputEpoch == inputGeneration && streamState == VoiceStreamState.LISTENING) {
                        inactivityJob?.cancel()
                        setReady(false)
                        updateState(VoiceStreamState.TRANSCRIBING)
                        inactivityJob = scope.launch(mainDispatcher) {
                            delay(transcriptionTimeoutMs)
                            if (current(epoch) && inputEpoch == inputGeneration && streamState == VoiceStreamState.TRANSCRIBING)
                                fail("Speech transcription timed out")
                        }
                    }
                } },
            )
        } catch (e: RuntimeException) {
            fail("Microphone could not start: ${e.message ?: "speech service unavailable"}")
        }
    }

    private fun recognitionError(code: Int) {
        if (streamState != VoiceStreamState.LISTENING && streamState != VoiceStreamState.TRANSCRIBING) return
        if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            pauseForSilence(); return
        }
        fail(when (code) {
            -1 -> "Speech recognition is unavailable"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
            SpeechRecognizer.ERROR_AUDIO -> "Microphone recording failed"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network failed. Start voice again when connected."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Start voice again when ready."
            else -> "Speech recognition failed ($code)"
        })
    }

    private fun pauseForSilence() {
        stopInput()
        inactivityJob?.cancel(); inactivityJob = null
        microphoneMuted = true; onMutedChange(true)
        updateState(VoiceStreamState.MUTED)
        onInputPaused("No speech detected. Tap Resume mic when you are ready.")
    }

    private fun setReady(ready: Boolean) {
        if (microphoneReady != ready) { microphoneReady = ready; onReadinessChange(ready) }
    }

    private fun armInactivityTimer() {
        inactivityJob?.cancel()
        val epoch = activeGeneration
        inactivityJob = scope.launch(mainDispatcher) {
            delay((inactivityTimeoutMs - (nowMs() - lastSpeechAt)).coerceAtLeast(0))
            if (current(epoch) && streamState == VoiceStreamState.LISTENING) pauseForSilence()
        }
    }

    private fun fail(message: String) {
        stopStream()
        onError(message)
    }

    private fun releaseResources() {
        inactivityJob?.cancel(); inactivityJob = null
        waitingForReply = false
        if (microphoneMuted) { microphoneMuted = false; onMutedChange(false) }
        stopInput()
        tts?.shutdown(); tts = null
        audioSession.release()
    }

    private fun stopInput() {
        inputGeneration++
        setReady(false)
        voiceInput.stopListening()
    }

    private fun updateState(next: VoiceStreamState) {
        if (streamState != next) { streamState = next; onStateChange(next) }
    }

    fun shutdown() {
        closed = true
        generation.incrementAndGet()
        // ViewModel teardown may cancel its scope, so cleanup must happen synchronously on main.
        releaseResources()
        updateState(VoiceStreamState.IDLE)
    }
}
