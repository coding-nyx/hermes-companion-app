package app.hermes.companion.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class NativeAudioContractTest {
    @Test fun nativeSpeechUsesOnlyTextAndProfileAndDecodesReturnedAudio() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"data_url":"data:audio/wav;base64,AQID","provider":"local"}"""))
            val speech = DashboardClient().synthesizeSpeech(server.url("/").toString().trimEnd('/'), "my profile", "hello\tworld")
            assertArrayEquals(byteArrayOf(1,2,3), speech.bytes)
            assertEquals("audio/wav", speech.mime)
            val r = server.takeRequest()
            assertEquals("/api/audio/speak", r.requestUrl!!.encodedPath)
            assertEquals("my profile", r.requestUrl!!.queryParameter("profile"))
            val p = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
            assertEquals(setOf("text"), p.keys)
            assertEquals("hello\tworld", p["text"]!!.jsonPrimitive.content)
        }
    }
    @Test fun emptySpeechIsSilenceAndNeverBecomesAnInventedTranscript() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"transcript":"","provider":"local"}"""))
            val text = DashboardClient().transcribeAudio(server.url("/").toString().trimEnd('/'), "coder", byteArrayOf(1,2), "audio/wav")
            assertEquals("", text.text)
            val r = server.takeRequest()
            assertEquals("coder", r.requestUrl!!.queryParameter("profile"))
            val p = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
            assertEquals("data:audio/wav;base64,AQI=", p["data_url"]!!.jsonPrimitive.content)
            assertEquals(setOf("data_url", "mime_type"), p.keys)
        }
    }
    @Test fun invalidPlaybackPayloadsFailBeforeAnyPlayback() = runBlocking {
        for (data in listOf("", "data:audio/wav;base64,???", "data:text/html;base64,AQID", "data:audio/wav;base64,")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"ok":true,"data_url":"$data"}"""))
                val error = runCatching { DashboardClient().synthesizeSpeech(server.url("/").toString().trimEnd('/'), "coder", "text") }.exceptionOrNull()
                assertEquals("audio_invalid", (error as DashboardException).code)
            }
        }
    }
    @Test fun callReadinessUsesNonSecretNativeStatusWithoutRequestingASession() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"mode":"chained","available":false,"reason":"no API key"}"""))
            val s = DashboardClient().voiceLiveStatus(server.url("/").toString().trimEnd('/'), "coder")
            assertEquals(false, s.available)
            assertEquals("no API key", s.reason)
            assertEquals("/api/audio/voice-live/status?profile=coder", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun authorizationFailureDoesNotFallBackToPluginAudio() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val error = runCatching { DashboardClient().synthesizeSpeech(server.url("/").toString().trimEnd('/'), "coder", "text") }.exceptionOrNull()
            assertEquals("http_401", (error as DashboardException).code)
            assertEquals(1, server.requestCount)
        }
    }
}
