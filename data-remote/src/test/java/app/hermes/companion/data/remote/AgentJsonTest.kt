package app.hermes.companion.data.remote

import app.hermes.companion.model.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJsonTest {
    @Test
    fun structuredFramesMapToAgentEvents() {
        val ev = AgentJson.event("""{"type":"agent.delta","session_id":"s1","text":"hi"}""")
        assertEquals(AgentEvent.Delta("hi"), (ev as AgentJson.Event.Structured).event)
        val appr = AgentJson.event("""{"type":"agent.approval","session_id":"s1","request_id":"r1","kind":"approval","tool":"Bash","command":"rm -rf x","choices":["once","deny"]}""")
        val prompt = ((appr as AgentJson.Event.Structured).event as AgentEvent.Approval).prompt
        assertEquals("r1", prompt.requestId)
        assertEquals("rm -rf x", prompt.command)
        assertEquals("Bash", prompt.speaker)
        val end = AgentJson.event("""{"type":"agent.turn.end","session_id":"s1","text":"done","is_error":false,"cost_usd":0.5,"duration_ms":12,"claude_session_id":"c1"}""")
        assertEquals(AgentEvent.TurnEnd("done", false, 0.5, 12), (end as AgentJson.Event.Structured).event)
        val tool = AgentJson.event("""{"type":"agent.tool.complete","session_id":"s1","tool_id":"t1","name":"Bash","detail":"out","error":true,"duration_ms":3}""")
        assertEquals(AgentEvent.ToolComplete("t1", "Bash", "out", true, 3), (tool as AgentJson.Event.Structured).event)
        assertNull(AgentJson.event("""{"type":"agent.heartbeat"}"""))
    }

    @Test
    fun replayFrameCarriesSessionEventsAndApproval() {
        val raw = """{"type":"agent.ready.replay","session_id":"s1","session":{"id":"s1","tool":"claude","mode":"structured","status":"waiting_approval","created_at":1.5,"updated_at":2.5},""" +
            """"events":[{"type":"agent.user","session_id":"s1","text":"go"},{"type":"agent.turn.start","session_id":"s1"}],"approval":{"request_id":"r9","tool":"Write","command":"/tmp/x"}}"""
        val ev = AgentJson.event(raw) as AgentJson.Event.Replay
        assertEquals("structured", ev.session?.mode)
        assertEquals(listOf(AgentEvent.User("go"), AgentEvent.TurnStart), ev.events)
        assertEquals("r9", ev.approval?.requestId)
    }

    @Test
    fun paneErrorFramesStayAsBefore() {
        val err = AgentJson.event("""{"type":"agent.error","error":"not_found"}""")
        assertTrue(err is AgentJson.Event.Error)
        val (events, approval, session) = AgentJson.transcriptBody("""{"events":[{"type":"agent.exit","session_id":"s1","exit_code":0,"stderr":""}],"approval":null,"session":{"id":"s1","tool":"codex","status":"exited"}}""")
        assertEquals(listOf(AgentEvent.Exit(0, "")), events)
        assertNull(approval)
        assertEquals("exited", session?.status)
    }

    @Test
    fun dirsListingParses() {
        val d = AgentJson.dirs("""{"path":"/home/nyx","parent":null,"roots":[{"path":"/home/nyx","label":"HOME"},{"path":"/tmp","label":"TMP"}],""" +
            """"dirs":[{"name":"Projects","path":"/home/nyx/Projects","git":false,"project":false},{"name":"app","path":"/home/nyx/app","git":true,"project":true}],"recent":["/tmp"],"truncated":false,"git":false}""")
        assertEquals("/home/nyx", d.path)
        assertNull(d.parent)
        assertEquals(listOf("HOME", "TMP"), d.roots.map { it.label })
        assertEquals(listOf("Projects", "app"), d.dirs.map { it.name })
        assertTrue(d.dirs[1].git)
        assertEquals(listOf("/tmp"), d.recent)
    }
}
