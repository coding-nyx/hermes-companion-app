package app.hermes.companion.data.remote

import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomJsonTest {
    private val roomJson = """
        {"id":"r-1","title":"triage","participants":[{"profile":"coder","glyph":"COD"},{"profile":"ops","glyph":"OPS"}],
         "policy":{"max_rounds":3,"turn_timeout_s":180},"seq":4,"message_count":4,"busy":true,"speaking":"ops",
         "updated_at":1757200000.5,"last":{"seq":4,"speaker":"coder","text":"done"}}
    """.trimIndent()

    @Test
    fun roomListAndEnvelopeParse() {
        val rooms = RoomJson.rooms("""{"rooms":[$roomJson]}""")
        assertEquals(1, rooms.size)
        val r = rooms[0]
        assertEquals("r-1", r.id)
        assertEquals(listOf("COD", "OPS"), r.participants.map { it.glyph })
        assertEquals(3, r.maxRounds)
        assertTrue(r.busy)
        assertEquals("ops", r.speaking)
        assertEquals(1757200000500L, r.updatedAtEpochMs)
        assertEquals("done", r.lastText)
        assertEquals("r-1", RoomJson.roomEnvelope("""{"room":$roomJson}""")?.id)
    }

    @Test
    fun roomParsesV2FieldsAndRemoteParticipants() {
        val body = """
            {"id":"r-2","title":"x","participants":[{"id":"coder","profile":"coder","host":null,"glyph":"COD"},{"id":"bishop@hub-11","profile":"bishop","host":"hub-11","glyph":"BIS"}],
             "policy":{"mode":"moderated","max_turns":16,"max_rounds":2,"moderator":"coder","hands":"bishop@hub-11"},"mode":"moderated",
             "state":"paused","pause_reason":"stall","turns_used":5,"budget":22,"hands":"bishop@hub-11","moderator":"coder",
             "approval":{"request_id":"apr-2","kind":"clarify","speaker":"coder","command":"which env?","choices":["ok","deny"]},
             "summary":{"seq":4,"speaker":"coder","text":"TTL is the cause","kind":"summary"},"seq":6,"message_count":6,"busy":false}
        """.trimIndent()
        val r = RoomJson.room(kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)!!
        assertEquals(listOf("coder", "bishop@hub-11"), r.participants.map { it.id })
        assertEquals("hub-11", r.participants[1].host)
        assertEquals(("moderated" to "paused"), r.mode to r.state)
        assertEquals(("stall" to 5), r.pauseReason to r.turnsUsed)
        assertEquals(22, r.budget)
        assertEquals(16, r.maxTurns)
        assertEquals("bishop@hub-11", r.hands)
        assertEquals("coder", r.moderator)
        assertEquals("apr-2", r.approval?.requestId)
        assertEquals("clarify", r.approval?.kind)
        assertEquals("TTL is the cause", r.summary)
        assertEquals("converse", RoomJson.room(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"r-3","participants":[]}""") as kotlinx.serialization.json.JsonObject)!!.mode)
    }

    @Test
    fun historyExpandsToolsAndMapsRoles() {
        val body = """
            {"room":$roomJson,"seq":3,"messages":[
              {"seq":1,"speaker":"operator","text":"who owns it?","ts_ms":1,"tools":[]},
              {"seq":2,"speaker":"coder","text":"mint fails. @OPS?","ts_ms":2,"round":1,
               "tools":[{"name":"terminal","detail":"journalctl -u gw"}]},
              {"seq":3,"speaker":"ops","text":"PASS","ts_ms":3,"round":2,"passed":true,"tools":[]}
            ]}
        """.trimIndent()
        val (room, rows) = RoomJson.history(body)
        assertEquals("r-1", room?.id)
        assertEquals(listOf(MessageRole.USER, MessageRole.TOOL, MessageRole.ASSISTANT, MessageRole.ASSISTANT), rows.map { it.role })
        assertEquals("rm-1", rows[0].id)
        assertNull(rows[0].speaker)
        assertEquals("terminal", rows[1].toolName)
        assertEquals("coder", rows[1].speaker)
        assertEquals("coder", rows[2].speaker)
        assertEquals("mint fails. @OPS?", rows[2].text)
        assertTrue(rows[3].passed)
        assertEquals("", rows[3].text)
        assertEquals("ops", rows[3].speaker)
    }

    @Test
    fun eventsMapToChatEvents() {
        assertEquals(ChatEvent.TurnStarted("coder", "t-1", 1), RoomJson.event("""{"type":"room.turn.start","room_id":"r-1","speaker":"coder","turn_id":"t-1","round":1}"""))
        assertEquals(ChatEvent.AssistantDelta("hel", "coder"), RoomJson.event("""{"type":"room.delta","speaker":"coder","turn_id":"t-1","text":"hel"}"""))
        assertEquals(ChatEvent.ToolStarted("terminal", "ls", "coder"), RoomJson.event("""{"type":"room.tool.start","speaker":"coder","name":"terminal","detail":"ls"}"""))
        assertEquals(ChatEvent.ToolCompleted("terminal", "ls", 40, "coder"), RoomJson.event("""{"type":"room.tool.complete","speaker":"coder","name":"terminal","detail":"ls","duration_ms":40}"""))
        assertEquals(ChatEvent.TurnEnded("coder", "t-1", 5, false, ""), RoomJson.event("""{"type":"room.turn.end","speaker":"coder","turn_id":"t-1","seq":5,"passed":false,"error":""}"""))
        assertEquals(ChatEvent.RoomPost(7, "hi"), RoomJson.event("""{"type":"room.post","message":{"seq":7,"speaker":"operator","text":"hi"}}"""))
        assertEquals(ChatEvent.Completed, RoomJson.event("""{"type":"room.idle","room_id":"r-1","seq":5}"""))
        assertNull(RoomJson.event("""{"type":"room.heartbeat"}"""))
        assertEquals(ChatEvent.RoomReady(9, null), RoomJson.event("""{"type":"room.ready","room_id":"r-1","seq":9}"""))
        assertEquals(ChatEvent.RoomState("paused", "budget", 12, 18, 40), RoomJson.event("""{"type":"room.state","room_id":"r-1","state":"paused","reason":"budget","turns_used":12,"max_turns":12,"budget":18,"seq":40}"""))
        val approval = RoomJson.event("""{"type":"room.approval","room_id":"r-1","speaker":"coder","turn_id":"t-1","request_id":"apr-1","kind":"approval","command":"rm -rf build","choices":["once","deny"]}""") as ChatEvent.Approval
        assertEquals("apr-1", approval.prompt.requestId)
        assertEquals("coder", approval.prompt.speaker)
        assertEquals(listOf("once", "deny"), approval.prompt.choices)
        assertEquals(ChatEvent.PromptExpired("apr-1"), RoomJson.event("""{"type":"room.approval.resolved","request_id":"apr-1","decision":"once"}"""))
        val updated = RoomJson.event("""{"type":"room.updated","room_id":"r-1","room":{"id":"r-1","title":"renamed","participants":[],"policy":{}}}""") as ChatEvent.RoomUpdated
        assertEquals("renamed", updated.room.title)
        assertNull(RoomJson.event("not json"))
        assertFalse(RoomJson.event("""{"type":"room.delta","speaker":"coder","text":""}""") is ChatEvent.AssistantDelta)
    }
}
