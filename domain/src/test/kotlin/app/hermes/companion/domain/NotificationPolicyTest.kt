package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {

    @Test
    fun blockingPingsGetTheLoudChannelAndQuietOnesDoNot() {
        // The whole point of stratification: an approval must not arrive as quietly as a run
        // finishing, and a run finishing must not buzz like an approval.
        assertEquals(NotificationPolicy.APPROVALS, NotificationPolicy.channelFor("approval.request"))
        assertEquals(NotificationPolicy.APPROVALS, NotificationPolicy.channelFor("room.approval"))
        assertEquals(NotificationPolicy.Importance.HIGH, NotificationPolicy.channelFor("approval.request").importance)

        assertEquals(NotificationPolicy.TURNS, NotificationPolicy.channelFor("run.completed"))
        assertEquals(NotificationPolicy.Importance.LOW, NotificationPolicy.channelFor("run.completed").importance)
    }

    @Test
    fun questionsAndMentionsAlert() {
        for (type in listOf("clarify", "room.mention")) {
            assertEquals(type, NotificationPolicy.MENTIONS, NotificationPolicy.channelFor(type))
        }
        assertEquals(NotificationPolicy.Importance.DEFAULT, NotificationPolicy.MENTIONS.importance)
    }

    @Test
    fun errorsAreNotSilent() {
        // An error on the MIN health channel is one the user finds out about hours later.
        val channel = NotificationPolicy.channelFor("error")
        assertNotEquals(NotificationPolicy.HEALTH, channel)
        assertEquals(NotificationPolicy.Importance.DEFAULT, channel.importance)
    }

    @Test
    fun everyWakePolicyTypeRoutesSomewhereKnown() {
        for (type in WakePolicy.TYPES + WakePolicy.ROOM_TYPES) {
            assertTrue(type, NotificationPolicy.channelFor(type) in NotificationPolicy.ALL)
        }
    }

    @Test
    fun reservedPrefixesRouteToTheirChannels() {
        assertEquals(NotificationPolicy.CRON, NotificationPolicy.channelFor("cron.fired"))
        assertEquals(NotificationPolicy.HEALTH, NotificationPolicy.channelFor("health.lane"))
        assertEquals(NotificationPolicy.TURNS, NotificationPolicy.channelFor("something.unknown"))
    }

    @Test
    fun channelIdsAreStableAndUnique() {
        val ids = NotificationPolicy.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("hermes.") })
    }

    @Test
    fun pillsMarkWhatNeedsScanningAndNothingElse() {
        assertEquals("APPROVAL", NotificationPolicy.pill("approval.request"))
        assertEquals("QUESTION", NotificationPolicy.pill("clarify"))
        assertEquals("ERROR", NotificationPolicy.pill("error"))
        assertEquals("MENTION", NotificationPolicy.pill("room.mention"))
        // A finished run is already on a silent channel; a badge would be noise.
        assertNull(NotificationPolicy.pill("run.completed"))
    }

    @Test
    fun senderNamesTheHostBecauseProfilesRepeatAcrossHosts() {
        assertEquals("knight · lab", NotificationPolicy.sender("knight", "lab"))
        assertEquals("knight", NotificationPolicy.sender("knight", ""))
        assertEquals("agent", NotificationPolicy.sender("  ", ""))
    }

    @Test
    fun titleCombinesBadgeAndSender() {
        assertEquals("APPROVAL · knight · lab", NotificationPolicy.title("approval.request", "knight", "lab"))
        assertEquals("knight · lab", NotificationPolicy.title("run.completed", "knight", "lab"))
    }

    @Test
    fun bodyNeverFallsBackToARawSessionIdAlone() {
        // The old builder showed "coder · sess-123", which told the user nothing.
        val body = NotificationPolicy.body("approval.request", "", "", "sess-123")
        assertTrue(body, body.startsWith("needs your approval"))
        assertTrue(body, body.contains("sess-123"))
        assertEquals("real text", NotificationPolicy.body("clarify", "real text", "", "sess-1"))
    }

    @Test
    fun glyphSurvivesJunkProfiles() {
        assertEquals("K", NotificationPolicy.glyph("knight"))
        assertEquals("A", NotificationPolicy.glyph("  ash"))
        assertEquals("7", NotificationPolicy.glyph("7up"))
        assertEquals("?", NotificationPolicy.glyph(""))
        assertEquals("?", NotificationPolicy.glyph("---"))
    }

    @Test
    fun idsAreStablePerThreadAndDistinctFromTheSummary() {
        val a = NotificationPolicy.notificationId("http://lab:9120", "sess-1", "")
        val again = NotificationPolicy.notificationId("http://lab:9120", "sess-1", "")
        val other = NotificationPolicy.notificationId("http://lab:9120", "sess-2", "")
        assertEquals(a, again)          // a second ping replaces, not stacks
        assertNotEquals(a, other)
        assertNotEquals(a, NotificationPolicy.summaryId("http://lab:9120"))
        // Room pings key on the room, not the backing session.
        assertEquals(
            NotificationPolicy.notificationId("h", "sess-9", "room-1"),
            NotificationPolicy.notificationId("h", "sess-different", "room-1"),
        )
    }

    @Test
    fun sessionsWithTheSameIdInDifferentProfilesDoNotReplaceEachOther() {
        assertNotEquals(
            NotificationPolicy.notificationId("h", "sess-1", "", "coder"),
            NotificationPolicy.notificationId("h", "sess-1", "", "assistant"),
        )
        assertNotEquals(
            NotificationPolicy.notificationId("h", "same-id", "", "coder"),
            NotificationPolicy.notificationId("h", "", "same-id", "coder"),
        )
    }

    @Test
    fun roomIdentityDoesNotChangeWithTheSpeakingProfile() {
        assertEquals(
            NotificationPolicy.notificationId("h", "backing-1", "room-1", "coder"),
            NotificationPolicy.notificationId("h", "backing-2", "room-1", "assistant"),
        )
    }

    @Test
    fun groupsPerHostSoAFleetCollapses() {
        assertEquals("http://lab:9120", NotificationPolicy.groupKey("http://lab:9120"))
        assertEquals("hermes", NotificationPolicy.groupKey("  "))
        assertNotEquals(
            NotificationPolicy.summaryId("http://lab:9120"),
            NotificationPolicy.summaryId("http://hub:9120"),
        )
    }

    @Test
    fun summaryTextReadsNaturally() {
        assertEquals("1 update from lab", NotificationPolicy.summaryText(1, "lab"))
        assertEquals("3 updates from lab", NotificationPolicy.summaryText(3, "lab"))
        assertEquals("2 updates from Hermes", NotificationPolicy.summaryText(2, ""))
    }
}
