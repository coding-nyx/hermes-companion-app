package app.hermes.companion.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.remote.DashboardClient
import kotlinx.coroutines.runBlocking
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
        val status = client.voiceLiveStatus(origin, profile)
        assertNotNull("Host omitted voice readiness", status.available)
        val speech = client.synthesizeSpeech(origin, profile, "Hermes audio test. One two three.")
        assertTrue("Host returned no usable audio", speech.bytes.size > 512)
        val transcript = client.transcribeAudio(origin, profile, speech.bytes, speech.mime)
        assertTrue("Audio test phrase missing from transcript", "audio" in transcript.text.lowercase())
        android.util.Log.i("CompanionAudioTest", "Native speech=${speech.provider}, transcription=${transcript.provider}, live=${status.available}")
        Unit
    }
}
