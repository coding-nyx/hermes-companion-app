package app.hermes.companion.voice

import android.content.Context
import android.speech.SpeechRecognizer
import app.hermes.companion.VoiceStreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Continuous bidirectional hands-free voice stream engine.
 *
 * Implements conversational turn-taking:
 * [IDLE] -> start -> [LISTENING] -> (user finishes phrase) -> [THINKING] (submit prompt) ->
 * [SPEAKING] (streaming sentence-chunked TTS) -> (TTS done) -> auto-reopen [LISTENING] ->
 * (15s inactivity) -> auto-sleep [IDLE].
 *
 * Supports instant zero-latency barge-in (cancelling TTS and interrupting prompt).
 */
class VoiceStreamEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSendPrompt: (String) -> Unit,
    private val onInterrupt: () -> Unit,
    private val onStateChange: (VoiceStreamState) -> Unit,
    private val onError: (String) -> Unit = {},
    private val inactivityTimeoutMs: Long = 15_000L,
) {
    private var streamState: VoiceStreamState = VoiceStreamState.IDLE
    private var inactivityJob: Job? = null
    private var listeningRetryCount: Int = 0

    private val voiceInput = VoiceInputManager(context)
    private var tts: TextToSpeechEngine? = null

    init {
        tts = TextToSpeechEngine(
            context = context,
            onStart = {
                // TTS began outputting audio
                updateState(VoiceStreamState.SPEAKING)
            },
            onAllCompleted = {
                // TTS finished speaking all queued clauses
                scope.launch(Dispatchers.Main) {
                    if (streamState == VoiceStreamState.SPEAKING) {
                        // Automatically re-arm microphone for hands-free follow-up!
                        startListeningLoop()
                    }
                }
            },
            onError = { err ->
                onError(err)
            },
        )
    }

    val state: VoiceStreamState get() = streamState
    val isActive: Boolean get() = streamState != VoiceStreamState.IDLE

    fun toggle() {
        if (isActive) {
            stopStream()
        } else {
            startStream()
        }
    }

    fun startStream() {
        scope.launch(Dispatchers.Main) {
            listeningRetryCount = 0
            startListeningLoop()
        }
    }

    fun stopStream() {
        scope.launch(Dispatchers.Main) {
            cancelInactivityTimer()
            voiceInput.stopListening()
            tts?.stop()
            updateState(VoiceStreamState.IDLE)
        }
    }

    fun bargeIn() {
        scope.launch(Dispatchers.Main) {
            tts?.stop()
            onInterrupt()
            if (isActive) {
                startListeningLoop()
            }
        }
    }

    /**
     * Feed streaming LLM assistant deltas to the TTS engine.
     */
    fun onAssistantDelta(delta: String) {
        if (!isActive) return
        if (streamState == VoiceStreamState.THINKING) {
            updateState(VoiceStreamState.SPEAKING)
        }
        tts?.feedToken(delta)
    }

    /**
     * LLM has finished generating the turn.
     */
    fun onAssistantTurnCompleted() {
        if (!isActive) return
        tts?.finishFeed()
    }

    /**
     * Assistant was cancelled or interrupted externally.
     */
    fun onAssistantTurnInterrupted() {
        tts?.stop()
        if (isActive) {
            scope.launch(Dispatchers.Main) {
                startListeningLoop()
            }
        }
    }

    private fun startListeningLoop() {
        cancelInactivityTimer()
        tts?.stop()
        updateState(VoiceStreamState.LISTENING)

        // Arm the inactivity timer (15s of silence -> auto-sleep)
        armInactivityTimer()

        voiceInput.startListening(
            onPartial = {
                // User started speaking! Reset inactivity timer
                resetInactivityTimer()
            },
            onResult = { recognizedText ->
                cancelInactivityTimer()
                listeningRetryCount = 0
                val clean = recognizedText.trim()
                if (clean.isNotBlank()) {
                    updateState(VoiceStreamState.THINKING)
                    onSendPrompt(clean)
                } else if (isActive) {
                    // Empty speech result, continue listening
                    startListeningLoop()
                }
            },
            onErrorCode = { code: Int ->
                when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // User was quiet during this recognition window.
                        // If inactivity timer hasn't fired yet, restart listening window!
                        if (isActive && streamState == VoiceStreamState.LISTENING) {
                            startListeningLoop()
                        }
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        onError("Microphone permission denied")
                        stopStream()
                    }
                    -1 -> {
                        onError("Speech recognition not available")
                        stopStream()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        listeningRetryCount++
                        if (listeningRetryCount < 3 && isActive && streamState == VoiceStreamState.LISTENING) {
                            scope.launch(Dispatchers.Main) {
                                voiceInput.stopListening()
                                delay(500)
                                if (isActive && streamState == VoiceStreamState.LISTENING) {
                                    startListeningLoop()
                                }
                            }
                        } else {
                            onError("speech engine busy")
                            stopStream()
                        }
                    }
                    else -> {
                        listeningRetryCount++
                        if (listeningRetryCount < 3 && isActive && streamState == VoiceStreamState.LISTENING) {
                            scope.launch(Dispatchers.Main) {
                                delay(300)
                                if (isActive && streamState == VoiceStreamState.LISTENING) {
                                    startListeningLoop()
                                }
                            }
                        } else {
                            val errorName = when (code) {
                                SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
                                SpeechRecognizer.ERROR_CLIENT -> "client error"
                                SpeechRecognizer.ERROR_NETWORK -> "network error"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
                                SpeechRecognizer.ERROR_SERVER -> "server error"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "speech engine busy"
                                11 -> "speech service disconnected"
                                12 -> "language not supported"
                                else -> "speech error ($code)"
                            }
                            onError(errorName)
                            stopStream()
                        }
                    }
                }
            },
            onError = { _ -> },
            onStateChange = { _ -> },
        )
    }

    private fun armInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = scope.launch(Dispatchers.Main) {
            delay(inactivityTimeoutMs)
            // If we are still waiting for speech after 15s, auto-sleep to IDLE
            if (streamState == VoiceStreamState.LISTENING) {
                stopStream()
            }
        }
    }

    private fun resetInactivityTimer() {
        armInactivityTimer()
    }

    private fun cancelInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    private fun updateState(next: VoiceStreamState) {
        if (streamState != next) {
            streamState = next
            onStateChange(next)
        }
    }

    fun shutdown() {
        stopStream()
        tts?.shutdown()
        tts = null
    }
}
