package app.hermes.companion.voice

/** Main-thread owner, separate from conversation capture. No chat prompt is submitted. */
class MessageReadAloud(
    private val audio: VoiceAudioSessionControl,
    private val onPlaying: (String?) -> Unit,
    private val onError: (String) -> Unit,
    private val dispatch: (() -> Unit) -> Unit = { it() },
) {
    private var generation = 0L
    private var output: SpeechOutput? = null
    fun play(id: String, text: String, factory: (SpeechCallbacks) -> SpeechOutput) {
        stop()
        val epoch = generation
        if (!audio.acquire { dispatch { if (epoch == generation) stop() } }) { onError("Finish the active call before reading aloud"); return }
        if (!audio.useSpeaker(true)) { stop(); onError("Speaker route unavailable"); return }
        onPlaying(id)
        try {
            val created = factory(SpeechCallbacks(
                onStart = {},
                onCompleted = { dispatch { if (epoch == generation) stop() } },
                onError = { error -> dispatch { if (epoch == generation) { stop(); onError(error) } } }))
            if (epoch != generation) { created.shutdown(); return }
            output = created
            output?.feedToken(text)
            output?.finishFeed()
        } catch (_: Exception) { stop(); onError("Read aloud could not start") }
    }
    fun stop() {
        generation++
        val old = output; output = null
        old?.shutdown(); audio.release(); onPlaying(null)
    }
}
