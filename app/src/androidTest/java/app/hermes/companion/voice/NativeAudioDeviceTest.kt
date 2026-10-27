package app.hermes.companion.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.remote.DashboardClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual configured host providers via the phone's native dashboard connection. */
@RunWith(AndroidJUnit4::class)
class NativeAudioDeviceTest {
    @Test fun nativeProviderRoundTripAndCallReadinessKeepKeysOnHost() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val origin = requireNotNull(args.getString("nativeOrigin")) { "Pass the temporary nativeOrigin test connection" }
        val profile = requireNotNull(args.getString("profile")) { "Pass the test profile" }
        val client = DashboardClient()
        client.useLoopback()
        assertNotNull("Native dashboard token unavailable", client.adoptLoopbackToken(origin))
        val settings = client.audioSettings(origin, profile)
        assertTrue(settings.editable)
        val status = client.voiceLiveStatus(origin, profile)
        assertNotNull("Host omitted voice readiness", status.available)
        val speech = client.synthesizeSpeech(origin, profile, "Hermes audio test. One two three.")
        assertTrue("Host returned no usable audio", speech.bytes.size > 512)
        val transcript = client.transcribeAudio(origin, profile, speech.bytes, speech.mime)
        assertTrue("Audio test phrase missing from transcript", "audio" in transcript.text.lowercase())
        // Exercise the actual phone streaming coordinator and Android AudioTrack,
        // including the same host/profile snapshot path used by a continuous call.
        val snapshot = client.beginSpeechSnapshot(origin, profile)
        try {
            coroutineScope {
                val completed = CompletableDeferred<Unit>()
                var started = false
                val output = StreamingHostSpeechOutput(this, NativeSpeechTransport(client, origin, profile, snapshot),
                    AndroidPcmSpeechPlayer(this), AndroidSpeechPlayer(),
                    SpeechCallbacks({ started = true }, { completed.complete(Unit) }, { completed.completeExceptionally(AssertionError(it)) }))
                try {
                    output.feedToken("Hermes streaming audio test. One two three. ")
                    output.finishFeed()
                    withTimeout(90000) { completed.await() }
                    assertTrue("Android never started playing the streamed reply", started)
                } finally { output.shutdown() }
            }
        } finally { client.endSpeechSnapshot(origin, profile, snapshot) }
        android.util.Log.i("CompanionAudioTest", "Native speech=${speech.provider}, transcription=${transcript.provider}, live=${status.available}")
        Unit
    }
}
