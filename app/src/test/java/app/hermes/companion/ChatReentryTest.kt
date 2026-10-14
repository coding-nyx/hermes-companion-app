package app.hermes.companion

import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.OutboxItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving the chat window while the agent is still thinking, then coming back.
 *
 * [ChatSessionManager.closeChat] deliberately lets the turn keep running, so its outbox item
 * is still pending on re-entry. It must not be drawn a second time next to the host's own copy,
 * and the window must come back showing the turn as live.
 */
class ChatReentryTest {

    private fun user(id: String, text: String) =
        ChatMessage(id = id, role = MessageRole.USER, text = text)

    private fun pending(id: String, text: String, attempts: Int = 0) = OutboxItem(
        id = id,
        origin = "http://lab:9120",
        profileId = "cod",
        sessionId = "s-1",
        text = text,
        createdAtEpochMs = 1L,
        attempts = attempts,
    )

    @Test
    fun anInFlightFirstSendIsNotDrawnTwiceWhenTheHostAlreadyHasIt() {
        // Sent, agent still thinking, user backed out to the list and reopened. The host
        // transcript already carries the user turn while the item sits pending on attempt 0.
        val server = listOf(user("row-9", "what is the status"))

        val merged = mergeQueued(server, listOf(pending("u-1", "what is the status").copy(baselineUserIds = emptyList())))

        assertEquals(listOf("what is the status"), merged.map { it.text })
        assertFalse("no stale queued badge on a turn the host has", merged.single().queued)
    }

    @Test
    fun aPendingSendTheHostNeverGotStillShowsAsQueued() {
        // Guard on the other side of the fix: suppression must never swallow a real message.
        val server = listOf(user("row-9", "earlier question"))

        val merged = mergeQueued(server, listOf(pending("u-1", "never arrived")))

        assertEquals(listOf("earlier question", "never arrived"), merged.map { it.text })
        assertTrue(merged.last().queued)
    }

    @Test
    fun reopeningTheWindowShowsTheTurnStillRunning() {
        val st = CompanionState(streamingSessionId = "s-1")

        assertTrue(streamingFor(st, "s-1"))
        assertFalse(streamingFor(st, "s-2"))
    }

    @Test
    fun switchingHostDropsTheInFlightMarker() {
        // A turn running on the old gateway is not running on the new one; a marker that
        // survived the switch would show a phantom thinking indicator forever.
        val st = CompanionState(streamingSessionId = "s-1", streaming = true)

        assertEquals(null, st.dropHostScoped().streamingSessionId)
    }

    @Test
    fun aTurnFailureDoesNotWriteIntoAWindowTheUserHasLeft() {
        // `open` used to be captured before the stream and reused in the failure branches, so a
        // failure landing after the user left wrote stream state into the list view.
        val left = CompanionState(openSessionId = null, messages = emptyList())

        assertEquals(left, applyTurnFailure(left, "u-1", "s-1", "a-1", "session busy"))
    }

    @Test
    fun aTurnFailureInTheOpenWindowRequeuesTheMessage() {
        val open = CompanionState(
            openSessionId = "s-1",
            streaming = true,
            messages = listOf(user("u-1", "what is the status")),
        )

        val next = applyTurnFailure(open, "u-1", "s-1", "a-1", "session busy")

        assertTrue(next.messages.single().queued)
        assertFalse(next.streaming)
        assertEquals("session busy", next.error)
    }
}
