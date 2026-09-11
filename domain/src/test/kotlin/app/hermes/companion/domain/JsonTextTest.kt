package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTextTest {
    @Test
    fun escapesTheMandatoryTwo() {
        assertEquals("\"a\\\\b\"", JsonText.quote("a\\b"))
        assertEquals("\"say \\\"hi\\\"\"", JsonText.quote("say \"hi\""))
    }

    @Test
    fun escapesNewlinesSoPayloadsStayParseable() {
        // The bug this class exists for: an SMS body or notification text with a newline used
        // to be emitted raw, producing malformed JSON that surfaced to the caller as a timeout.
        assertEquals("\"line1\\nline2\"", JsonText.quote("line1\nline2"))
        assertEquals("\"a\\r\\nb\"", JsonText.quote("a\r\nb"))
        assertEquals("\"col1\\tcol2\"", JsonText.quote("col1\tcol2"))
    }

    @Test
    fun escapesOtherControlCharactersAsUnicode() {
        assertEquals("\"\\u0000\"", JsonText.quote("\u0000"))
        assertEquals("\"\\u001f\"", JsonText.quote("\u001f"))
        assertEquals("\"\\b\\f\"", JsonText.quote("\b\u000C"))
    }

    @Test
    fun leavesOrdinaryAndNonAsciiTextAlone() {
        assertEquals("\"hello\"", JsonText.quote("hello"))
        assertEquals("\"ரஜ் 😀\"", JsonText.quote("ரஜ் 😀"))
    }

    @Test
    fun objRendersTypesCorrectly() {
        val out = JsonText.obj(linkedMapOf("s" to "x", "n" to 4, "b" to true, "z" to null))
        assertEquals("{\"s\":\"x\",\"n\":4,\"b\":true,\"z\":null}", out)
    }

    @Test
    fun objEscapesValuesToo() {
        assertEquals("{\"body\":\"a\\nb\"}", JsonText.obj(mapOf("body" to "a\nb")))
    }
}
