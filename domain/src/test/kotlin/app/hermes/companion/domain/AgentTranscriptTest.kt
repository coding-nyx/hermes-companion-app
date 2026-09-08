package app.hermes.companion.domain

import app.hermes.companion.model.AgentEvent
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptTest {
    @Test
    fun deltasStreamIntoOneRowAndToolsSplitParagraphs() {
        val st = AgentTranscript.replay(
            listOf(
                AgentEvent.Ready("sess-1", "claude-x"),
                AgentEvent.User("run it"),
                AgentEvent.TurnStart,
                AgentEvent.Delta("Running "),
                AgentEvent.Delta("now."),
                AgentEvent.ToolStart("tu1", "Bash", "echo hi"),
                AgentEvent.ToolComplete("tu1", "Bash", "hi\n", false, 12),
                AgentEvent.Delta("Done."),
                AgentEvent.TurnEnd("Running now.Done.", false, 0.25, 900),
            ),
        )
        val roles = st.messages.map { it.role }
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT), roles)
        assertEquals("Running now.", st.messages[1].text)
        assertFalse(st.messages[1].streaming)
        val tool = st.messages[2]
        assertEquals("Bash", tool.toolName)
        assertEquals("echo hi", tool.toolDetail)
        assertEquals("hi\n", tool.text)
        assertFalse(tool.toolRunning)
        assertEquals("Done.", st.messages[3].text)
        assertFalse(st.turnActive)
        assertEquals(0.25, st.costUsd, 1e-9)
        assertEquals("sess-1", st.agentSessionId)
        assertEquals(9, st.seq)
    }

    @Test
    fun approvalIsHeldUntilResolvedAndClearedOnTurnEnd() {
        var st = AgentTranscript.replay(
            listOf(
                AgentEvent.User("write a file"), AgentEvent.TurnStart,
                AgentEvent.ToolStart("tu2", "Write", "/tmp/x"),
                AgentEvent.Approval(ApprovalPrompt(requestId = "req-1", command = "/tmp/x")),
            ),
        )
        assertEquals("req-1", st.approval?.requestId)
        assertTrue(st.turnActive)
        assertTrue(st.messages.last().toolRunning)
        st = AgentTranscript.reduce(st, AgentEvent.ApprovalResolved("req-1", "deny"))
        assertNull(st.approval)
        st = AgentTranscript.reduce(st, AgentEvent.ToolComplete("tu2", "Write", "denied by operator", true, 0))
        assertTrue(st.messages.last().toolDetail!!.startsWith("failed"))
        st = AgentTranscript.reduce(st, AgentEvent.TurnEnd("", true, null, null))
        assertEquals("turn failed", st.lastError)
        assertFalse(st.turnActive)
    }

    @Test
    fun turnEndWithoutDeltasAddsTheFinalTextOnce() {
        val st = AgentTranscript.replay(listOf(AgentEvent.User("hi"), AgentEvent.TurnStart, AgentEvent.TurnEnd("pong", false, 0.0, 1)))
        assertEquals(listOf("hi", "pong"), st.messages.map { it.text })
        val again = AgentTranscript.replay(listOf(AgentEvent.User("hi"), AgentEvent.TurnStart, AgentEvent.Delta("pong"), AgentEvent.TurnEnd("pong", false, 0.0, 1)))
        assertEquals(2, again.messages.size)
    }

    @Test
    fun exitClosesEverything() {
        val st = AgentTranscript.replay(listOf(AgentEvent.User("x"), AgentEvent.TurnStart, AgentEvent.Delta("half"), AgentEvent.Exit(1, "boom")))
        assertTrue(st.exited)
        assertEquals(1, st.exitCode)
        assertEquals("boom", st.lastError)
        assertFalse(st.turnActive)
        assertFalse(st.messages.last().streaming)
        assertFalse(st.live)
    }
}
