package app.hermes.companion.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BootstrapRecoveryTest {
    @Test fun rotatedTokenRecoversReadWithinItsReverseProxyPrefix() = runBlocking {
        MockWebServer().use { host ->
            val origin = host.url("/proxy/hermes").toString()
            val api = DashboardClient().apply { bindOrigin(origin); sessionToken = "old" }
            host.enqueue(rejected()); host.enqueue(bootstrap("fresh")); host.enqueue(sessions())
            assertEquals(listOf("fixture"), api.listSessions(origin, "coder").map { it.id })
            val first = host.takeRequest(); val root = host.takeRequest(); val retry = host.takeRequest()
            assertEquals("old", first.getHeader("X-Hermes-Session-Token"))
            assertEquals("/proxy/hermes/", root.path)
            assertEquals(first.path, retry.path)
            assertEquals("fresh", retry.getHeader("X-Hermes-Session-Token"))
            assertEquals(3, host.requestCount)
        }
    }

    @Test fun unchangedOrMissingBootstrapPreservesTheOriginal401WithoutRetry() = runBlocking {
        for (html in listOf(bootstrap("old"), MockResponse().setBody("<html>Sign in</html>"))) {
            MockWebServer().use { host ->
                val origin = host.url("/").toString()
                val api = DashboardClient().apply { bindOrigin(origin); sessionToken = "old" }
                host.enqueue(rejected()); host.enqueue(html)
                assertEquals("http_401", failure { api.listSessions(origin, "coder") })
                assertEquals(2, host.requestCount)
            }
        }
    }

    @Test fun aSecond401StopsRecovery() = runBlocking {
        MockWebServer().use { host ->
            val origin = host.url("/").toString()
            val api = DashboardClient().apply { bindOrigin(origin); sessionToken = "old" }
            host.enqueue(rejected()); host.enqueue(bootstrap("fresh")); host.enqueue(rejected())
            assertEquals("http_401", failure { api.listSessions(origin, "coder") })
            assertEquals(3, host.requestCount)
        }
    }

    @Test fun missingCredentialsSurface401WithoutBootstrapDiscovery() = runBlocking {
        MockWebServer().use { host ->
            val origin = host.url("/").toString()
            val api = DashboardClient().apply { bindOrigin(origin) }
            host.enqueue(rejected())
            assertEquals("http_401", failure { api.listSessions(origin, "coder") })
            assertEquals(1, host.requestCount)
        }
    }

    @Test fun passwordHostsNeverAdoptLoopbackCredentialsAfter401() = runBlocking {
        MockWebServer().use { host ->
            val origin = host.url("/").toString()
            val api = DashboardClient().apply { bindOrigin(origin); useGated() }
            host.enqueue(rejected())
            assertEquals("http_401", failure { api.listSessions(origin, "coder") })
            assertEquals(1, host.requestCount)
        }
    }

    @Test fun pairingFailuresNeverRecoverWithDashboardCredentials() = runBlocking {
        MockWebServer().use { host ->
            val origin = host.url("/").toString()
            val api = DashboardClient().apply { bindOrigin(origin); sessionToken = "old"; companionAuth = "fixture:credential" }
            host.enqueue(rejected())
            assertEquals("http_401", failure { api.agentTools(origin) })
            assertEquals("Companion fixture:credential", host.takeRequest().getHeader("Authorization"))
            assertEquals(1, host.requestCount)
        }
    }

    @Test fun rejectedMutationsAreNotReplayed() = runBlocking {
        MockWebServer().use { host ->
            val origin = host.url("/").toString()
            val api = DashboardClient().apply { bindOrigin(origin); sessionToken = "old" }
            host.enqueue(rejected())
            assertEquals("http_401", failure { api.applyHermesUpdate(origin) })
            assertEquals("POST", host.takeRequest().method)
            assertEquals(1, host.requestCount)
        }
    }

    @Test fun concurrentRejectedReadsShareOneSuccessfulBootstrapRefresh() = runBlocking {
        MockWebServer().use { host ->
            val refreshes = AtomicInteger()
            host.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> { refreshes.incrementAndGet(); bootstrap("fresh") }
                    request.getHeader("X-Hermes-Session-Token") == "fresh" -> sessions()
                    else -> rejected()
                }
            }
            val origin = host.url("/").toString()
            val api = DashboardClient().apply { bindOrigin(origin); sessionToken = "old" }
            coroutineScope { (1..8).map { async { api.listSessions(origin, "coder") } }.awaitAll() }
                .forEach { assertEquals(listOf("fixture"), it.map { row -> row.id }) }
            assertEquals(1, refreshes.get())
        }
    }

    private suspend fun failure(block: suspend () -> Any?): String =
        (runCatching { block() }.exceptionOrNull() as DashboardException).code
    private fun rejected() = MockResponse().setResponseCode(401).setBody("{\"error\":\"unauthorized\"}")
    private fun bootstrap(token: String) = MockResponse().setBody("<script>window.__HERMES_SESSION_TOKEN__ = \"$token\";</script>")
    private fun sessions() = MockResponse().setBody("""{"sessions":[{"id":"fixture","profile":"coder","title":"fixture"}]}""")
}
