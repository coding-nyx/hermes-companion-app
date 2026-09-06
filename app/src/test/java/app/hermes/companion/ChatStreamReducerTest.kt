package app.hermes.companion

import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Streaming reducer: tool rows carry a live flag between start and complete; finish clears everything. */
class ChatStreamReducerTest {
    private val aid = "a-1"

    private fun run(vararg events: ChatEvent): CompanionState =
        events.fold(CompanionState(streaming = true)) { st, ev -> applyEvent(st, ev, aid) }

    @Test
    fun toolStartMarksRunningAndCompleteClearsIt() {
        val started = run(ChatEvent.ToolStarted("terminal", "ls"))
        val tool = started.messages.single()
        assertEquals(MessageRole.TOOL, tool.role)
        assertTrue(tool.toolRunning)

        val done = applyEvent(started, ChatEvent.ToolCompleted("terminal", "ok", 1200), aid)
        val row = done.messages.single()
        assertFalse(row.toolRunning)
        assertEquals("ok · 1.2s", row.text)
    }

    @Test
    fun deltasAppendToOneStreamingAssistantRow() {
        val st = run(ChatEvent.AssistantDelta("hel"), ChatEvent.AssistantDelta("lo"))
        val a = st.messages.single()
        assertEquals("hello", a.text)
        assertTrue(a.streaming)
        assertTrue(st.streaming)
    }

    @Test
    fun completedClearsStreamingAndRunningFlags() {
        val st = run(
            ChatEvent.ToolStarted("terminal", "ls"),
            ChatEvent.AssistantDelta("done"),
            ChatEvent.Completed,
        )
        assertFalse(st.streaming)
        assertTrue(st.messages.none { it.streaming || it.toolRunning })
    }

    @Test
    fun textAfterAToolCallStartsANewSegmentBelowTheToolRow() {
        val st = run(
            ChatEvent.AssistantDelta("Running the command first."),
            ChatEvent.ToolStarted("terminal", "echo ok"),
            ChatEvent.ToolCompleted("terminal", "ok", 300),
            ChatEvent.AssistantDelta("It printed ok."),
            ChatEvent.Completed,
        )
        assertEquals(
            listOf(MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT),
            st.messages.map { it.role },
        )
        assertEquals("Running the command first.", st.messages[0].text)
        assertEquals("It printed ok.", st.messages[2].text)
        assertTrue(st.messages.none { it.streaming })
        // Both segments get rendered blocks on completion.
        assertTrue(st.messages.filter { it.role == MessageRole.ASSISTANT }.all { it.blocks.isNotEmpty() })
    }

    @Test
    fun toolStartClosesTheOpenTextSegment() {
        val st = run(ChatEvent.AssistantDelta("thinking aloud"), ChatEvent.ToolStarted("browser", "open"))
        assertFalse(st.messages[0].streaming)
        assertTrue(st.messages[1].toolRunning)
    }

    @Test
    fun finishStreamClearsOrphanRunningTool() {
        // Interrupt mid-tool: no tool.complete ever arrives.
        val st = finishStream(run(ChatEvent.ToolStarted("browser", "open")), aid)
        assertFalse(st.streaming)
        assertFalse(st.messages.single().toolRunning)
    }
}
