package app.hermes.companion.data.remote

import app.hermes.companion.model.CompanionAvailability
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NativeHostIntegrationTest {
    @Test fun nativeMetricsDoNotProbeOrUseThePlugin() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"cpu_percent":12.5,"cpu_count":4,"memory":{"total":1000,"used":300,"available":700,"percent":30},"disk":{"total":2000,"used":400,"free":1600},"os":"Linux","hostname":"lab","process":{"pid":42}}"""))
            val metrics = DashboardClient().getHostMetrics(server.url("/").toString().trimEnd('/'))
            assertEquals(12.5, metrics.cpu.percent, 0.01)
            assertEquals(300L, metrics.memory.usedBytes)
            assertEquals(42, metrics.hermes.pid)
            assertEquals("/api/system/stats", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun authorizationAndServerErrorsNeverFallBackToAnotherService() = runBlocking {
        for (code in listOf(401, 403, 500)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("""{"detail":"denied"}"""))
                val result = runCatching { DashboardClient().getHostMetrics(server.url("/").toString().trimEnd('/')) }
                assertEquals("http_$code", (result.exceptionOrNull() as DashboardException).code)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun legacyMetricsAreUsedOnlyAfterNativeRouteAndExtensionDiscovery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"relay":"ok","mode":"standalone"}"""))
            server.enqueue(MockResponse().setBody("""{"metrics":{"cpu":{"percent":9.0,"cores":2}}}"""))
            val metrics = DashboardClient().getHostMetrics(server.url("/").toString().trimEnd('/'))
            assertEquals(9.0, metrics.cpu.percent, 0.01)
            assertEquals(listOf("/api/system/stats", "/companion/health", "/companion/host/metrics"),
                List(3) { server.takeRequest().path })
        }
    }

    @Test fun missingNativeMetricsWithNoPluginPreservesTheNativeError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            val result = runCatching { DashboardClient().getHostMetrics(server.url("/").toString().trimEnd('/')) }
            assertEquals("http_404", (result.exceptionOrNull() as DashboardException).code)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun nativeSpaFallbackIsNotMistakenForAnInstalledExtension() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<!doctype html><html>Hermes</html>"))
            val client = DashboardClient()
            val origin = server.url("/").toString().trimEnd('/')
            repeat(2) { assertEquals(CompanionAvailability.UNAVAILABLE, client.companionAvailability(origin)) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun availabilityRecoversFromTransientErrorsAndCanBeRefreshed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"relay":"ok","mode":"proxy"}"""))
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            val client = DashboardClient()
            val origin = server.url("/").toString().trimEnd('/')
            assertEquals(CompanionAvailability.UNKNOWN, client.companionAvailability(origin))
            repeat(2) { assertEquals(CompanionAvailability.AVAILABLE, client.companionAvailability(origin)) }
            assertEquals(2, server.requestCount)
            client.invalidateCompanionAvailability(origin)
            assertEquals(CompanionAvailability.UNAVAILABLE, client.companionAvailability(origin))
            assertEquals(3, server.requestCount)
        }
    }
}
