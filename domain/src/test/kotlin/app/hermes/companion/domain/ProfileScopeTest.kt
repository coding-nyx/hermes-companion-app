package app.hermes.companion.domain

import app.hermes.companion.model.SessionChange
import app.hermes.companion.model.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileScopeTest {
    private val coder = session("s-coder", "coder")
    private val ops = session("s-ops", "ops")
    private val personal = session("s-per", "personal")

    @Test
    fun visibleSessionsNeverCrossProfiles() {
        val mixed = listOf(coder, ops, personal)
        val visible = ProfileScope.visibleSessions(mixed, "coder")
        assertEquals(listOf(coder), visible)
        assertTrue(visible.none { it.profileId != "coder" })
    }

    @Test
    fun noActiveProfileShowsNothing() {
        assertTrue(ProfileScope.visibleSessions(listOf(coder, ops), null).isEmpty())
    }

    @Test
    fun stickyProfileWinsIfStillPresent() {
        val profiles = listOf(
            ProfileScope.toRef("coder"),
            ProfileScope.toRef("ops"),
        )
        assertEquals("ops", ProfileScope.resolveActive(profiles, "ops")?.id)
        assertEquals("coder", ProfileScope.resolveActive(profiles, "gone")?.id)
    }

    @Test
    fun glyphIsThreeLetters() {
        assertEquals("COD", ProfileScope.glyph("coder"))
        assertEquals("PER", ProfileScope.glyph("personal"))
        assertEquals("OPS", ProfileScope.glyph("ops"))
        assertEquals("DEF", ProfileScope.glyph("default"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankProfileIsRejectedOnTheWire() {
        ProfileScope.requireProfileId("  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotOpenForeignSession() {
        ProfileScope.requireOwnedSession(ops, "coder")
    }

    @Test
    fun ownedSessionPasses() {
        assertEquals(coder, ProfileScope.requireOwnedSession(coder, "coder"))
    }

    @Test
    fun sessionChangeIgnoresForeignProfile() {
        val change = SessionChange("upsert", ops.copy(title = "nope"))
        val next = ProfileScope.applyChange(listOf(coder), change, "coder")
        assertEquals(listOf(coder), next)
    }

    @Test
    fun sessionChangeDeleteRemovesOwnedRow() {
        val change = SessionChange("delete", coder)
        val next = ProfileScope.applyChange(listOf(coder, ops), change, "coder")
        assertEquals(emptyList<SessionRef>(), next.filter { it.profileId == "coder" })
        assertTrue(next.none { it.id == coder.id })
    }

    @Test
    fun sessionChangeUpsertPrependsAndReplaces() {
        val created = session("s-new", "coder").copy(title = "fresh")
        val added = ProfileScope.applyChange(listOf(coder), SessionChange("upsert", created), "coder")
        assertEquals("s-new", added.first().id)
        val renamed = coder.copy(title = "renamed")
        val updated = ProfileScope.applyChange(added, SessionChange("upsert", renamed), "coder")
        assertEquals("renamed", updated.first { it.id == coder.id }.title)
    }

    @Test
    fun sparseUpsertPatchKeepsTitleAndTimestamps() {
        val known = coder.copy(title = "real title", updatedAtEpochMs = 500L, createdAtEpochMs = 100L)
        // `sessions.changed` often carries only id + profile + op; the parser echoes the id as title.
        val sparse = SessionChange("upsert", session(coder.id, "coder").copy(unread = true))
        val next = ProfileScope.applyChange(listOf(known), sparse, "coder")
        val row = next.single()
        assertEquals("real title", row.title)
        assertEquals(500L, row.updatedAtEpochMs)
        assertEquals(100L, row.createdAtEpochMs)
        assertTrue(row.unread)
    }

    private fun session(id: String, profile: String) = SessionRef(
        id = id,
        profileId = profile,
        title = id,
        updatedAtEpochMs = 0L,
    )
}
