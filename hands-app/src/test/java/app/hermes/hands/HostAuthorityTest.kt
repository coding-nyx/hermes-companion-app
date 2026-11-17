package app.hermes.hands

import org.junit.Assert.*
import org.junit.Test

class HostAuthorityTest {
    @Test fun remoteAcceptsLiteralAndLocalDnsAuthoritiesWithTheActualPort() {
        listOf("192.168.1.8:8766", "100.105.213.54:8766", "phone.local:8766", "phone.tailnet.ts.net:8766",
            "phone:8766", "[::1]:8766", "[2001:db8::123]:8766").forEach { assertTrue(it, HostAuthority.accepts(it, 8766, true)) }
    }

    @Test fun rejectsAmbiguousMalformedOrMismatchedAuthorities() {
        listOf("https://phone.local:8766", "phone.local:8766/path", "user@phone.local:8766", "phone.local:8766#x",
            "phone.local:8765", "phone.local:0", "phone.local:65536", "phone.local:08766", "phone.local", "127.1:8766",
            "999.1.1.1:8766", "01.2.3.4:8766", "phone..local:8766", "-phone.local:8766", "phone.local :8766",
            "::1:8766", "[fe80::1%wlan0]:8766", "[not-ip]:8766").forEach { assertFalse(it, HostAuthority.accepts(it, 8766, true)) }
    }

    @Test fun loopbackAllowsForwardedPortsWithoutAcceptingRemoteHosts() {
        assertTrue(HostAuthority.accepts("127.0.0.1:18765", 8765, false))
        assertTrue(HostAuthority.accepts("localhost:50001", 8765, false))
        assertFalse(HostAuthority.accepts("192.168.1.8:8765", 8765, false))
        assertFalse(HostAuthority.accepts("localhost:65536", 8765, false))
    }

    @Test fun rateWindowsBoundPerPeerAndAddressChurnAndExpire() {
        var time = 0L
        val rate = RequestRate(1, 3) { time }
        assertTrue(rate.allow("one")); assertFalse(rate.allow("one"))
        assertTrue(rate.allow("two")); assertTrue(rate.allow("three")); assertFalse(rate.allow("four"))
        time = 60_000
        assertTrue(rate.allow("one")); assertTrue(rate.allow("four"))
    }
}
