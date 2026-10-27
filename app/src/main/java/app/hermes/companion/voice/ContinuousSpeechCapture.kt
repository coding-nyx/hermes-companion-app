package app.hermes.companion.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.*
import kotlin.math.sqrt

/** One microphone owner, including while transcription and speaker playback are in progress. */
interface CallSpeechCapture {
    var muted: Boolean
    fun start(scope: CoroutineScope, onReady: (Boolean) -> Unit, onSpeech: () -> Unit,
        onUtterance: (ByteArray) -> Unit, onError: (String) -> Unit)
    fun stop(): Job?
}

class ContinuousSpeechCapture(private val pacingMs: Int = 900) : CallSpeechCapture {
    @Volatile private var recorder: AudioRecord? = null
    @Volatile override var muted: Boolean = false
    private var job: Job? = null

    override fun start(scope: CoroutineScope, onReady: (Boolean) -> Unit, onSpeech: () -> Unit,
        onUtterance: (ByteArray) -> Unit, onError: (String) -> Unit) {
        check(job == null)
        job = scope.launch(Dispatchers.IO) {
            var audio: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            val samples = ShortArray(320)
            val detector = ContinuousUtteranceDetector(pacingMs)
            try {
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                audio = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                recorder = audio
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(audio.audioSessionId)?.also { it.enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(audio.audioSessionId)?.also { it.enabled = true }
                var recording = !muted
                if (recording) audio.startRecording()
                withContext(Dispatchers.Main.immediate) { onReady(recording) }
                while (isActive) {
                    if (muted) {
                        if (recording) { audio.stop(); recording = false; withContext(Dispatchers.Main.immediate) { onReady(false) } }
                        detector.reset(); delay(50); continue
                    }
                    if (!recording) { audio.startRecording(); recording = true; withContext(Dispatchers.Main.immediate) { onReady(true) } }
                    val n = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    ensureActive()
                    check(n > 0) { "Microphone recording failed" }
                    check(audio.activeRecordingConfiguration?.isClientSilenced != true) { "Another app silenced the microphone" }
                    if (muted) { detector.reset(); continue }
                    val frame = detector.feed(samples, n)
                    if (frame.started) withContext(Dispatchers.Main.immediate) { if (!muted) onSpeech() }
                    frame.pcm?.let { pcm ->
                        android.util.Log.i("VoiceTiming", "endpoint_silence_ms=${frame.endpointSilenceMs}")
                        val wav = wavPcm16(pcm)
                        pcm.fill(0)
                        withContext(Dispatchers.Main.immediate) {
                            if (!muted) onUtterance(wav) else wav.fill(0)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { withContext(Dispatchers.Main.immediate) { onError(e.message ?: "Microphone unavailable") } }
            finally {
                samples.fill(0); detector.reset()
                recorder = null
                aec?.release(); ns?.release()
                audio?.let { runCatching { it.stop() }; it.release() }
                withContext(NonCancellable + Dispatchers.Main.immediate) { onReady(false) }
            }
        }
    }
    override fun stop(): Job? {
        val old = job; old?.cancel(); job = null
        recorder?.let { runCatching { it.stop() } }
        return old
    }
}

internal data class CaptureFrame(val started: Boolean = false, val pcm: ByteArray? = null, val endpointSilenceMs: Int = 0)

/** 20 ms PCM frames, adaptive room floor, 300 ms pre-roll and bounded two-minute utterances. */
internal class ContinuousUtteranceDetector(pacingMs: Int = 900) {
    private val quietLimit = 16 * pacingMs.also { require(it in setOf(600, 900, 1400)) }
    private val preRoll = ByteArray(9600)
    private var preCount = 0
    private var preHead = 0
    private var floor = 120.0
    private var voiced = 0
    private var quiet = 0
    private var current: ClearedPcmBuffer? = null
    fun feed(samples: ShortArray, count: Int = samples.size): CaptureFrame {
        require(count in 1..samples.size)
        val rms = sqrt((0 until count).sumOf { samples[it].toDouble() * samples[it] } / count)
        val loud = rms >= maxOf(600.0, floor * 3)
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) { bytes[2*i] = samples[i].toByte(); bytes[2*i+1] = (samples[i].toInt() shr 8).toByte() }
        var started = false
        if (current == null) {
            if (!loud) floor = floor * .98 + rms * .02
            for (byte in bytes) { preRoll[preHead] = byte; preHead = (preHead + 1) % preRoll.size; preCount = minOf(preRoll.size, preCount + 1) }
            voiced = if (loud) voiced + count else 0
            if (voiced >= 1920) {
                current = ClearedPcmBuffer().apply {
                    val begin = (preHead - preCount + preRoll.size) % preRoll.size
                    repeat(preCount) { write(preRoll[(begin + it) % preRoll.size]) }
                }
                quiet = 0; started = true
            }
        } else {
            current!!.write(bytes)
            quiet = if (loud) 0 else quiet + count
        }
        bytes.fill(0)
        val output = current
        if (output != null && (quiet >= quietLimit || output.size >= 16000 * 2 * 120)) {
            val pcm = output.toByteArray()
            val endpointSilenceMs = quiet / 16
            reset()
            return CaptureFrame(started, pcm, endpointSilenceMs)
        }
        return CaptureFrame(started)
    }
    fun reset() {
        current?.clear(); current = null
        preRoll.fill(0); preCount = 0; preHead = 0; voiced = 0; quiet = 0
    }
}

internal fun wavPcm16(pcm: ByteArray): ByteArray = java.nio.ByteBuffer.allocate(44 + pcm.size)
    .order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()

/** Fixed capacity avoids uncleared copies from a growable output stream. */
private class ClearedPcmBuffer {
    private val bytes = ByteArray(16000 * 2 * 120 + 640)
    var size = 0; private set
    fun write(byte: Byte) { check(size < bytes.size); bytes[size++] = byte }
    fun write(value: ByteArray) { check(size + value.size <= bytes.size); value.copyInto(bytes, size); size += value.size }
    fun toByteArray() = bytes.copyOf(size)
    fun clear() { bytes.fill(0); size = 0 }
}
