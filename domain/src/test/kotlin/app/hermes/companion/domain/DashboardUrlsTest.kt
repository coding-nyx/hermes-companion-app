package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUrlsTest {
    @Test
    fun profileRosterIsMachineLevel() {
        val url = DashboardUrls.machine("http://host:9119", "/api/profiles")
        assertEquals("http://host:9119/api/profiles", url)
        assertFalse(url.contains("profile="))
    }

    @Test
    fun restAlwaysCarriesProfile() {
        val url = DashboardUrls.rest("http://100.83.141.111:9119", "/api/sessions", "coder")
        assertEquals("http://100.83.141.111:9119/api/sessions?profile=coder", url)
        assertTrue(url.contains("profile=coder"))
    }

    @Test
    fun restDoesNotLeakAnotherProfile() {
        val url = DashboardUrls.rest("http://host:9119", "/api/sessions", "ops")
        assertFalse(url.contains("profile=coder"))
        assertTrue(url.endsWith("profile=ops"))
    }

    @Test
    fun wsCarriesProfileEvenWithTicket() {
        val url = DashboardUrls.ws("http://host:9119", "personal", ticket = "t1")
        assertTrue(url.startsWith("ws://host:9119/api/ws?"))
        assertTrue(url.contains("profile=personal"))
        assertTrue(url.contains("ticket=t1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun restWithoutProfileThrows() {
        DashboardUrls.rest("http://host:9119", "/api/sessions", "")
    }

    @Test
    fun chatStreamIsProfileScoped() {
        val url = DashboardUrls.rest(
            "http://host:9119",
            "/api/sessions/sess-cod-1/chat/stream",
            "coder",
        )
        assertEquals("http://host:9119/api/sessions/sess-cod-1/chat/stream?profile=coder", url)
        assertFalse(url.contains("profile=ops"))
    }
}
