package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonArgsTest {
    @Test
    fun epochMillisSurviveExactly() {
        // 2026-09-30T10:00:00.123Z — a Float would round this to a multiple of 131072 ms.
        val raw = """{"begin_ms":1790762400123,"end_ms":1790766000000,"title":"x"}"""
        assertEquals(1790762400123L, JsonArgs.long(raw, "begin_ms"))
        assertEquals(1790766000000L, JsonArgs.long(raw, "end_ms"))
        assertEquals("1790762400123", JsonArgs.numberArg(raw, "begin_ms"))
    }

    @Test
    fun floatSpellingsOfIntegers() {
        val raw = """{"since_ms":1790762400123.0,"to_ms":1.790762400123E12}"""
        assertEquals(1790762400123L, JsonArgs.long(raw, "since_ms"))
        assertEquals(1790762400123L, JsonArgs.long(raw, "to_ms"))
        assertEquals("1790762400123", JsonArgs.numberArg(raw, "since_ms"))
    }

    @Test
    fun fractionsAndMissing() {
        val raw = """{"rate":1.25,"limit":20,"neg":-3}"""
        assertEquals(1.25, JsonArgs.double(raw, "rate")!!, 0.0)
        assertEquals("1.25", JsonArgs.numberArg(raw, "rate"))
        assertEquals(20L, JsonArgs.long(raw, "limit"))
        assertEquals(-3L, JsonArgs.long(raw, "neg"))
        assertNull(JsonArgs.long(raw, "absent"))
        assertNull(JsonArgs.long("""{"limit":"20"}""", "limit"))
    }

    @Test
    fun stringEscapesDecode() {
        // Exactly what kotlinx JsonObject.toString() / Python json.dumps put on the wire.
        val raw = """{"to":"+15550100","body":"Hi\nBye\tthere \"q\" C:\\tmp \/ \u00e9 \ud83d\ude00"}"""
        assertEquals("Hi\nBye\tthere \"q\" C:\\tmp / \u00e9 \uD83D\uDE00", JsonArgs.string(raw, "body"))
        assertEquals("+15550100", JsonArgs.string(raw, "to"))
        assertNull(JsonArgs.string(raw, "absent"))
    }

    @Test
    fun malformedEscapesPassThrough() {
        assertEquals("a\\u12", JsonArgs.unescape("a\\u12"))
        assertEquals("x\\qy", JsonArgs.unescape("x\\qy"))
        assertEquals("plain", JsonArgs.unescape("plain"))
    }
}
