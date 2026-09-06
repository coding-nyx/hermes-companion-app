package app.hermes.companion.data.remote

import app.hermes.companion.model.ChatBlock
import app.hermes.companion.model.ChatBlockKind
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.RoomParticipant
import app.hermes.companion.model.RoomRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Wire shapes for the plugin-owned `/companion/rooms` routes (hermes-plugin/rooms.py). */
internal object RoomJson {
    private fun JsonObject?.str(key: String): String = this?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject?.int(key: String, default: Int = 0): Int = this?.get(key)?.jsonPrimitive?.intOrNull ?: default
    private fun JsonObject?.bool(key: String): Boolean = this?.get(key)?.jsonPrimitive?.booleanOrNull ?: false
    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray

    fun room(obj: JsonObject?): RoomRef? {
        val id = obj.str("id")
        if (id.isBlank()) return null
        val parts = obj.arr("participants")?.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val profile = p.str("profile")
            if (profile.isBlank()) null else RoomParticipant(profile, p.str("glyph").ifBlank { profile.take(3).uppercase() })
        }.orEmpty()
        val updated = obj?.get("updated_at")?.jsonPrimitive?.doubleOrNull ?: 0.0
        return RoomRef(
            id = id,
            title = obj.str("title").ifBlank { parts.joinToString(" + ") { it.glyph } },
            participants = parts,
            maxRounds = obj.obj("policy").int("max_rounds", 2),
            seq = obj.int("seq"),
            messageCount = obj.int("message_count"),
            busy = obj.bool("busy"),
            speaking = obj.str("speaking").ifBlank { null },
            updatedAtEpochMs = (updated * 1000).toLong(),
            lastText = obj.obj("last").str("text"),
        )
    }

    fun rooms(body: String): List<RoomRef> {
        val root = DashboardJson.parseToJsonElement(body) as? JsonObject
        return root.arr("rooms")?.mapNotNull { room(it as? JsonObject) }.orEmpty()
    }

    fun roomEnvelope(body: String): RoomRef? =
        room((DashboardJson.parseToJsonElement(body) as? JsonObject).obj("room"))

    /** A stored room line → transcript row. Operator lines become USER rows; agents ASSISTANT. */
    fun message(obj: JsonObject?): ChatMessage? {
        val seq = obj.int("seq", -1)
        if (seq < 0) return null
        val speaker = obj.str("speaker")
        val text = obj.str("text")
        val passed = obj.bool("passed")
        val error = obj.str("error")
        val tools = obj.arr("tools")?.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            t.str("name").ifBlank { "tool" } to t.str("detail")
        }.orEmpty()
        val operator = speaker.isBlank() || speaker == "operator"
        return ChatMessage(
            id = "rm-$seq",
            role = if (operator) MessageRole.USER else MessageRole.ASSISTANT,
            text = when {
                passed -> ""
                error.isNotBlank() && text.isBlank() -> ""
                else -> text
            },
            speaker = if (operator) null else speaker,
            passed = passed,
            toolDetail = if (error.isNotBlank()) error else null,
            blocks = if (!operator && !passed && text.isNotBlank()) listOf(ChatBlock(kind = ChatBlockKind.TEXT, text = text)) else emptyList(),
        ).let { row ->
            // Tool summaries travel as separate TOOL rows placed before the text, like live turns.
            row
        }.also { toolRowsFor[seq] = tools }
    }

    /** Side channel so [history] can expand tool summaries into TOOL rows (one per tool). */
    private val toolRowsFor = mutableMapOf<Int, List<Pair<String, String>>>()

    fun history(body: String): Pair<RoomRef?, List<ChatMessage>> {
        val root = DashboardJson.parseToJsonElement(body) as? JsonObject
        toolRowsFor.clear()
        val rows = mutableListOf<ChatMessage>()
        root.arr("messages")?.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val msg = message(obj) ?: return@forEach
            val speaker = msg.speaker
            toolRowsFor[obj.int("seq", -1)]?.forEachIndexed { i, (name, detail) ->
                rows += ChatMessage(
                    id = "${msg.id}-t$i",
                    role = MessageRole.TOOL,
                    text = detail,
                    toolName = name,
                    toolDetail = detail,
                    speaker = speaker,
                )
            }
            rows += msg
        }
        toolRowsFor.clear()
        return room(root.obj("room")) to rows
    }

    /** One frame from `/companion/rooms/events`. Null for heartbeats and unknown types. */
    fun event(raw: String): ChatEvent? {
        val obj = runCatching { DashboardJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val type = obj.str("type")
        val speaker = obj.str("speaker")
        return when (type) {
            "room.delta" -> {
                val text = obj.str("text")
                if (text.isEmpty()) null else ChatEvent.AssistantDelta(text, speaker)
            }
            "room.tool.start" -> ChatEvent.ToolStarted(obj.str("name").ifBlank { "tool" }, obj.str("detail"), speaker)
            "room.tool.complete" -> ChatEvent.ToolCompleted(
                obj.str("name").ifBlank { "tool" },
                obj.str("detail"),
                obj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                speaker,
            )
            "room.turn.start" -> ChatEvent.TurnStarted(speaker, obj.str("turn_id"), obj.int("round", 1))
            "room.turn.end" -> ChatEvent.TurnEnded(
                speaker,
                obj.str("turn_id"),
                obj.int("seq"),
                obj.bool("passed"),
                obj.str("error"),
            )
            "room.post" -> {
                val m = obj.obj("message")
                ChatEvent.RoomPost(m.int("seq"), m.str("text"))
            }
            "room.idle", "room.interrupted" -> ChatEvent.Completed
            else -> null
        }
    }
}
