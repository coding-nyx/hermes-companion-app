package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftThreadTest {
    @Test
    fun placeholderIsLocalOnly() {
        val draft = DraftThread.placeholder("coder")
        assertEquals(DraftThread.ID, draft.id)
        assertEquals("coder", draft.profileId)
        assertEquals("new", draft.title)
        assertTrue(DraftThread.isDraft(draft))
        assertTrue(DraftThread.isDraft(draft.id))
        assertFalse(DraftThread.isPersisted(draft.id))
    }

    @Test
    fun realSessionIdsArePersisted() {
        assertFalse(DraftThread.isDraft("n1"))
        assertFalse(DraftThread.isDraft(null as String?))
        assertFalse(DraftThread.isPersisted(null))
        assertFalse(DraftThread.isPersisted(""))
        assertTrue(DraftThread.isPersisted("n1"))
    }
}
