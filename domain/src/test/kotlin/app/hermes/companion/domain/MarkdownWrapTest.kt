package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownWrapTest {
    @Test
    fun boldWrapsSelection() {
        val out = MarkdownWrap.bold(edit("say hello now", 4, 9))
        assertEquals("say **hello** now", out.text)
        assertEquals(4, out.selectionStart)
        assertEquals(13, out.selectionEnd)
    }

    @Test
    fun boldInsertsWhenEmpty() {
        val out = MarkdownWrap.bold(edit("ab", 1, 1))
        assertEquals("a****b", out.text)
        assertEquals(3, out.selectionStart)
        assertEquals(3, out.selectionEnd)
    }

    @Test
    fun boldUnwrapsMarkersInsideSelection() {
        val out = MarkdownWrap.bold(edit("say **hello** now", 4, 13))
        assertEquals("say hello now", out.text)
        assertEquals(4, out.selectionStart)
        assertEquals(9, out.selectionEnd)
    }

    @Test
    fun boldUnwrapsMarkersOutsideSelection() {
        val out = MarkdownWrap.bold(edit("say **hello** now", 6, 11))
        assertEquals("say hello now", out.text)
        assertEquals(4, out.selectionStart)
        assertEquals(9, out.selectionEnd)
    }

    @Test
    fun italicWrapsWithoutEatingBold() {
        val out = MarkdownWrap.italic(edit("hello", 0, 5))
        assertEquals("*hello*", out.text)
    }

    @Test
    fun codeWrapsSelection() {
        val out = MarkdownWrap.code(edit("run ls here", 4, 6))
        assertEquals("run `ls` here", out.text)
    }

    @Test
    fun bulletPrefixesCurrentLine() {
        val out = MarkdownWrap.bullet(edit("alpha", 2, 2))
        assertEquals("- alpha", out.text)
        assertEquals(0, out.selectionStart)
        assertEquals(7, out.selectionEnd)
    }

    @Test
    fun bulletTogglesOff() {
        val out = MarkdownWrap.bullet(edit("- alpha", 3, 3))
        assertEquals("alpha", out.text)
    }

    @Test
    fun bulletMultipleLinesSkipsBlanks() {
        val out = MarkdownWrap.bullet(edit("one\n\ntwo", 0, 8))
        assertEquals("- one\n\n- two", out.text)
    }
}

private fun edit(text: String, start: Int, end: Int) = MarkdownWrap.Edit(text, start, end)
