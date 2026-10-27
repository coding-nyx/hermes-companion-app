package app.hermes.companion.voice

import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.NativeSpeechStream
import app.hermes.companion.model.SynthesizedSpeech
import app.hermes.companion.domain.VoiceTextFilter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** Immutable provider scope; a late response can never follow the UI onto another host. */
interface SpeechTransport {
    suspend fun transcribe(wav: ByteArray): String
    suspend fun synthesize(text: String): SynthesizedSpeech
}

interface StreamingSpeechTransport : SpeechTransport {
    suspend fun openSpeechStream(): NativeSpeechStream
}

class NativeSpeechTransport(
    private val client: DashboardClient,
    private val origin: String,
    private val profile: String,
    private val snapshot: String = "",
) : StreamingSpeechTransport {
    init { require(origin.isNotBlank() && profile.isNotBlank()) }
    override suspend fun transcribe(wav: ByteArray) = client.transcribeAudio(origin, profile, wav, "audio/wav", snapshot).text
    override suspend fun synthesize(text: String) = client.synthesizeSpeech(origin, profile, text, snapshot)
    override suspend fun openSpeechStream() = client.openSpeechStream(origin, profile, snapshot)
}

interface SpeechCapture {
    suspend fun record(onReady: () -> Unit, onActivity: () -> Unit): ByteArray?
    fun cancel()
}

interface SpeechPlayer {
    fun play(audio: SynthesizedSpeech, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit)
    fun stop()
}

class HostSpeechInput(
    private val scope: CoroutineScope,
    private val capture: SpeechCapture,
    private val transport: SpeechTransport,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SpeechInput {
    override val terminalErrors = true
    override val reportsReadiness = true
    private val generation = AtomicLong()
    private var job: Job? = null

    override fun startListening(onPartial: (String) -> Unit, onResult: (String) -> Unit,
        onError: (String) -> Unit, onErrorCode: ((Int) -> Unit)?, onStateChange: (Boolean) -> Unit,
        onActivity: () -> Unit, onProcessing: () -> Unit) {
        stopListening()
        val epoch = generation.get()
        fun active() = generation.get() == epoch
        job = scope.launch(dispatcher) {
            try {
                val wav = capture.record(
                    onReady = { if (active()) onStateChange(true) },
                    onActivity = { if (active()) onActivity() })
                try {
                    if (!active()) return@launch
                    onStateChange(false)
                    if (wav == null) { onResult(""); return@launch }
                    onProcessing()
                    val text = withTimeout(60_000) { transport.transcribe(wav) }
                    if (active()) onResult(text.trim())
                } finally { wav?.fill(0) }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException && active()) {
                    onStateChange(false); onError("Host transcription timed out")
                } else throw e
            } catch (e: SecurityException) {
                if (active()) { onStateChange(false); onError("Microphone permission denied") }
            } catch (e: Exception) {
                if (active()) { onStateChange(false); onError("Host speech failed. Check microphone, voice settings and connection.") }
            }
        }
    }

    override fun stopListening() {
        generation.incrementAndGet()
        capture.cancel()
        job?.cancel(); job = null
    }
}

/** Phase-one whole-reply synthesis, chunked only to respect provider text limits. */
class HostSpeechOutput(
    private val scope: CoroutineScope,
    private val transport: SpeechTransport,
    private val player: SpeechPlayer,
    private val callbacks: SpeechCallbacks,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SpeechOutput {
    private val generation = AtomicLong()
    private val text = StringBuilder()
    private var job: Job? = null
    private var closed = false

    override fun feedToken(delta: String) {
        if (closed || job != null) return
        if (text.length + delta.length > 64_000) { stop(); callbacks.onError("Reply is too long to read aloud"); return }
        text.append(delta)
    }

    override fun finishFeed() {
        if (closed || job != null) return
        val clean = VoiceTextFilter.cleanForSpeech(text.toString())
        text.clear()
        val epoch = generation.get()
        val next = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            try {
                // Word boundaries preserve content across requests; never replay a played chunk.
                for (chunk in chunks(clean)) {
                    val audio = withTimeout(60_000) { transport.synthesize(chunk) }
                    try {
                        if (generation.get() != epoch) return@launch
                        withTimeout(180_000) { suspendCancellableCoroutine<Unit> { continuation ->
                            player.play(audio, { if (generation.get() == epoch) callbacks.onStart() },
                                { if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) },
                                { if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("playback failed"))) })
                        } }
                    } finally { audio.bytes.fill(0) }
                }
                if (generation.get() == epoch) { job = null; callbacks.onCompleted() }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException && generation.get() == epoch) {
                    job = null; player.stop(); callbacks.onError("Host speech timed out")
                } else throw e
            } catch (e: Exception) {
                if (generation.get() == epoch) { job = null; player.stop(); callbacks.onError("Host speech playback failed. Check voice settings and connection.") }
            }
        }
        job = next
        next.start()
    }

    override fun reconcileFinal(text: String) {
        if (closed || job != null) return
        this.text.clear()
        feedToken(text)
    }

    override fun stop() {
        generation.incrementAndGet()
        job?.cancel(); job = null
        text.clear(); player.stop()
    }
    override fun shutdown() { closed = true; stop() }

    internal fun chunks(value: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = value.trim()
        while (remaining.isNotEmpty()) {
            val end = if (remaining.length <= 1_000) remaining.length else
                remaining.lastIndexOf(' ', 1_000).takeIf { it > 0 } ?: 1_000
            result += remaining.substring(0, end)
            remaining = remaining.substring(end).trimStart()
        }
        return result
    }
}
