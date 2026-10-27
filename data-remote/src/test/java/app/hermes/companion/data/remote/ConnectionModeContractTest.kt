package app.hermes.companion.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionModeContractTest {
    @Test fun modeUsesTheExactHostPrefixAndRequiresAnExplicitHostConfirmation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"relay":"ok","mode":"standalone","connection_settings":true,"mode_source":"host_startup"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"mode":"proxy"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":false,"mode":"standalone"}"""))
            val origin = server.url("/work").toString()
            val client = DashboardClient()
            assertTrue(client.companionHealth(origin).connectionSettings)
            client.setConnectionMode(origin, "proxy", "standalone")
            assertTrue(runCatching { client.setConnectionMode(origin, "proxy", "standalone") }.isFailure)
            assertEquals("/work/companion/health", server.takeRequest().path)
            val post = server.takeRequest()
            assertEquals("/work/companion/connection/mode", post.path)
            assertEquals("POST", post.method)
            assertEquals("""{"mode":"proxy","expected_mode":"standalone"}""", post.body.readUtf8())
        }
    }
}
