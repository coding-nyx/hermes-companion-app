package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginPolicyTest {
    @Test
    fun loopbackIsThisPhone() {
        assertTrue(OriginPolicy.loopback("http://127.0.0.1:19219"))
        assertTrue(OriginPolicy.loopback("http://localhost:9119"))
        assertFalse(OriginPolicy.loopback("http://100.85.151.99:9120"))
        assertEquals("100.85.151.99", OriginPolicy.host("http://100.85.151.99:9120/"))
    }

    @Test
    fun hintOnlyForLoopback() {
        assertNotNull(OriginPolicy.hint("http://127.0.0.1:19219"))
        assertTrue(OriginPolicy.hint("http://127.0.0.1:19219")!!.contains("9120"))
        assertNull(OriginPolicy.hint("http://100.85.151.99:9120"))
        assertEquals(OriginPolicy.EMPTY_HINT, OriginPolicy.hint(""))
        assertTrue(OriginPolicy.hint("")!!.contains("9120"))
    }
}
