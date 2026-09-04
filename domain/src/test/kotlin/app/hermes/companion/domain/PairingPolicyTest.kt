package app.hermes.companion.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPolicyTest {
    @Test
    fun normalizeAndValidate() {
        assertTrue(PairingPolicy.isValid("k7m2qx"))
        assertTrue(PairingPolicy.isValid("K7M-2QX"))
        assertEquals("K7M2QX", PairingPolicy.normalize("k7m-2qx"))
        assertFalse(PairingPolicy.isValid("ABC"))
        assertFalse(PairingPolicy.isValid("IIIIII"))
        assertFalse(PairingPolicy.isValid("000000"))
    }

    @Test
    fun ttlTenMinutes() {
        assertFalse(PairingPolicy.expired(0L, PairingPolicy.TTL_MS - 1))
        assertTrue(PairingPolicy.expired(0L, PairingPolicy.TTL_MS))
    }

    @Test
    fun issueUsesAlphabet() {
        val code = PairingPolicy.issue(Random(1))
        assertEquals(PairingPolicy.CODE_LEN, code.length)
        assertTrue(PairingPolicy.isValid(code))
        assertTrue(code.all { it in PairingPolicy.ALPHABET })
    }
}
