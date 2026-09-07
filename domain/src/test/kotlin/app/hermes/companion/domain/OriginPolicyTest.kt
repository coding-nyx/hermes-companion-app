package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginPolicyTest {
    @Test
    fun canonicalizeAddsHttpWhenSchemeMissing() {
        assertEquals("http://100.86.138.6:9120", OriginPolicy.canonicalize("100.86.138.6:9120"))
        assertEquals("http://raj-13766.mullet-pantone.ts.net:9120", OriginPolicy.canonicalize("http://raj-13766.mullet-pantone.ts.net:9120/"))
        assertEquals("", OriginPolicy.canonicalize("  "))
    }

    @Test
    fun loopbackIsThisPhone() {
        assertTrue(OriginPolicy.loopback("http://127.0.0.1:19219"))
        assertTrue(OriginPolicy.loopback("http://localhost:9119"))
        assertFalse(OriginPolicy.loopback("http://100.85.151.99:9120"))
        assertEquals("100.85.151.99", OriginPolicy.host("http://100.85.151.99:9120/"))
        assertEquals("fd7a:115c:a1e0::1", OriginPolicy.host("http://[fd7a:115c:a1e0::1]:9120/x"))
        assertEquals("hermes.ts.net", OriginPolicy.host("https://user:pw@hermes.ts.net/"))
    }

    @Test
    fun hintForLoopbackAndCleartext() {
        assertEquals(OriginPolicy.LOOPBACK_HINT, OriginPolicy.hint("http://127.0.0.1:19219"))
        assertTrue(OriginPolicy.hint("http://127.0.0.1:19219")!!.contains("9120"))
        assertEquals(OriginPolicy.CLEARTEXT_HINT, OriginPolicy.hint("http://100.85.151.99:9120"))
        assertEquals(OriginPolicy.CLEARTEXT_DENIED, OriginPolicy.hint("http://hermes.example.com:9120"))
        assertNull(OriginPolicy.hint("https://hermes.example.com"))
        assertEquals(OriginPolicy.EMPTY_HINT, OriginPolicy.hint(""))
        assertTrue(OriginPolicy.hint("")!!.contains("9120"))
    }

    @Test
    fun cleartextOnlyOnPrivateHosts() {
        // loopback + RFC 1918 + CGNAT (Tailscale) + link-local
        for (o in listOf(
            "http://127.0.0.1:9119",
            "http://localhost:9119",
            "http://10.0.0.5:9120",
            "http://172.16.0.1:9120",
            "http://172.31.255.254:9120",
            "http://192.168.1.20:9120",
            "http://169.254.10.10",
            "http://100.64.0.1:9120",
            "http://100.85.151.99:9120",
            "http://100.127.255.255:9120",
            "ws://100.85.151.99:9120/companion/device/ws",
            "http://[fd7a:115c:a1e0::1]:9120",
            "http://[fe80::1%25wlan0]:9120",
            "http://[::1]:9120",
            "http://hermes.ts.net:9120",
            "http://hermes-box.local:9120",
            "http://hermes-box:9120",
        )) {
            assertTrue(o, OriginPolicy.cleartextAllowed(o))
        }
        // public IPs / hostnames over http are refused
        for (o in listOf(
            "http://8.8.8.8:9120",
            "http://100.63.255.255:9120",
            "http://100.128.0.0:9120",
            "http://172.15.0.1:9120",
            "http://172.32.0.1:9120",
            "http://192.169.0.1:9120",
            "http://hermes.example.com:9120",
            "http://[2001:db8::1]:9120",
            "ws://evil.example/api/ws",
        )) {
            assertFalse(o, OriginPolicy.cleartextAllowed(o))
        }
        // TLS is always fine
        assertTrue(OriginPolicy.cleartextAllowed("https://hermes.example.com"))
        assertTrue(OriginPolicy.cleartextAllowed("wss://8.8.8.8/api/ws"))
    }

    @Test
    fun privateHostRejectsGarbage() {
        assertFalse(OriginPolicy.privateHost(""))
        assertFalse(OriginPolicy.privateHost("999.1.1.1"))
        assertFalse(OriginPolicy.privateHost("10.0.0"))
        assertTrue(OriginPolicy.privateHost("[fd00::5]"))
    }
}
