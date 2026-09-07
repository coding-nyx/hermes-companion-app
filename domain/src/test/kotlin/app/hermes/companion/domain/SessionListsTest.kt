package app.hermes.companion.domain

import app.hermes.companion.model.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListsTest {
    private fun session(id: String, updated: Long = 0L) = SessionRef(
        id = id,
        profileId = "default",
        title = id,
        updatedAtEpochMs = updated,
    )

    @Test
    fun prependDropsDuplicateIdKeepingCreated() {
        val existing = listOf(session("a", 1), session("b", 2), session("a", 0))
        val created = session("a", 99).copy(title = "fresh")
        val next = SessionLists.prepend(created, existing)
        assertEquals(listOf("a", "b"), next.map { it.id })
        assertEquals("fresh", next.first().title)
        assertEquals(2, next.size)
    }

    @Test
    fun prependAndNormalizeDropDraftPlaceholders() {
        val draft = DraftThread.placeholder("default")
        val real = session("n1", 5)
        assertEquals(listOf("n1"), SessionLists.prepend(draft, listOf(real, draft)).map { it.id })
        assertEquals(listOf("n1"), SessionLists.prepend(real, listOf(draft)).map { it.id })
        assertEquals(listOf("n1"), SessionLists.normalize(listOf(draft, real, draft)).map { it.id })
    }

    @Test
    fun normalizeDedupesAndOrdersByUpdatedAt() {
        val rows = listOf(
            session("old", 10),
            session("new", 30),
            session("old", 20),
            session("mid", 20),
        )
        val next = SessionLists.normalize(rows)
        assertEquals(listOf("new", "mid", "old"), next.map { it.id })
        assertEquals(10L, next.find { it.id == "old" }!!.updatedAtEpochMs)
    }
}
