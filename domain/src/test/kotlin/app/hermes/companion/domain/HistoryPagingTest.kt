package app.hermes.companion.domain

import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPagingTest {
    private val all = (1..200).map { msg("m$it") }

    @Test
    fun tailKeepsLastPage() {
        val page = HistoryPaging.tail(all, 80)
        assertEquals(80, page.size)
        assertEquals("m121", page.first().id)
        assertEquals("m200", page.last().id)
    }

    @Test
    fun olderThanStopsAtBeforeId() {
        val older = HistoryPaging.olderThan(all, "m121", 80)
        assertEquals("m41", older.first().id)
        assertEquals("m120", older.last().id)
        assertTrue(older.none { it.id == "m121" })
    }

    @Test
    fun clipInitialHasMoreWhenTruncated() {
        val page = HistoryPaging.clip(all, beforeId = null, limit = 80)
        assertEquals(80, page.messages.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun clipEmptyOlderMeansCaughtUp() {
        val first = HistoryPaging.tail(all, 80)
        val page = HistoryPaging.clip(first, beforeId = first.first().id, limit = 80)
        assertTrue(page.messages.isEmpty())
        assertFalse(page.hasMore)
    }

    private fun msg(id: String) = ChatMessage(id = id, role = MessageRole.USER, text = id)
}
