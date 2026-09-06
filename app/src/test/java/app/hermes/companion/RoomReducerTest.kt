package app.hermes.companion

import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room event reducer: per-turn segments keyed by turn id, speaker stamping, PASS and idle. */
class RoomReducerTest {
    private fun manager(state: MutableStateFlow<CompanionState>) =
        RoomSessionManager(HostClientPool(), state, CoroutineScope(Dispatchers.Unconfined))

    private fun run(vararg events: ChatEvent): CompanionState {
        val flow = MutableStateFlow(CompanionState(openRoomId = "r-1"))
        val m = manager(flow)
        return events.fold(flow.value) { st, ev -> m.applyRoomEvent(st, ev) }
    }

    @Test
    fun twoSpeakersGetSeparateLabelledSegments() {
        val st = run(
            ChatEvent.RoomPost(1, "who owns it?"),
            ChatEvent.TurnStarted("coder", "t1", 1),
            ChatEvent.ToolStarted("terminal", "journalctl", "coder"),
            ChatEvent.ToolCompleted("terminal", "ok", 20, "coder"),
            ChatEvent.AssistantDelta("mint fails ", "coder"),
            ChatEvent.AssistantDelta("@OPS?", "coder"),
            ChatEvent.TurnEnded("coder", "t1", 2, false, ""),
            ChatEvent.TurnStarted("ops", "t2", 1),
            ChatEvent.AssistantDelta("two restarts", "ops"),
            ChatEvent.TurnEnded("ops", "t2", 3, false, ""),
            ChatEvent.Completed,
        )
        assertEquals(
            listOf(MessageRole.USER, MessageRole.TOOL, MessageRole.ASSISTANT, MessageRole.ASSISTANT),
            st.messages.map { it.role },
        )
        assertEquals(listOf(null, "coder", "coder", "ops"), st.messages.map { it.speaker })
        assertEquals("mint fails @OPS?", st.messages[2].text)
        assertEquals("rm-2", st.messages[2].id)
        assertEquals("rm-3", st.messages[3].id)
        assertFalse(st.streaming)
        assertNull(st.roomSpeaking)
        assertTrue(st.messages.none { it.streaming || it.toolRunning })
    }

    @Test
    fun streamingStaysOnBetweenTurnsUntilIdle() {
        val mid = run(
            ChatEvent.TurnStarted("coder", "t1", 1),
            ChatEvent.AssistantDelta("hi", "coder"),
            ChatEvent.TurnEnded("coder", "t1", 2, false, ""),
        )
        assertTrue(mid.streaming)
        assertNull(mid.roomSpeaking)
        val speaking = run(ChatEvent.TurnStarted("ops", "t2", 2))
        assertEquals("ops", speaking.roomSpeaking)
        assertTrue(speaking.streaming)
    }

    @Test
    fun passReplacesSegmentWithMutedRow() {
        val st = run(
            ChatEvent.TurnStarted("ops", "t1", 2),
            ChatEvent.AssistantDelta("PASS", "ops"),
            ChatEvent.TurnEnded("ops", "t1", 4, true, ""),
            ChatEvent.Completed,
        )
        val row = st.messages.single()
        assertTrue(row.passed)
        assertEquals("", row.text)
        assertEquals("ops", row.speaker)
        assertEquals("rm-4", row.id)
    }

    @Test
    fun errorTurnWithoutTextGetsAnErrorRow() {
        val st = run(
            ChatEvent.TurnStarted("coder", "t1", 1),
            ChatEvent.TurnEnded("coder", "t1", 5, false, "upstream_timeout"),
            ChatEvent.Completed,
        )
        val row = st.messages.single()
        assertEquals("upstream_timeout", row.toolDetail)
        assertEquals("coder", row.speaker)
    }

    @Test
    fun postEchoAdoptsTheOptimisticLocalRow() {
        val flow = MutableStateFlow(
            CompanionState(
                openRoomId = "r-1",
                messages = listOf(ChatMessage(id = "u-room-123", role = MessageRole.USER, text = "who owns it?")),
            ),
        )
        val st = manager(flow).applyRoomEvent(flow.value, ChatEvent.RoomPost(1, "who owns it?"))
        assertEquals(listOf("rm-1"), st.messages.map { it.id })
        assertEquals(1, st.messages.size)
    }

    @Test
    fun emptyTurnStaysVisible() {
        val st = run(
            ChatEvent.TurnStarted("ash", "t1", 1),
            ChatEvent.TurnEnded("ash", "t1", 3, false, ""),
            ChatEvent.Completed,
        )
        val row = st.messages.single()
        assertEquals("ash", row.speaker)
        assertEquals("empty_reply", row.toolDetail)
    }

    @Test
    fun duplicatePostEchoIsIgnored() {
        val st = run(ChatEvent.RoomPost(7, "hi"), ChatEvent.RoomPost(7, "hi"))
        assertEquals(1, st.messages.size)
        assertEquals("rm-7", st.messages[0].id)
    }

    @Test
    fun turnStartedSpeakerUsedWhenDeltaSpeakerNull() {
        val st = run(
            ChatEvent.TurnStarted("coder", "t1", 1),
            ChatEvent.AssistantDelta("hi", null), // delta speaker was null
            ChatEvent.TurnEnded("coder", "t1", 2, false, ""),
            ChatEvent.Completed,
        )
        val row = st.messages.single()
        assertEquals("coder", row.speaker)
        assertEquals("hi", row.text)
    }
}
