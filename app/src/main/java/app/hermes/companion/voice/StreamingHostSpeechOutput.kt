package app.hermes.companion.voice

import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.data.remote.NativeSpeechStream
import app.hermes.companion.data.remote.SpeechStreamEvent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Host backend only. Fallback needs an explicit compatibility signal before any received PCM. */
class StreamingHostSpeechOutput(
    private val scope: CoroutineScope,
    private val transport: StreamingSpeechTransport,
    private val pcm: PcmSpeechPlayer,
    private val fallbackPlayer: SpeechPlayer,
    private val callbacks: SpeechCallbacks,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val openTimeoutMs: Long = 15_000,
    private val firstAudioTimeoutMs: Long = 60_000,
    private val finishIdleTimeoutMs: Long = 20_000,
    private val onFailureCode: (String) -> Unit = {},
) : SpeechOutput {
    private val generation = AtomicLong()
    private val text = StreamingSpeechText()
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private var finishedText = CompletableDeferred<Unit>()
    private var finishing = false
    private var closed = false
    private var job: Job? = null
    private var stream: NativeSpeechStream? = null
    private var fallbackOutput: SpeechOutput? = null

    override fun feedToken(delta: String) {
        if (closed || finishing) return
        try { text.append(delta) } catch (_: IllegalArgumentException) {
            stop(); callbacks.onError("Reply is too long to read aloud"); return
        }
        ensureJob()
        updates.trySend(Unit)
    }

    override fun reconcileFinal(text: String) {
        if (closed) return
        try {
            if (!this.text.reconcile(text)) {
                // The displayed authoritative transcript wins; never synthesize a revised prefix twice.
                stop(); callbacks.onCompleted(); return
            }
        } catch (_: IllegalArgumentException) { stop(); callbacks.onError("Reply is too long to read aloud"); return }
        updates.trySend(Unit)
    }

    override fun finishFeed() {
        if (closed || finishing) return
        finishing = true
        finishedText.complete(Unit)
        ensureJob()
        updates.trySend(Unit)
    }

    private fun ensureJob() {
        if (job != null) return
        val epoch = generation.get()
        val textDone = finishedText
        val next = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            var opened: NativeSpeechStream? = null
            try {
                if (finishing && text.allText().isBlank()) {
                    if (generation.get() == epoch) { job = null; callbacks.onCompleted() }
                    return@launch
                }
                opened = withTimeout(openTimeoutMs) { transport.openSpeechStream() }
                if (generation.get() != epoch) return@launch
                stream = opened
                converse(opened, epoch, textDone)
                if (generation.get() == epoch) { job = null; callbacks.onCompleted() }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException && generation.get() == epoch) {
                    job = null; pcm.stop(); callbacks.onError("Host streaming speech timed out")
                } else throw error
            } catch (error: Exception) {
                if (generation.get() == epoch) {
                    val location = error.stackTrace.firstOrNull { it.className.startsWith("app.hermes.companion.voice.") }
                    val code = (error as? DashboardException)?.code ?: error.javaClass.simpleName +
                        (location?.let { "@${it.fileName}:${it.lineNumber}" } ?: "")
                    runCatching { onFailureCode(code) }
                    job = null; pcm.stop(); callbacks.onError("Host streaming speech failed. Check voice settings and connection.")
                }
            } finally {
                opened?.stop()
                if (generation.get() == epoch) { stream = null; pcm.stop(); fallbackOutput?.shutdown(); fallbackOutput = null }
            }
        }
        job = next
        next.start()
    }

    private suspend fun converse(socket: NativeSpeechStream, epoch: Long, textDone: Deferred<Unit>) = coroutineScope {
        var receivedPcm = false
        var formatStarted = false
        var finishSent = false
        var ended = false
        var fallback = false
        var handlingFrame = false
        val ready = CompletableDeferred<Unit>()
        var firstAudio: Job? = null
        var idleTimer: Job? = null
        fun resetIdleTimer() {
            if (!finishSent || handlingFrame) return
            idleTimer?.cancel()
            idleTimer = launch { delay(finishIdleTimeoutMs); throw DashboardException("speech_stream_idle", "Host speech stream timed out") }
        }
        val sender = launch {
            try { withTimeout(openTimeoutMs) { ready.await() } }
            catch (_: TimeoutCancellationException) {
                throw DashboardException("speech_stream_handshake", "Host speech stream did not open")
            }
            for (signal in updates) {
                if (generation.get() != epoch || fallback) break
                val batch = text.next(finishing)
                if (batch != null) {
                    if (batch.text.isNotBlank()) {
                        if (!socket.sendText(batch.text + " ")) return@launch // Events carry the authoritative terminal outcome.
                        if (firstAudio == null && !receivedPcm) firstAudio = launch {
                            delay(firstAudioTimeoutMs)
                            throw DashboardException("speech_stream_first_audio", "Host speech did not start")
                        }
                    }
                    text.commit(batch)
                }
                if (finishing) {
                    if (!socket.finish()) return@launch
                    finishSent = true
                    resetIdleTimer()
                    break
                }
            }
        }
        try {
            socket.events.collect { event ->
                currentCoroutineContext().ensureActive()
                if (generation.get() != epoch) throw CancellationException("Speech changed")
                handlingFrame = true
                idleTimer?.cancel()
                when (event) {
                    SpeechStreamEvent.Ready -> {
                        check(!ready.isCompleted); ready.complete(Unit)
                    }
                    is SpeechStreamEvent.Start -> {
                        check(ready.isCompleted && !formatStarted)
                        pcm.begin(event.format) {
                            scope.launch(dispatcher) { if (generation.get() == epoch) callbacks.onStart() }
                        }
                        formatStarted = true
                    }
                    is SpeechStreamEvent.Pcm -> {
                        try {
                            check(formatStarted && !ended && !fallback)
                            receivedPcm = true // Receiving, rather than playing, commits the no-replay boundary.
                            firstAudio?.cancel()
                            pcm.enqueue(event.bytes)
                        } finally { event.bytes.fill(0) }
                    }
                    SpeechStreamEvent.End -> {
                        check(finishSent && formatStarted && !ended && !fallback)
                        ended = true; sender.cancel(); firstAudio?.cancel(); idleTimer?.cancel()
                        pcm.finishAndDrain()
                    }
                    SpeechStreamEvent.Fallback, SpeechStreamEvent.Unsupported -> {
                        check(!receivedPcm && !ended && !fallback)
                        fallback = true; sender.cancel(); firstAudio?.cancel(); idleTimer?.cancel()
                        pcm.stop()
                        textDone.await()
                        playFallback(epoch)
                        ended = true
                    }
                    is SpeechStreamEvent.Failed -> throw event.error
                }
                handlingFrame = false
                if (!ended && !fallback) resetIdleTimer()
            }
            check(ended) { "Host speech stream ended without a terminal response" }
        } finally { sender.cancel(); firstAudio?.cancel(); idleTimer?.cancel() }
    }

    private suspend fun playFallback(epoch: Long) = coroutineScope {
        val done = CompletableDeferred<Unit>()
        val output = HostSpeechOutput(this, transport, fallbackPlayer, SpeechCallbacks(
            { if (generation.get() == epoch) callbacks.onStart() },
            { done.complete(Unit) },
            { done.completeExceptionally(IllegalStateException(it)) }), dispatcher)
        fallbackOutput = output
        try {
            output.feedToken(text.allText()); output.finishFeed()
            done.await()
        } finally { output.shutdown(); if (fallbackOutput === output) fallbackOutput = null }
    }

    override fun stop() {
        generation.incrementAndGet()
        job?.cancel(); job = null
        stream?.stop(); stream = null
        pcm.stop()
        fallbackOutput?.shutdown(); fallbackOutput = null
        finishing = false
        finishedText.cancel(); finishedText = CompletableDeferred()
        text.clear()
    }

    override fun shutdown() { closed = true; stop(); updates.close() }
}
