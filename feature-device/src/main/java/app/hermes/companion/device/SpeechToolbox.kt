package app.hermes.companion.device

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * L0 text-to-speech: make the phone say something out loud.
 *
 * No permission at all, and it works with the screen off — which makes it the cheapest way for
 * an agent to reach a human who is near the phone but not looking at it.
 *
 * Deliberately does **not** reuse `TextToSpeechEngine`: that one is a streaming engine owned by
 * the voice loop, with a clause buffer and `feedToken`/`finishFeed` lifecycle. Pushing tool
 * speech through it would interleave with a live voice turn and corrupt that buffer. This owns
 * its own [TextToSpeech], created lazily and kept for the process.
 *
 * The engine is created on the main looper because its init and utterance callbacks are
 * delivered there; every entry point here is safe to call from a background dispatcher.
 */
object SpeechToolbox {

    private const val INIT_TIMEOUT_MS = 8_000L
    private const val MAX_WAIT_MS = 120_000L
    const val MAX_CHARS = 4_000

    private val seq = AtomicInteger(0)
    private val lock = Any()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    /** Init failure reason, so the tool can say *why* rather than just failing. */
    @Volatile
    private var initError: String? = null

    class SpeechError(message: String) : Exception(message)

    /** Distinct from a missing argument: the text is present, just past the cap. */
    class TextTooLong(message: String) : Exception(message)

    private fun engine(ctx: Context): TextToSpeech {
        synchronized(lock) {
            tts?.let { if (ready) return it }
        }
        val latch = CountDownLatch(1)
        // Create on the main looper: TextToSpeech delivers its init callback there, and a
        // background thread with no Looper would never hear it.
        Handler(Looper.getMainLooper()).post {
            synchronized(lock) {
                if (tts == null) {
                    tts = TextToSpeech(ctx.applicationContext) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            val e = tts
                            if (e == null) {
                                initError = "engine went away during init"
                            } else {
                                val lang = e.setLanguage(Locale.getDefault())
                                if (lang == TextToSpeech.LANG_MISSING_DATA ||
                                    lang == TextToSpeech.LANG_NOT_SUPPORTED
                                ) {
                                    e.language = Locale.US
                                }
                                ready = true
                                initError = null
                            }
                        } else {
                            initError = "no usable text-to-speech engine on this phone"
                        }
                        latch.countDown()
                    }
                } else {
                    // Already constructed; either ready or its init already failed.
                    latch.countDown()
                }
            }
        }
        if (!latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw SpeechError("text-to-speech engine did not start within ${INIT_TIMEOUT_MS}ms")
        }
        val e = tts
        if (e == null || !ready) throw SpeechError(initError ?: "text-to-speech unavailable")
        return e
    }

    /**
     * Speak [text].
     *
     * @param queue append to any speech already playing instead of replacing it.
     * @param rate 0.5..2.0 speech rate; null leaves the engine default.
     * @param pitch 0.5..2.0; null leaves the default.
     * @param waitMs block until the utterance finishes, up to this many ms. 0 returns as soon
     *   as it is enqueued — the phone keeps speaking after the tool call returns.
     * @return what happened, including the music volume, because TTS plays on the music stream
     *   and a muted phone speaks silently — a failure an agent would otherwise never see.
     */
    fun speak(
        ctx: Context,
        text: String,
        queue: Boolean = false,
        rate: Float? = null,
        pitch: Float? = null,
        waitMs: Long = 0L,
    ): Map<String, Any?> {
        val body = text.trim()
        if (body.isEmpty()) throw IllegalArgumentException("text required")
        if (body.length > MAX_CHARS) {
            throw TextTooLong("text is ${body.length} chars; cap is $MAX_CHARS — send it in parts")
        }
        val e = engine(ctx)
        rate?.let { e.setSpeechRate(it.coerceIn(0.5f, 2.0f)) }
        pitch?.let { e.setPitch(it.coerceIn(0.5f, 2.0f)) }

        val id = "tool_${seq.incrementAndGet()}"
        val done = CountDownLatch(1)
        var failed: String? = null
        if (waitMs > 0) {
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) done.countDown()
                }

                @Deprecated("Kept for the pre-API-33 signature; the framework may call either.")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) {
                        failed = "engine reported an error"
                        done.countDown()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id) {
                        failed = "engine error $errorCode"
                        done.countDown()
                    }
                }
            })
        }

        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        val mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val rc = e.speak(body, mode, params, id)
        if (rc != TextToSpeech.SUCCESS) throw SpeechError("engine refused the utterance")

        var completed: Boolean? = null
        if (waitMs > 0) {
            completed = done.await(waitMs.coerceAtMost(MAX_WAIT_MS), TimeUnit.MILLISECONDS)
            failed?.let { throw SpeechError(it) }
        }

        val am = ctx.getSystemService(AudioManager::class.java)
        val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        return buildMap {
            put("utterance_id", id)
            put("chars", body.length)
            put("queued", queue)
            put("music_volume", vol)
            put("music_volume_max", max)
            // The one silent-failure mode worth naming: it "worked" but nobody could hear it.
            put("audible", vol > 0)
            if (completed != null) put("completed", completed)
        }
    }

    /** Halt anything playing or queued. Returns whether an engine existed to stop. */
    fun stop(): Boolean {
        val e = tts ?: return false
        runCatching { e.stop() }
        return true
    }

    /** Engine state and the languages it can speak, for diagnosing a silent phone. */
    fun status(ctx: Context): Map<String, Any?> {
        val available = runCatching { engine(ctx) }.isSuccess
        val e = tts
        val langs = runCatching {
            e?.availableLanguages?.map { it.toLanguageTag() }?.sorted()?.take(40) ?: emptyList()
        }.getOrDefault(emptyList())
        val am = ctx.getSystemService(AudioManager::class.java)
        val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        return mapOf(
            "available" to available,
            "error" to initError,
            "default_locale" to Locale.getDefault().toLanguageTag(),
            "languages" to langs.joinToString(","),
            "music_volume" to vol,
            "audible" to (vol > 0),
        )
    }

    fun errorCode(t: Throwable?): String = when (t) {
        is TextTooLong -> "text_too_long"
        is IllegalArgumentException -> "missing_args"
        is SpeechError -> "tts_unavailable"
        else -> "speak_failed"
    }
}
