package app.hermes.companion.voice

import android.media.AudioFormat
import android.media.AudioTrack
import app.hermes.companion.data.remote.SpeechPcmFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

interface PcmSpeechPlayer {
    /** onStart runs on the playback worker when the first samples reach Android. */
    suspend fun begin(format: SpeechPcmFormat, onStart: () -> Unit = {})
    suspend fun enqueue(bytes: ByteArray)
    suspend fun finishAndDrain()
    fun stop()
}

internal interface PcmAudioSink {
    fun play()
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun playedFrames(): Long
    fun release()
}

/** One writer, 100 × 20ms slots. Cancellation clears both queued PCM and the hardware buffer. */
internal class AndroidPcmSpeechPlayer(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sinkFactory: (SpeechPcmFormat) -> PcmAudioSink = { AudioTrackSink(it) },
) : PcmSpeechPlayer {
    private val generation = AtomicLong()
    private val current = AtomicReference<Playback?>()

    override suspend fun begin(format: SpeechPcmFormat, onStart: () -> Unit) {
        stop()
        val epoch = generation.get()
        var created: PcmAudioSink? = null
        val sink = try {
            withContext(dispatcher) { sinkFactory(format).also { created = it } }
        } catch (error: Throwable) { created?.release(); throw error }
        if (generation.get() != epoch) { sink.release(); throw CancellationException("Playback canceled") }
        val playback = Playback(format, sink)
        current.set(playback)
        val worker = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            try {
                sink.play()
                var written = 0L
                var started = false
                for (chunk in playback.queue) {
                    try {
                        var offset = 0
                        while (offset < chunk.size) {
                            ensureActive()
                            val count = sink.write(chunk, offset, chunk.size - offset)
                            check(count > 0 && count <= chunk.size - offset && count % 2 == 0) { "PCM playback write failed" }
                            offset += count
                            written += count / 2
                            if (!started) { started = true; onStart() }
                        }
                    } finally { chunk.fill(0) }
                }
                // end-of-network is not end-of-speech: wait for Android's playback head.
                var lastHead = sink.playedFrames()
                var played = lastHead
                val budget = ((written - played).coerceAtLeast(0) * 1000 / format.sampleRate) + 10_000
                withTimeout(budget) {
                    while (played < written) {
                        delay(10)
                        val head = sink.playedFrames()
                        played += (head - lastHead) and 0xffff_ffffL
                        lastHead = head
                    }
                }
                playback.release()
                playback.done.complete(Unit)
            } catch (error: Throwable) {
                playback.release()
                playback.done.completeExceptionally(error)
            } finally { playback.queue.cancel(); playback.release() }
        }
        playback.worker = worker
        worker.invokeOnCompletion { error ->
            if (!playback.done.isCompleted) {
                playback.queue.cancel(); playback.release()
                playback.done.completeExceptionally(error ?: CancellationException("Playback ended"))
            }
        }
        if (generation.get() != epoch || current.get() !== playback) {
            current.compareAndSet(playback, null)
            playback.cancel()
            throw CancellationException("Playback canceled")
        }
        worker.start()
    }

    override suspend fun enqueue(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size % 2 == 0)
        val playback = current.get() ?: error("PCM playback has not started")
        val slice = playback.format.sampleRate / 50 * 2
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            check(current.get() === playback) { "PCM playback changed" }
            val end = minOf(bytes.size, offset + slice)
            val chunk = bytes.copyOfRange(offset, end)
            try { playback.queue.send(chunk) } catch (error: Throwable) {
                chunk.fill(0)
                currentCoroutineContext().ensureActive()
                playback.done.await() // Surface an audio-worker failure rather than mistaking it for user cancellation.
                throw error
            }
            offset = end
        }
    }

    override suspend fun finishAndDrain() {
        val playback = current.get() ?: error("PCM playback has not started")
        playback.queue.close()
        playback.done.await()
        current.compareAndSet(playback, null)
    }

    override fun stop() {
        generation.incrementAndGet()
        current.getAndSet(null)?.cancel()
    }

    private class Playback(val format: SpeechPcmFormat, val sink: PcmAudioSink) {
        val queue = Channel<ByteArray>(100, onUndeliveredElement = { it.fill(0) })
        val done = CompletableDeferred<Unit>()
        private val released = AtomicBoolean()
        var worker: Job? = null
        fun release() { if (released.compareAndSet(false, true)) sink.release() }
        fun cancel() {
            queue.cancel(); worker?.cancel(); release()
            done.cancel()
        }
    }

    private class AudioTrackSink(format: SpeechPcmFormat) : PcmAudioSink {
        private val track: AudioTrack
        init {
            val minimum = AudioTrack.getMinBufferSize(format.sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "PCM format unavailable on this device" }
            track = AudioTrack.Builder().setAudioAttributes(VoiceAudioSession.attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(format.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, format.bytesPerSecond / 10)).build()
            if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); error("PCM playback initialization failed") }
            // Streaming replies can be shorter than Android's default buffer-fill threshold.
            // API 31+ lets a single frame start playback, so short replies can actually drain.
            track.setStartThresholdInFrames(1)
        }
        override fun play() = track.play()
        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            track.write(bytes, offset, length, AudioTrack.WRITE_BLOCKING)
        override fun playedFrames() = track.playbackHeadPosition.toLong() and 0xffff_ffffL
        override fun release() {
            runCatching { track.pause() }; runCatching { track.flush() }; track.release()
        }
    }
}
