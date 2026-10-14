package app.hermes.companion.voice

interface SpeechInput {
    val terminalErrors: Boolean get() = false
    fun startListening(
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {},
        onErrorCode: ((Int) -> Unit)? = null,
        onStateChange: (Boolean) -> Unit = {},
        onActivity: () -> Unit = {},
        onProcessing: () -> Unit = {},
    )
    fun stopListening()
}

interface SpeechOutput {
    fun feedToken(delta: String)
    /** Buffered speech can replace provisional text. Already-played system speech is not replayed. */
    fun reconcileFinal(text: String) = Unit
    fun finishFeed()
    fun stop()
    fun shutdown()
}

data class SpeechCallbacks(
    val onStart: () -> Unit,
    val onCompleted: () -> Unit,
    val onError: (String) -> Unit,
)

/** One voice conversation owns focus and routing until it ends. */
interface VoiceAudioSessionControl {
    fun acquire(onLost: () -> Unit): Boolean
    fun release()
    fun useSpeaker(enabled: Boolean): Boolean
}
