package app.hermes.companion.voice

import android.os.SystemClock
import android.util.Log

/** Local timing only: no transcript, audio, identifiers or provider credentials. */
internal class VoiceTimings {
    private var callStart = 0L
    private var turnStart = 0L
    private var firstText = false
    private var firstAudio = false
    fun start() { callStart = SystemClock.elapsedRealtime(); turnStart = callStart }
    fun ready() = record("capture_ready_ms", callStart)
    fun utterance() { turnStart = SystemClock.elapsedRealtime(); firstText = false; firstAudio = false }
    fun transcribed() = record("stt_ms", turnStart)
    fun text() { if (!firstText) { firstText = true; record("first_hermes_text_ms", turnStart) } }
    fun audio() { if (!firstAudio) { firstAudio = true; record("first_playback_callback_ms", turnStart) } }
    fun stopping(): Long = SystemClock.elapsedRealtime()
    fun stopped(since: Long) = record("playback_stop_call_ms", since)
    private fun record(name: String, since: Long) { Log.i("VoiceTiming", "$name=${SystemClock.elapsedRealtime() - since}") }
}
