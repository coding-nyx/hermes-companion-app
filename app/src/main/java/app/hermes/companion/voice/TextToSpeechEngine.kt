package app.hermes.companion.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.hermes.companion.domain.VoiceTextFilter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-time streaming Text-to-Speech engine.
 *
 * Buffers streaming LLM response tokens and synthesizes speech clause-by-clause
 * on punctuation boundaries ([.!?\n:]), so the user hears speech in real-time
 * without waiting for the full response to finish generating.
 * Supports zero-latency instant cancellation for barge-in.
 */
class TextToSpeechEngine(
    private val context: Context,
    private val onStart: () -> Unit = {},
    private val onAllCompleted: () -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    private var tts: TextToSpeech? = null
    private var isReady = false
    private val utteranceSeq = AtomicInteger(0)
    private var pendingBuffer = StringBuilder()
    private var endOfFeedReached = false
    private val queuedUtterances = mutableSetOf<String>()
    private val lock = Any()

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(Locale.getDefault())
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.language = Locale.US
                    }
                    engine.setSpeechRate(1.05f)
                    engine.setPitch(1.0f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            onStart()
                        }

                        override fun onDone(utteranceId: String?) {
                            synchronized(lock) {
                                utteranceId?.let { queuedUtterances.remove(it) }
                                if (endOfFeedReached && queuedUtterances.isEmpty()) {
                                    onAllCompleted()
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            synchronized(lock) {
                                utteranceId?.let { queuedUtterances.remove(it) }
                                if (endOfFeedReached && queuedUtterances.isEmpty()) {
                                    onAllCompleted()
                                }
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            onError("TTS error code: $errorCode")
                            synchronized(lock) {
                                utteranceId?.let { queuedUtterances.remove(it) }
                                if (endOfFeedReached && queuedUtterances.isEmpty()) {
                                    onAllCompleted()
                                }
                            }
                        }
                    })
                    synchronized(lock) {
                        isReady = true
                        if (pendingBuffer.isNotEmpty()) {
                            processBuffer(flushAll = endOfFeedReached)
                            if (endOfFeedReached && queuedUtterances.isEmpty()) {
                                onAllCompleted()
                            }
                        }
                    }
                }
            } else {
                onError("TextToSpeech initialization failed: $status")
            }
        }
    }

    /**
     * Feed an incremental text delta from the streaming LLM turn.
     * Extracts completed clauses on punctuation boundaries and queues them to speak.
     */
    fun feedToken(delta: String) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            pendingBuffer.append(delta)
            if (isReady) {
                processBuffer(flushAll = false)
            }
        }
    }

    /**
     * Signals that the assistant has finished generating the full turn.
     * Flushes any remaining text in the buffer and marks completion.
     */
    fun finishFeed() {
        synchronized(lock) {
            endOfFeedReached = true
            if (isReady) {
                processBuffer(flushAll = true)
                if (queuedUtterances.isEmpty()) {
                    onAllCompleted()
                }
            }
        }
    }

    private fun processBuffer(flushAll: Boolean) {
        val text = pendingBuffer.toString()
        if (text.isBlank()) {
            pendingBuffer.clear()
            return
        }

        if (flushAll) {
            speakClause(text.trim())
            pendingBuffer.clear()
            return
        }

        while (true) {
            val current = pendingBuffer.toString()
            val extracted = VoiceTextFilter.extractNextClause(current) ?: break
            val (clause, remaining) = extracted
            pendingBuffer.clear()
            pendingBuffer.append(remaining)
            if (clause.isNotBlank()) {
                speakClause(clause)
            }
        }
    }

    private fun speakClause(clause: String) {
        val cleaned = VoiceTextFilter.cleanForSpeech(clause)
        if (cleaned.isBlank()) return
        val id = "u_${utteranceSeq.incrementAndGet()}"
        queuedUtterances.add(id)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        tts?.speak(cleaned, TextToSpeech.QUEUE_ADD, params, id)
    }

    /**
     * Instantly halt any playing or queued speech.
     * Used for barge-in when the user speaks or interrupts.
     */
    fun stop() {
        synchronized(lock) {
            pendingBuffer.clear()
            endOfFeedReached = false
            queuedUtterances.clear()
            tts?.stop()
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
