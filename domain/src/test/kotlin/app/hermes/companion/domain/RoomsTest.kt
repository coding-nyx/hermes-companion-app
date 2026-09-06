package app.hermes.companion.domain

import app.hermes.companion.model.RoomParticipant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsTest {
    @Test
    fun glyphMirrorsHost() {
        assertEquals("COD", Rooms.glyph("coder"))
        assertEquals("OP", Rooms.glyph("op"))
        assertEquals("YOU", Rooms.glyph(null))
        assertEquals("YOU", Rooms.glyph("operator"))
    }

    @Test
    fun backingSessionsAreHidden() {
        assertTrue(Rooms.isBackingSession("room:r-1 triage"))
        assertFalse(Rooms.isBackingSession("roomy thread"))
    }

    @Test
    fun mentionChipInsertsOnce() {
        assertEquals("@OPS ", Rooms.withMention("", "OPS"))
        assertEquals("pull the journal @OPS ", Rooms.withMention("pull the journal", "OPS"))
        assertEquals("@ops please", Rooms.withMention("@ops please", "OPS"))
    }

    @Test
    fun speakerIndexFallsBackToZero() {
        val parts = listOf(RoomParticipant("coder", "COD"), RoomParticipant("ops", "OPS"))
        assertEquals(1, Rooms.speakerIndex("ops", parts))
        assertEquals(0, Rooms.speakerIndex("ghost", parts))
        assertEquals(0, Rooms.speakerIndex(null, parts))
    }
}
