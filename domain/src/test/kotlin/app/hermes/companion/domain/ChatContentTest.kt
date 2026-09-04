package app.hermes.companion.domain

import app.hermes.companion.model.ChatAttachment
import app.hermes.companion.model.ChatBlockKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContentTest {
    @Test
    fun splitsMarkdownImagesFromText() {
        val blocks = ChatContent.fromMarkdown("see ![shot](https://h/x.png) please")
        assertEquals(ChatBlockKind.TEXT, blocks[0].kind)
        assertEquals("see", blocks[0].text)
        assertEquals(ChatBlockKind.IMAGE, blocks[1].kind)
        assertEquals("https://h/x.png", blocks[1].url)
        assertEquals("please", blocks[2].text)
    }

    @Test
    fun screenshotToolBecomesImage() {
        val blocks = ChatContent.screenshotBlocks(
            "device.screenshot",
            null,
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+X2Zk=",
        )
        assertEquals(ChatBlockKind.IMAGE, blocks.single().kind)
        assertTrue(blocks.single().url.startsWith("data:image/png;base64,"))
    }

    @Test
    fun submitPartsIncludeUploadedUrls() {
        val extra = ChatContent.toSubmitParts(
            "hi",
            listOf(
                ChatAttachment(
                    id = "a1",
                    localUri = "file://x",
                    name = "x.jpg",
                    mime = "image/jpeg",
                    kind = ChatBlockKind.IMAGE,
                    uploadedUrl = "/companion/media/abc",
                ),
            ),
        )
        assertTrue(extra.contains("\"type\":\"image\""))
        assertTrue(extra.contains("/companion/media/abc"))
        assertTrue(extra.contains("\"type\":\"text\""))
    }
}
