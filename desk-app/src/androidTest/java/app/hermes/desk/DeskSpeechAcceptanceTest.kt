package app.hermes.desk

import android.speech.SpeechRecognizer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.voice.TextToSpeechEngine
import app.hermes.companion.voice.VoiceAudioSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicitly opted-in audible playback; never records the room microphone. */
class DeskSpeechAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<DeskActivity>()
    @Test fun systemSpeechStartsAndCompletesOnTheTabletSpeaker() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("desk_speech") == "true")
        val started = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        var output: TextToSpeechEngine? = null
        val audio = VoiceAudioSession(rule.activity)
        try {
            rule.runOnUiThread {
                println("DESK_SPEECH recognition_available=${SpeechRecognizer.isRecognitionAvailable(rule.activity)} on_device_recognition=${SpeechRecognizer.isOnDeviceRecognitionAvailable(rule.activity)}")
                assertTrue("Another audio session owns the tablet", audio.acquire { failure.set("Audio focus lost") })
                assertTrue("Tablet speaker route unavailable", audio.useSpeaker(true))
                output = TextToSpeechEngine(rule.activity, onStart = { started.set(true) }, onAllCompleted = { finished.set(true) },
                    onError = { failure.set(it) }, audioAttributes = VoiceAudioSession.attributes)
                output!!.feedToken("Hello. Your desk companion is connected to lab.")
                output!!.finishFeed()
            }
            rule.waitUntil(30_000) { finished.get() || failure.get() != null }
            assertNull(failure.get())
            assertTrue("Speech playback never started", started.get())
            assertTrue("Speech playback did not complete", finished.get())
        } finally {
            rule.runOnUiThread { output?.shutdown(); audio.release() }
        }
    }
}
