package app.hermes.companion.data.local

import android.content.Context
import app.hermes.companion.model.ChatAttachment
import app.hermes.companion.model.ChatBlockKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ComposerDraftStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("draft-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private val store = ComposerDraftStore(prefs)
    private val owner = DraftOwner("https://lab/proxy/a", "ash", "thread")
    private val file = ChatAttachment("a", "/synthetic/a", "a.txt", "text/plain", ChatBlockKind.FILE)

    @Test fun formattingAndAttachmentsSurviveStoreRecreation() {
        val expected = ComposerDraft("**bold**\n- list\n`code`\nनमस्ते", listOf(file))
        store.save(owner, expected); assertTrue(store.flush())
        assertEquals(expected, ComposerDraftStore(prefs).load(owner))
    }
    @Test fun hostProfileSessionAndProxyPathsAreIsolated() {
        store.save(owner, ComposerDraft("private"))
        for (other in listOf(owner.copy(origin = "https://other/proxy/a"), owner.copy(profile = "other"),
            owner.copy(session = "other"), owner.copy(origin = "https://lab/proxy/b"))) {
            assertTrue(store.load(other).empty)
        }
        assertEquals("private", store.load(owner.copy(origin = owner.origin + "/")).text)
    }
    @Test fun sentContentIsClearedButNewEditsRemain() {
        val submitted = ComposerDraft("sent", listOf(file))
        store.save(owner, submitted); store.consume(owner, submitted)
        assertTrue(store.load(owner).empty)
        store.save(owner, ComposerDraft("new text", listOf(file, file.copy(id = "new"))))
        store.consume(owner, submitted)
        assertEquals("new text", store.load(owner).text)
        assertEquals("new", store.load(owner).attachments.single().id)
    }
    @Test fun promotionMovesUnchangedLocalContentButPreservesLaterLocalEdits() {
        val local = owner.copy(session = "local:draft")
        val sent = ComposerDraft("sent")
        store.save(local, sent); store.promote(local, owner, sent)
        assertTrue(store.load(local).empty); assertEquals(sent, store.load(owner))
        store.save(local, ComposerDraft("later")); store.promote(local, owner, sent)
        assertEquals("later", store.load(local).text); assertEquals(sent, store.load(owner))
    }
    @Test fun unknownFieldsDoNotDestroyReadableOldDrafts() {
        val key = kotlinx.serialization.json.Json.encodeToString(DraftOwner.serializer(), owner)
        prefs.edit().putString(key, """{"text":"kept","attachments":[],"future":true}""").commit()
        assertEquals("kept", store.load(owner).text)
    }
}
