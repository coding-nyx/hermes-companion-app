package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderTest {
    @Test
    fun nestedInlineBoldItalic() {
        val spans = MarkdownRender.inline("**bold *and italic***")
        assertEquals("bold ", spans[0].text)
        assertTrue(spans[0].bold)
        assertEquals("and italic", spans[1].text)
        assertTrue(spans[1].bold)
        assertTrue(spans[1].italic)
    }

    @Test
    fun unterminatedFenceRendersAsCode() {
        val blocks = MarkdownRender.blocks("```kotlin\nval x = 1")
        val fence = blocks.single() as MdBlock.Fence
        assertEquals("kotlin", fence.lang)
        assertEquals("val x = 1", fence.text)
    }

    @Test
    fun listAfterParagraph() {
        val blocks = MarkdownRender.blocks("hello\n- one\n- two")
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals("hello", (blocks[0] as MdBlock.Paragraph).spans.single().text)
        assertEquals("one", (blocks[1] as MdBlock.Bullet).spans.single().text)
        assertEquals("two", (blocks[2] as MdBlock.Bullet).spans.single().text)
    }

    @Test
    fun escapedAsteriskIsLiteral() {
        val spans = MarkdownRender.inline("foo \\* bar")
        assertEquals("foo * bar", spans.joinToString("") { it.text })
        assertTrue(spans.none { it.bold || it.italic })
    }

    @Test
    fun inlineCodeLinkAndQuote() {
        val spans = MarkdownRender.inline("see `x` and [docs](https://ex)")
        assertEquals("x", spans[1].text)
        assertTrue(spans[1].code)
        assertEquals("docs", spans[3].text)
        assertEquals("https://ex", spans[3].href)
        val quote = MarkdownRender.blocks("> hold") .single() as MdBlock.Quote
        assertEquals("hold", quote.spans.single().text)
    }

    @Test
    fun tableAndImageBlock() {
        val md = """
            | lane | status |
            | --- | --- |
            | operator | live |
            ![signal](data:image/png;base64,aaa)
        """.trimIndent()
        val blocks = MarkdownRender.blocks(md)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("lane", "status"), table.headers)
        assertEquals("operator", table.rows.single()[0])
        val image = blocks[1] as MdBlock.Image
        assertEquals("signal", image.alt)
        assertTrue(image.url.startsWith("data:image/png"))
    }
}
