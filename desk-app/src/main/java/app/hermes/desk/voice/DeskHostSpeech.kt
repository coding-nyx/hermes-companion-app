package app.hermes.desk.voice

import app.hermes.companion.model.SynthesizedSpeech
import app.hermes.companion.domain.VoiceTextFilter
import app.hermes.companion.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface SpeechCapture {
    suspend fun record(onReady: () -> Unit, onActivity: () -> Unit): ByteArray?
    fun cancel()
}
interface SpeechPlayer {
    fun play(audio: SynthesizedSpeech, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit)
    fun stop()
}

/** App-owned PCM capture has no recognizer service tones. Silence does not end the call. */
class DeskHostInput(
    private val scope: CoroutineScope,
    private val capture: SpeechCapture,
    private val transcribe: suspend (ByteArray) -> String,
) : SpeechInput {
    override val resumesCaptureAfterProcessing = true
    override val terminalErrors = true
    override val reportsReadiness = true
    private val generation = AtomicLong()
    private var job: Job? = null
    override fun startListening(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit,
        onErrorCode: ((Int) -> Unit)?, onStateChange: (Boolean) -> Unit, onActivity: () -> Unit, onProcessing: () -> Unit) {
        stopListening()
        val epoch = generation.get()
        job = scope.launch {
            try {
                while (isActive && epoch == generation.get()) {
                    val wav = capture.record({ if (epoch == generation.get()) onStateChange(true) }, { if (epoch == generation.get()) onActivity() })
                    if (wav == null) continue // A quiet capture window is not an end-of-conversation event.
                    try {
                        if (epoch != generation.get()) return@launch
                        onStateChange(false); onProcessing()
                        val text = withTimeout(60_000) { transcribe(wav) }.trim()
                        if (epoch != generation.get()) return@launch
                        if (text.isNotBlank()) { onResult(text); return@launch }
                        // A voiced window can contain a cough/noise. Resume capture with a
                        // new buffer; never resubmit that audio or require a Resume tap.
                        delay(250)
                    } finally { wav.fill(0) }
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException && epoch == generation.get()) onError("Hermes transcription timed out") else throw e
            } catch (_: Exception) {
                if (epoch == generation.get()) { onStateChange(false); onError("Hermes speech input failed. Check the host speech configuration and microphone.") }
            }
        }
    }
    override fun stopListening() { generation.incrementAndGet(); capture.cancel(); job?.cancel(); job = null }
}

/** Streaming text is spoken clause-by-clause using the host's existing selected voice. */
class DeskHostOutput(
    private val scope: CoroutineScope,
    private val synthesize: suspend (String) -> SynthesizedSpeech,
    private val player: SpeechPlayer,
    private val callbacks: SpeechCallbacks,
) : SpeechOutput {
    private var generation = 0L
    private var closed = false
    private var finished = false
    private var channel: Channel<String>? = null
    private var job: Job? = null
    private val input = StringBuilder()
    private val pending = StringBuilder()
    override fun feedToken(delta: String) {
        if (closed || finished) return
        if (input.length + delta.length > 64_000) { stop(); callbacks.onError("The spoken reply is too long"); return }
        input.append(delta); pending.append(delta)
        drain(false)
    }
    override fun reconcileFinal(text: String) {
        if (closed || finished) return
        val received = input.toString()
        if (received.isEmpty()) feedToken(text)
        else if (text.startsWith(received)) feedToken(text.removePrefix(received))
        else if (channel == null) { input.clear(); pending.clear(); feedToken(text) }
        // Already spoken text is never replayed to "correct" a final transcript.
    }
    override fun finishFeed() {
        if (closed || finished) return
        drain(true); finished = true
        if (channel == null) callbacks.onCompleted() else channel?.close()
    }
    private fun drain(final: Boolean) {
        while (pending.isNotEmpty()) {
            val text = pending.toString()
            val clause = VoiceTextFilter.extractNextClause(text)
            val cut = when {
                clause != null -> text.length - clause.second.length
                text.length >= 280 -> text.lastIndexOf(' ', 280).takeIf { it > 0 } ?: 280
                final -> text.length
                else -> return
            }
            val clean = VoiceTextFilter.cleanForSpeech(text.take(cut))
            pending.delete(0, cut)
            if (clean.isBlank()) continue
            if (channel == null) startWorker()
            if (channel?.trySend(clean)?.isSuccess != true) { stop(); callbacks.onError("Speech output fell behind; playback stopped"); return }
        }
    }
    private fun startWorker() {
        val queue = Channel<String>(32)
        channel = queue
        val epoch = generation
        job = scope.launch {
            try {
                for (text in queue) {
                    var audio: SynthesizedSpeech? = null
                    try {
                        withTimeout(60_000) { audio = synthesize(text) }
                        if (epoch != generation) return@launch
                        suspendCancellableCoroutine<Unit> { continuation ->
                            player.play(requireNotNull(audio),
                                { if (epoch == generation) callbacks.onStart() },
                                { if (continuation.isActive) continuation.resume(Unit) },
                                { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Speech playback failed")) })
                            continuation.invokeOnCancellation { player.stop() }
                        }
                    } finally { audio?.bytes?.fill(0) }
                }
                if (epoch == generation) callbacks.onCompleted()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (epoch == generation) callbacks.onError("Hermes speech output failed. Check the selected host voice.") }
        }
    }
    override fun stop() {
        generation++; job?.cancel(); job = null; channel?.cancel(); channel = null
        player.stop(); input.clear(); pending.clear(); finished = false
    }
    override fun shutdown() { closed = true; stop() }
}
