package app.hermes.companion.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HostCredentialIsolationTest {
    @Test fun notificationWakeStreamDoesNotReceiveDashboardCredentials() = runBlocking {
        MockWebServer().use { host -> MockWebServer().use { external ->
            val origin = host.url("/").toString().trimEnd('/')
            val client = DashboardClient().apply { bindOrigin(origin); sessionToken = "fixture-token"; companionAuth = "fixture:credential" }
            host.enqueue(MockResponse().setBody("{}").addHeader("Set-Cookie", "hermes_session=fixture-cookie; Path=/"))
            client.probe(origin); host.takeRequest()
            external.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody("data: fixture-wake\n\n"))
            assertEquals("fixture-wake", withTimeout(5_000) { client.wakeEvents(external.url("/topic/sse").toString()).first() })
            assertUncredentialed(external.takeRequest())
        } }
    }

    @Test fun externalMediaAndEveryRedirectHopReceiveNoHostHeadersOrCookies() = runBlocking {
        MockWebServer().use { host -> MockWebServer().use { external ->
            val origin = host.url("/").toString().trimEnd('/')
            val client = DashboardClient().apply { bindOrigin(origin); sessionToken = "fixture-token"; companionAuth = "fixture-device:fixture-credential" }
            host.enqueue(MockResponse().addHeader("Set-Cookie", "hermes_session=fixture-cookie; Path=/").setBody("{}"))
            client.probe(origin); host.takeRequest()
            external.enqueue(MockResponse().addHeader("Set-Cookie", "hermes_session=foreign-cookie; Path=/").setBody("foreign"))
            assertEquals("foreign", client.fetchBytes(external.url("/companion/media").toString()).decodeToString())
            assertUncredentialed(external.takeRequest())
            host.enqueue(MockResponse().setResponseCode(302).addHeader("Location", external.url("/picture")))
            external.enqueue(MockResponse().setResponseCode(302).addHeader("Location", external.url("/picture-final")))
            external.enqueue(MockResponse().setBody("picture"))
            assertEquals("picture", client.fetchBytes(host.url("/media/redirect").toString()).decodeToString())
            val first = host.takeRequest()
            assertEquals("Bearer fixture-token", first.getHeader("Authorization"))
            assertEquals("hermes_session=fixture-cookie", first.getHeader("Cookie"))
            assertUncredentialed(external.takeRequest()); assertUncredentialed(external.takeRequest())
            host.enqueue(MockResponse().setBody("native"))
            client.fetchBytes(host.url("/companion/media").toString())
            val last = host.takeRequest()
            assertEquals("Companion fixture-device:fixture-credential", last.getHeader("Authorization"))
            assertEquals("hermes_session=fixture-cookie", last.getHeader("Cookie"))
        } }
    }

    @Test fun reverseProxyDashboardsHaveIndependentClientsAndRejectCrossScopeApiUse() = runBlocking {
        MockWebServer().use { server ->
            val root = server.url("/").toString().trimEnd('/')
            val a = "$root/proxy/a"; val b = "$root/proxy/b"
            val pool = HostClientPool(); val first = pool.forOrigin(a); val second = pool.forOrigin(b)
            assertNotSame(first, second); assertSame(first, pool.forOrigin("$a/"))
            first.sessionToken = "a-token"; second.sessionToken = "b-token"; first.companionAuth = "a:credential"
            server.enqueue(MockResponse().addHeader("Set-Cookie", "hermes_session=a-cookie; Path=/").setBody("{}"))
            first.probe(a); assertEquals("Bearer a-token", server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("outside"))
            first.fetchBytes(server.url("/proxy/ab/media").toString()); assertUncredentialed(server.takeRequest())
            server.enqueue(MockResponse().setBody("other dashboard"))
            first.fetchBytes(server.url("/proxy/b/companion/media").toString()); assertUncredentialed(server.takeRequest())
            server.enqueue(MockResponse().setBody("{}"))
            second.probe(b); val own = server.takeRequest()
            assertEquals("Bearer b-token", own.getHeader("Authorization")); assertNull(own.getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("plugin"))
            first.fetchBytes(server.url("/proxy/a/companion/media").toString())
            assertEquals("Companion a:credential", server.takeRequest().getHeader("Authorization"))
            val failure = runCatching { first.probe(b) }.exceptionOrNull()
            assertEquals("host_scope", (failure as DashboardException).code)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            pool.evict(a); assertNull(pool.existing(a)); assertSame(second, pool.existing(b))
        }
    }

    @Test fun mutationAndCredentialBearingWebsocketRedirectsAreNotFollowed() = runBlocking {
        MockWebServer().use { host -> MockWebServer().use { external ->
            val origin = host.url("/").toString().trimEnd('/')
            val client = DashboardClient().apply { bindOrigin(origin) }
            host.enqueue(MockResponse().setResponseCode(307).addHeader("Location", external.url("/auth/password-login")))
            assertTrue(runCatching { client.passwordLogin(origin, "fixture", "fixture-password") }.isFailure)
            assertEquals("POST", host.takeRequest().method)
            client.useLoopback(); client.sessionToken = "fixture-token"
            host.enqueue(MockResponse().setResponseCode(302).addHeader("Location", external.url("/api/ws?token=fixture-token")))
            try { assertTrue(runCatching { client.wsHello(origin, "fixture") }.isFailure) } finally { client.closeRpc() }
            assertTrue(host.takeRequest().path.orEmpty().contains("token=fixture-token"))
            assertNull(external.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }

    private fun assertUncredentialed(request: okhttp3.mockwebserver.RecordedRequest) {
        for (header in listOf("Authorization", "X-Hermes-Session-Token", "Cookie")) assertNull(header, request.getHeader(header))
    }
}
