package app.hermes.hands.engine

import org.junit.Assert.*
import org.junit.Test

class PrivateValuesTest {
    @Test fun longPrivateValueIsRedactedBeforeLabelTruncation() {
        val value = "LOCAL_ONLY_" + "x".repeat(800)
        val values = PrivateValues().apply { remember(value) }
        assertEquals("prefix [private] suffix", values.sanitize("prefix $value suffix").take(240))
        assertFalse(values.sanitize(value).take(240).contains("LOCAL_ONLY_"))
    }

    @Test fun overlappingRepeatedAndUnicodeValuesDoNotLeakFragments() {
        val values = PrivateValues().apply { remember("abc"); remember("abcdef"); remember("नमस्ते🔑") }
        assertEquals("[private] [private] [private]", values.sanitize("abcdef abc नमस्ते🔑"))
    }

    @Test fun retentionAndSingleValueAllocationAreBounded() {
        val values = PrivateValues()
        repeat(33) { values.remember("secret-$it-end") }
        assertEquals("secret-0-end [private]", values.sanitize("secret-0-end secret-32-end"))
        assertThrows(IllegalArgumentException::class.java) { values.remember("x".repeat(PrivateValues.MAX_CHARS + 1)) }
    }

    @Test fun explicitClearDropsAllMemoryOnlyRedactions() {
        val values = PrivateValues().apply { remember("local"); clear() }
        assertEquals("local", values.sanitize("local"))
    }
}
