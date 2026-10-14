package app.hermes.companion.voice

import android.app.KeyguardManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in spoken phrase test. Run this class alone when the operator is ready to speak. */
@RunWith(AndroidJUnit4::class)
class VoicePhraseDeviceTest {
    @Test fun operatorPhraseIsRecognizedWithoutSubmittingAnyChatTurn() {
        val i = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setShowWhenLocked(false)
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val done = CountDownLatch(1)
            val context = i.targetContext
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (keyguard.isKeyguardLocked && System.nanoTime() < deadline) Thread.sleep(100)
            assertFalse("Unlock the test phone with its normal authentication", keyguard.isKeyguardLocked)
            var result = ""
            var error = ""
            val input = VoiceInputManager(context)
            val audio = VoiceAudioSession(context)
            val cue = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            lateinit var output: TextToSpeechEngine
            i.runOnMainSync {
                assertTrue("Another call owns device audio", audio.acquire { done.countDown() })
                assertTrue("Speaker route unavailable", audio.useSpeaker(true))
                output = TextToSpeechEngine(context,
                    onAllCompleted = { i.targetContext.mainExecutor.execute {
                        input.startListening(onResult = { result = it; done.countDown() },
                            onError = { error = it; done.countDown() },
                            onStateChange = { ready -> if (ready) cue.startTone(ToneGenerator.TONE_PROP_BEEP, 150) })
                    } },
                    onError = { error = it; done.countDown() }, audioAttributes = VoiceAudioSession.attributes)
                output.feedToken("Microphone test. Wait for the beep, then say Hermes audio test one two three.")
                output.finishFeed()
            }
            try {
                assertTrue("No test utterance received", done.await(35, TimeUnit.SECONDS))
                assertTrue("Recognition failed: $error", error.isBlank())
                assertTrue("No recognized speech", result.isNotBlank())
                val normalized = result.lowercase()
                assertTrue("Test phrase was not recognized: $result", "audio" in normalized && "test" in normalized)
                android.util.Log.i("CompanionAudioTest", "Recognized operator test phrase: $result")
            } finally { i.runOnMainSync { input.stopListening(); output.shutdown(); cue.release(); audio.release() } }
        }
    }
}
