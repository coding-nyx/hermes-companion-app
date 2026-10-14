package app.hermes.companion.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.*
import app.hermes.companion.model.SynthesizedSpeech
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlin.math.sqrt

/** Bounded mono PCM recording. Audio stays in memory and is cleared after transcription. */
class AndroidSpeechCapture(private val context: Context) : SpeechCapture {
    private val generation = AtomicLong()
    private val current = AtomicReference<AudioRecord?>()
    override suspend fun record(onReady: () -> Unit, onActivity: () -> Unit): ByteArray? {
        val epoch = generation.get()
        return withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission denied")
            }
            val rate = 16_000
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "PCM format unavailable" }
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum * 2)
            val pcm = ByteArray(16_000 * 2 * 30)
            var count = 0
            val samples = ShortArray(320)
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                if (generation.get() != epoch) return@withContext null
                current.set(recorder)
                recorder.startRecording()
                withContext(Dispatchers.Main.immediate) { if (generation.get() == epoch) onReady() }
                val window = SpeechWindow()
                while (generation.get() == epoch && isActive) {
                    val n = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    ensureActive()
                    if (generation.get() != epoch) return@withContext null
                    check(n > 0) { "Microphone read failed" }
                    check(recorder.activeRecordingConfiguration?.isClientSilenced != true) { "Microphone silenced" }
                    check(count + n * 2 <= pcm.size)
                    for (j in 0 until n) {
                        pcm[count++] = samples[j].toByte()
                        pcm[count++] = (samples[j].toInt() shr 8).toByte()
                    }
                    val loud = sqrt((0 until n).sumOf { samples[it].toDouble() * samples[it] } / n) >= 600
                    if (loud) withContext(Dispatchers.Main.immediate) { if (generation.get() == epoch) onActivity() }
                    if (window.feed(n, loud)) break
                }
                ensureActive()
                if (generation.get() != epoch || !window.hasSpeech) return@withContext null
                val bytes = pcm.copyOf(count)
                try { wav(bytes, rate) } finally { bytes.fill(0) }
            } finally {
                current.compareAndSet(recorder, null)
                runCatching { recorder.stop() }; recorder.release()
                samples.fill(0)
                pcm.fill(0)
            }
        }
    }
    override fun cancel() { generation.incrementAndGet(); current.getAndSet(null)?.let { runCatching { it.stop() } } }

    internal fun wav(pcm: ByteArray, rate: Int): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()
}

/** Segmentation only; acoustic interruption and adaptive route policy belong to phase two. */
internal class SpeechWindow {
    private var total = 0
    private var speech = 0
    private var quiet = 0
    val hasSpeech get() = speech >= 1_920 // 120 ms of voiced samples.
    fun feed(samples: Int, loud: Boolean): Boolean {
        total += samples
        if (loud) { speech += samples; quiet = 0 } else quiet += samples
        return total >= 16_000 * 30 || (!hasSpeech && total >= 16_000 * 15) || (hasSpeech && quiet >= 14_400)
    }
}

/** MediaDataSource avoids writing provider audio to disk. All player operations run on main. */
class AndroidSpeechPlayer : SpeechPlayer {
    private var current: MediaPlayer? = null
    private var source: MemoryAudio? = null
    private var generation = 0L
    override fun play(audio: SynthesizedSpeech, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit) {
        stop()
        val epoch = generation
        val media = MediaPlayer()
        current = media
        try {
            source = MemoryAudio(audio.bytes.copyOf())
            media.setAudioAttributes(VoiceAudioSession.attributes)
            media.setDataSource(source!!)
            media.setOnPreparedListener { if (epoch == generation) {
                try { it.start(); onStart() } catch (_: RuntimeException) { stop(); onError() }
            } }
            media.setOnCompletionListener { if (epoch == generation) { stop(); onDone() } }
            media.setOnErrorListener { _, _, _ -> if (epoch == generation) { stop(); onError() }; true }
            media.prepareAsync()
        } catch (_: Exception) { stop(); onError() }
    }
    override fun stop() {
        generation++
        current?.let { runCatching { it.release() } }; current = null
        source?.close(); source = null
    }
    private class MemoryAudio(private val bytes: ByteArray) : MediaDataSource() {
        override fun getSize() = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position < 0 || position >= bytes.size) return -1
            val n = minOf(size, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }
        override fun close() { bytes.fill(0) }
    }
}
