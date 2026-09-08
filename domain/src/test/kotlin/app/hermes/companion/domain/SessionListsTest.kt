package app.hermes.companion.domain

import app.hermes.companion.model.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListsTest {
    private fun session(id: String, updated: Long = 0L, created: Long = 0L, title: String = id) = SessionRef(
        id = id,
        profileId = "default",
        title = title,
        updatedAtEpochMs = updated,
        createdAtEpochMs = created,
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
    fun normalizeDedupesByMergingAndOrdersNewestCreatedFirst() {
        val rows = listOf(
            session("old", updated = 10, created = 1),
            session("new", updated = 30, created = 3),
            session("old", updated = 20, created = 1, title = "renamed"),
            session("mid", updated = 20, created = 2),
        )
        val next = SessionLists.normalize(rows)
        assertEquals(listOf("new", "mid", "old"), next.map { it.id })
        val old = next.first { it.id == "old" }
        assertEquals("richer duplicate wins the timestamp", 20L, old.updatedAtEpochMs)
        assertEquals("renamed", old.title)
    }

    @Test
    fun createdSortFallsBackToActivityWhenHostSendsNoStartedAt() {
        val rows = listOf(
            session("a", updated = 100),
            session("b", updated = 300),
            session("c", updated = 200, created = 250),
        )
        assertEquals(listOf("b", "c", "a"), SessionLists.sort(rows, ThreadSort.CREATED).map { it.id })
    }

    @Test
    fun activeSortUsesLastActivityNotCreation() {
        val rows = listOf(
            session("fresh-but-quiet", updated = 10, created = 900),
            session("old-but-busy", updated = 500, created = 1),
        )
        assertEquals(listOf("fresh-but-quiet", "old-but-busy"), SessionLists.sort(rows, ThreadSort.CREATED).map { it.id })
        assertEquals(listOf("old-but-busy", "fresh-but-quiet"), SessionLists.sort(rows, ThreadSort.ACTIVE).map { it.id })
    }

    @Test
    fun titleSortIsCaseInsensitiveWithNewestTieBreak() {
        val rows = listOf(
            session("1", created = 1, title = "zeta"),
            session("2", created = 2, title = "Alpha"),
            session("3", created = 3, title = "alpha"),
        )
        assertEquals(listOf("3", "2", "1"), SessionLists.sort(rows, ThreadSort.TITLE).map { it.id })
    }

    @Test
    fun noTimestampsAtAllOrdersByIdDescendingLikeHermesIds() {
        // Hermes ids are YYYYMMDD_HHMMSS_hash: descending id == newest first. Must match Room's ORDER BY.
        val rows = listOf(
            session("20260901_100000_aaa"),
            session("20260907_090000_bbb"),
            session("20260903_120000_ccc"),
        )
        val ids = SessionLists.sort(rows).map { it.id }
        assertEquals(listOf("20260907_090000_bbb", "20260903_120000_ccc", "20260901_100000_aaa"), ids)
        assertEquals(ids, SessionLists.sort(rows.reversed()).map { it.id })
        assertEquals(ids, SessionLists.sort(rows, ThreadSort.ACTIVE).map { it.id })
    }

    @Test
    fun orderIsStableRegardlessOfInputOrder() {
        val rows = List(40) { i -> session("s$i", updated = (i * 7L) % 11, created = (i * 3L) % 5) }
        val a = SessionLists.normalize(rows).map { it.id }
        val b = SessionLists.normalize(rows.shuffled()).map { it.id }
        val c = SessionLists.normalize(rows.reversed()).map { it.id }
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun mergeKeepsKnownFieldsWhenPatchIsSparse() {
        val known = session("s1", updated = 100, created = 50, title = "fix the relay").copy(
            messageCount = 12,
            source = "telegram",
        )
        val sparse = SessionRef(id = "s1", profileId = "default", title = "s1", updatedAtEpochMs = 0L, unread = true)
        val merged = SessionLists.merge(known, sparse)
        assertEquals("fix the relay", merged.title)
        assertEquals(100L, merged.updatedAtEpochMs)
        assertEquals(50L, merged.createdAtEpochMs)
        assertEquals(12, merged.messageCount)
        assertEquals("telegram", merged.source)
        assertTrue(merged.unread)
    }

    @Test
    fun mergeNeverMovesAThreadBackwardsInTime() {
        val known = session("s1", updated = 900, created = 100)
        val stale = session("s1", updated = 400, created = 100, title = "late copy")
        val merged = SessionLists.merge(known, stale)
        assertEquals(900L, merged.updatedAtEpochMs)
        assertEquals("late copy", merged.title)
    }

    @Test
    fun mergeEndedIsStickyUnlessNewerPatchReopens() {
        val ended = session("s1", updated = 100).copy(ended = true)
        val samePatch = session("s1", updated = 100)
        assertTrue(SessionLists.merge(ended, samePatch).ended)
        val newer = session("s1", updated = 200)
        assertFalse(SessionLists.merge(ended, newer).ended)
    }

    @Test
    fun filterMatchesTitleIdOrSource() {
        val rows = listOf(
            session("20260907_1", title = "gateway 502 on lab"),
            session("20260907_2", title = "shopping").copy(source = "telegram"),
        )
        assertEquals(listOf("20260907_1"), SessionLists.filter(rows, "GATEWAY").map { it.id })
        assertEquals(listOf("20260907_2"), SessionLists.filter(rows, "tele").map { it.id })
        assertEquals(2, SessionLists.filter(rows, "  ").size)
        assertEquals(2, SessionLists.filter(rows, "20260907").size)
    }

    @Test
    fun bySourceAndArchivedVisibility() {
        val rows = listOf(
            session("tg", title = "tg").copy(source = "Telegram"),
            session("cli", title = "cli").copy(source = "cli"),
            session("arch", title = "arch").copy(source = "telegram", archived = true),
        )
        assertEquals(listOf("tg", "arch"), SessionLists.bySource(rows, "telegram").map { it.id })
        assertEquals(3, SessionLists.bySource(rows, "").size)
        assertEquals(listOf("tg", "cli"), SessionLists.archivedVisible(rows, false).map { it.id })
        assertEquals(3, SessionLists.archivedVisible(rows, true).size)
    }

    @Test
    fun mergeArchivedFollowsTheNewerRow() {
        val live = session("s1", updated = 100)
        val archivedNewer = session("s1", updated = 200).copy(archived = true)
        assertTrue(SessionLists.merge(live, archivedNewer).archived)
        val staleLive = session("s1", updated = 50)
        assertTrue(SessionLists.merge(archivedNewer, staleLive).archived)
        val unarchivedNewer = session("s1", updated = 300)
        assertFalse(SessionLists.merge(archivedNewer, unarchivedNewer).archived)
    }

    @Test
    fun threadSortParsesLenientlyAndDefaultsToCreated() {
        assertEquals(ThreadSort.ACTIVE, ThreadSort.parse("active"))
        assertEquals(ThreadSort.TITLE, ThreadSort.parse(" TITLE "))
        assertEquals(ThreadSort.CREATED, ThreadSort.parse(null))
        assertEquals(ThreadSort.CREATED, ThreadSort.parse("bogus"))
    }
}
