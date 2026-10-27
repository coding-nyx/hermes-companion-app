package app.hermes.desk

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.voice.VoiceAudioSession
import app.hermes.desk.feature.DeskBinding
import app.hermes.desk.voice.AndroidSpeechPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in host speech roundtrip using generated audio, never room microphone input. */
class DeskHostSpeechAcceptanceTest {
    @get:Rule(order = 0) val quiet = DeskQuietEntryRule()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<DeskActivity>()
    @Test fun configuredHermesTranscriptionAndVoiceUseTheSavedHost() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hermes_speech") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = rule.activity
        val store = DeskStore(context)
        val connection = requireNotNull(store.connection())
        val profile = store.profile(connection.origin)
        val binding = DeskBinding(connection.origin, profile, store.session(connection.origin, profile))
        val backend = HermesBackend()
        var tts: TextToSpeech? = null
        val wav = File.createTempFile("desk-synthetic-", ".wav", context.cacheDir)
        try {
            withTimeout(30_000) { backend.connect(connection) }
            val speech = backend.speech(binding)
            val initialized = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val status = AtomicInteger(-1)
            instrument.runOnMainSync { tts = TextToSpeech(context) { status.set(it); initialized.countDown() } }
            assertTrue(initialized.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status.get())
            instrument.runOnMainSync {
                tts!!.language = Locale.US
                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { status.set(0); completed.countDown() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { status.set(-1); completed.countDown() }
                })
                status.set(-1)
                assertEquals(TextToSpeech.SUCCESS, tts!!.synthesizeToFile("Hello desk companion. This is a synthetic speech test.", Bundle(), wav, "desk-fixture"))
            }
            assertTrue(completed.await(30, TimeUnit.SECONDS))
            assertEquals(0, status.get())
            val bytes = wav.readBytes()
            val transcript = try { withTimeout(60_000) { speech.transcribe(bytes) } } finally { bytes.fill(0) }
            assertTrue("Host returned no transcript for generated speech", transcript.isNotBlank())
            val audio = withTimeout(60_000) { speech.synthesize("Your desk companion is ready.") }
            assertTrue(audio.bytes.isNotEmpty())
            val played = CountDownLatch(1)
            val audioSession = VoiceAudioSession(context)
            val player = AndroidSpeechPlayer()
            status.set(-1)
            try {
                instrument.runOnMainSync {
                    assertTrue(audioSession.acquire { played.countDown() })
                    assertTrue(audioSession.useSpeaker(true))
                    player.play(audio, {}, { status.set(0); played.countDown() }, { played.countDown() })
                }
                assertTrue(played.await(30, TimeUnit.SECONDS))
                assertEquals(0, status.get())
            } finally {
                instrument.runOnMainSync { player.stop(); audioSession.release() }
                audio.bytes.fill(0)
            }
        } finally {
            instrument.runOnMainSync { tts?.shutdown() }
            wav.delete(); backend.close()
        }
    }
}
