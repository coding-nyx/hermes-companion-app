package app.hermes.companion.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class VoiceSettingsContractTest {
    private val settings = """{"ok":true,"version":1,"fields":[{"key":"tts.provider","value":"edge","options":["edge"],"type":"string"}],"live":{"mode":"chained","available":false,"reason":"No configured key"},"streaming":true,"snapshots":true}"""
    @Test fun cleanNativeHostsDiscoverThePluginAndKeepSnapshotRequestsInItsNamespace() = runBlocking {
        MockWebServer().use { server ->
            val token = "opaque_snapshot_".repeat(3)
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody(settings.replace("\"version\":1", "\"version\":1,\"api_base\":\"/api/plugins/hermes-companion\"")))
            server.enqueue(MockResponse().setBody("""{"ok":true,"snapshot":"$token"}"""))
            val api = DashboardClient(); val origin = server.url("/proxy").toString().trimEnd('/')
            assertTrue(api.audioSettings(origin, "knight").editable)
            assertEquals("/proxy/api/audio/settings?profile=knight", server.takeRequest().path)
            assertEquals("/proxy/api/plugins/hermes-companion/audio/settings?profile=knight", server.takeRequest().path)
            assertEquals(token, api.beginSpeechSnapshot(origin, "knight"))
            assertEquals("/proxy/api/plugins/hermes-companion/audio/settings/snapshot?profile=knight", server.takeRequest().path)
        }
    }
    @Test fun settingsReadAndPatchUseOnlyTheProfileScopedNonSecretInterface() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(settings)) }
            val api = DashboardClient(); val origin = server.url("/proxy").toString().trimEnd('/')
            val result = api.audioSettings(origin, "knight")
            assertEquals("edge", result.value("tts.provider")); assertTrue(result.snapshots)
            assertEquals("/proxy/api/audio/settings?profile=knight", server.takeRequest().path)
            api.saveAudioSettings(origin, "knight", mapOf("tts.provider" to "edge"), emptySet())
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/proxy/api/audio/settings?profile=knight", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("changes"), body.keys)
            assertEquals(setOf("tts.provider"), body["changes"]!!.jsonObject.keys)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun opaqueSnapshotIsBoundToTheSameProfileAndReleasedExplicitly() = runBlocking {
        MockWebServer().use { server ->
            val token = "opaque_snapshot_".repeat(3)
            server.enqueue(MockResponse().setBody("""{"ok":true,"snapshot":"$token"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"transcript":"","provider":"local"}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val api = DashboardClient(); val origin = server.url("/").toString().trimEnd('/')
            val snapshot = api.beginSpeechSnapshot(origin, "b")
            assertEquals("POST", server.takeRequest().method)
            api.transcribeAudio(origin, "b", byteArrayOf(1, 2), "audio/wav", snapshot)
            val request = server.takeRequest()
            assertEquals("b", request.requestUrl!!.queryParameter("profile"))
            assertEquals(token, request.requestUrl!!.queryParameter("voice_snapshot"))
            api.endSpeechSnapshot(origin, "b", snapshot)
            assertEquals("DELETE", server.takeRequest().method)
        }
    }
}
