package app.hermes.companion.data.remote

import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.GatewayHello
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class RpcEvent(
    val type: String,
    val sessionId: String? = null,
    val payload: JsonObject? = null,
)

internal sealed class RpcInbound {
    data class Result(val id: String, val result: JsonObject) : RpcInbound()
    data class Error(val id: String, val code: Int, val message: String) : RpcInbound()
    data class Event(val event: RpcEvent) : RpcInbound()
}

internal object RpcCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(id: String, method: String, paramsJson: String): String =
        """{"jsonrpc":"2.0","id":${id.jsonQuote()},"method":${method.jsonQuote()},"params":$paramsJson}"""

    fun parseLine(raw: String): RpcInbound? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val id = root.idString()
        val err = root["error"] as? JsonObject
        if (err != null && id != null) {
            return RpcInbound.Error(
                id = id,
                code = err["code"]?.jsonPrimitive?.intOrNull ?: -1,
                message = err.str("message").ifBlank { "rpc error" },
            )
        }
        if (root.containsKey("result") && id != null) {
            return RpcInbound.Result(id, root["result"].asObject())
        }
        val event = parseEvent(root) ?: return null
        return RpcInbound.Event(event)
    }

    fun parseHello(raw: String): GatewayHello? {
        val inbound = parseLine(raw) as? RpcInbound.Event ?: return parseReadyLoose(raw)
        if (inbound.event.type != "gateway.ready") return null
        return helloFrom(inbound.event.payload)
    }

    fun toChatEvent(event: RpcEvent): ChatEvent? {
        val payload = event.payload
        val type = event.type
        val text = payload.str("text").ifBlank { payload.str("delta") }.ifBlank { payload.str("content") }
        val name = payload.str("name").ifBlank { payload.str("tool") }.ifBlank { payload.str("tool_name") }
        val rawArgs = payload?.get("args")?.toString() ?: payload?.get("arguments")?.toString().orEmpty()
        val detail = payload.str("detail").ifBlank { payload.str("context") }
            .ifBlank { payload.str("command") }.ifBlank { payload.str("input") }
            .ifBlank { extractToolArgsPreview(rawArgs) }
            .ifBlank { text }
        return when {
            type == "tool.start" || type == "tool.started" || type.startsWith("tool.start") ->
                ChatEvent.ToolStarted(name.ifBlank { "tool" }, detail)
            type == "tool.complete" || type == "tool.completed" || type.startsWith("tool.complete") ->
                ChatEvent.ToolCompleted(
                    name.ifBlank { "tool" },
                    detail,
                    payload?.get("duration_ms")?.jsonPrimitive?.longOrNull ?: 0L,
                )
            type == "message.delta" || type == "assistant.delta" || type == "token" ->
                if (text.isBlank()) null else ChatEvent.AssistantDelta(text)
            type.endsWith(".expire") || type.endsWith(".expired") -> {
                val id = payload.str("request_id").ifBlank { payload.str("id") }
                if (id.isBlank()) null else ChatEvent.PromptExpired(id)
            }
            type.contains("approval") || type.contains("clarify") ||
                type.contains("sudo") || type.contains("secret") -> {
                if (!type.contains("request")) return null
                val kind = when {
                    type.contains("clarify") -> "clarify"
                    type.contains("sudo") -> "sudo"
                    type.contains("secret") -> "secret"
                    else -> "approval"
                }
                val prompt = parseApproval(payload?.toString() ?: return null, kind) ?: return null
                ChatEvent.Approval(prompt)
            }
            type == "message.complete" || type == "run.completed" || type == "done" ||
                type.endsWith(".complete") && type.startsWith("message") ->
                ChatEvent.Completed
            else -> null
        }
    }

    private fun parseEvent(root: JsonObject): RpcEvent? {
        val method = root.str("method")
        val params = root["params"] as? JsonObject
        val type = params.str("type").ifBlank { root.str("type") }.ifBlank {
            if (method == "event") "" else method
        }
        if (type.isBlank() && method != "event" && method != "gateway.ready") return null
        val payload = params?.get("payload") as? JsonObject
            ?: root["payload"] as? JsonObject
            ?: params
        val sessionId = params.str("session_id").ifBlank { payload.str("session_id") }
            .ifBlank { root.str("session_id") }
            .ifBlank { null }
        val resolved = type.ifBlank { if (method == "gateway.ready") "gateway.ready" else "" }
        if (resolved.isBlank()) return null
        return RpcEvent(type = resolved, sessionId = sessionId, payload = payload)
    }

    private fun parseReadyLoose(raw: String): GatewayHello? {
        val root = runCatching { json.parseToJsonElement(raw.trim()).jsonObject }.getOrNull() ?: return null
        val event = parseEvent(root) ?: return null
        if (event.type != "gateway.ready") return null
        return helloFrom(event.payload)
    }

    private fun helloFrom(payload: JsonObject?): GatewayHello =
        GatewayHello(
            changeEvents = payload?.get("change_events")?.jsonPrimitive?.booleanOrNull ?: false,
            heartbeat = payload?.get("heartbeat")?.jsonPrimitive?.booleanOrNull ?: false,
            instanceId = payload.str("instance_id").ifBlank { payload.str("process_id") },
        )

    private fun JsonElement?.asObject(): JsonObject = when (this) {
        is JsonObject -> this
        is JsonArray -> JsonObject(mapOf("items" to this))
        is JsonNull, null -> JsonObject(emptyMap())
        else -> JsonObject(mapOf("value" to this))
    }

    private fun JsonObject.idString(): String? {
        val el = this["id"] ?: return null
        val prim = el as? JsonPrimitive ?: return el.toString()
        return prim.contentOrNull ?: prim.intOrNull?.toString() ?: prim.longOrNull?.toString()
    }

    private fun JsonObject?.str(key: String): String =
        this?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun String.jsonQuote(): String = buildString {
        append('"')
        for (ch in this@jsonQuote) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
        append('"')
    }
}
