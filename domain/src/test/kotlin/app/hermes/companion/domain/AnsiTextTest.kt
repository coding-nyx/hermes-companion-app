package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTextTest {
    private val esc = "\u001B"
    private val bel = "\u0007"

    @Test
    fun plainLinesAndTrailingNewline() {
        assertEquals(listOf("\$ ls", "foo  bar"), AnsiText.plain("\$ ls\nfoo  bar\n"))
    }

    @Test
    fun sgrColoursBoldAndReset() {
        val spans = AnsiText.parse("${esc}[1;32mok${esc}[0m plain ${esc}[31mbad${esc}[39m end").single()
        assertEquals(listOf("ok", " plain ", "bad", " end"), spans.map { it.text })
        assertTrue(spans[0].bold)
        assertEquals(AnsiText.PALETTE16[2], spans[0].fg)
        assertTrue(spans[1].plain)
        assertEquals(AnsiText.PALETTE16[1], spans[2].fg)
        assertNull(spans[3].fg)
    }

    @Test
    fun extendedColoursAndInverse() {
        val spans = AnsiText.parse("${esc}[38;5;196mred${esc}[48;2;10;20;30mbg${esc}[7minv").single()
        assertEquals(0xFFFF0000L, spans[0].fg)
        assertEquals(0xFF0A141EL, spans[1].bg)
        assertTrue(spans[2].inverse)
        assertEquals(0xFF080808L, AnsiText.xterm256(232))
    }

    @Test
    fun cursorMovesTitlesAndClearsAreDropped() {
        val raw = "${esc}]0;claude — ~/Projects${bel}${esc}[2J${esc}[H${esc}[?25l> ${esc}[K${esc}(Bprompt\r\n${esc}[1Aline2"
        assertEquals(listOf("> prompt", "line2"), AnsiText.plain(raw))
    }

    @Test
    fun styleCarriesAcrossLines() {
        val lines = AnsiText.parse("${esc}[34mone\ntwo${esc}[0m\nthree")
        assertEquals(AnsiText.PALETTE16[4], lines[0].single().fg)
        assertEquals(AnsiText.PALETTE16[4], lines[1].single().fg)
        assertTrue(lines[2].single().plain)
    }
}
