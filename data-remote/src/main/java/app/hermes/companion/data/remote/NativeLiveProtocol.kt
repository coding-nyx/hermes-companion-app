package app.hermes.companion.data.remote

import app.hermes.companion.model.NativeLiveEvent
import kotlinx.serialization.json.*

/** Installed lab's public Live dialect. No provisional caption submits a chat prompt. */
object NativeLiveProtocol {
    const val CHANNEL = "oai-events"
    const val MAX_FRAME_BYTES = 262_144
    fun decode(text: String): NativeLiveEvent? {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "Live event is too large" }
        val obj = Json.parseToJsonElement(text).jsonObject
        fun field(name: String) = (obj[name] as? JsonPrimitive)?.contentOrNull
        return when (field("type")) {
            "session.started", "session.updated" -> NativeLiveEvent.Started((obj["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull)
            "session.input_transcript.delta" -> field("delta")?.let { NativeLiveEvent.Caption("user", it) }
            "session.output_transcript.delta" -> field("delta")?.let { NativeLiveEvent.Caption("assistant", it) }
            "session.delegation.created" -> (obj["delegation"] as? JsonObject)?.let { d ->
                val id = (d["id"] as? JsonPrimitive)?.contentOrNull
                val target = (d["target"] as? JsonPrimitive)?.contentOrNull ?: "client"
                if (target != "client" || id.isNullOrBlank() || id.length > 256) null else NativeLiveEvent.Delegation(id)
            }
            "session.closed" -> NativeLiveEvent.Closed(field("reason"))
            "error" -> (obj["error"] as? JsonObject).let { error -> NativeLiveEvent.Error(
                (error?.get("code") as? JsonPrimitive)?.contentOrNull,
                (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: field("message") ?: "Live provider failed") }
            else -> null
        }
    }
    fun append(text: String, delegationId: String? = null, speak: Boolean = true): List<String> = chunks(text).map {
        buildJsonObject {
            put("type", if (speak) "session.commentary.append" else "session.thinking.append")
            put("content", it)
            put("delegation_id", delegationId?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
    }
    fun chunks(text: String, limit: Int = 500): List<String> {
        require(limit >= 4)
        val result = mutableListOf<String>(); val part = StringBuilder(); var bytes = 0
        text.trim().codePoints().forEach { point ->
            val char = String(Character.toChars(point)); val size = char.toByteArray(Charsets.UTF_8).size
            if (bytes + size > limit) { result += part.toString(); part.setLength(0); bytes = 0 }
            part.append(char); bytes += size
        }
        if (part.isNotEmpty()) result += part.toString()
        return result
    }
}
