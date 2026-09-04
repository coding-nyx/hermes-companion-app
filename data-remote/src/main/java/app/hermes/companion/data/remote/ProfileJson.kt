package app.hermes.companion.data.remote

import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.RewindPolicy
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.DashboardStatus
import app.hermes.companion.model.DeviceTicket
import app.hermes.companion.model.PairingStatus
import app.hermes.companion.model.PlatformStatus
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.SessionChange
import app.hermes.companion.model.SessionRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal val DashboardJson = Json { ignoreUnknownKeys = true }

internal fun parseStatus(body: String): DashboardStatus {
    val root = DashboardJson.parseToJsonElement(body).jsonObject
    val providers = root["auth_providers"]?.jsonArrayOrNull()
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()
    val gateway = root["gateway"]
    val running = root.bool("gateway_running") || when (gateway) {
        is JsonObject -> gateway.bool("running") || gateway.str("status") == "running"
        is JsonPrimitive -> gateway.booleanOrNull == true || gateway.contentOrNull == "running"
        else -> false
    }
    val gatewayState = root.str("gateway_state").ifBlank {
        when (gateway) {
            is JsonObject -> gateway.str("status").ifBlank { gateway.str("state") }
            is JsonPrimitive -> gateway.contentOrNull.orEmpty()
            else -> ""
        }
    }.ifBlank { if (running) "running" else "" }
    val platformsEl = root["gateway_platforms"]
        ?: (gateway as? JsonObject)?.get("platforms")
        ?: root["platforms"]
    return DashboardStatus(
        authRequired = root.bool("auth_required"),
        authProviders = providers,
        version = root.str("version").ifBlank { root.str("agent_version") },
        gatewayRunning = running,
        gatewayState = gatewayState,
        platforms = parsePlatforms(platformsEl),
        memoryPressure = pressureOf(root["memory"]),
        diskPressure = pressureOf(root["disk"]),
        exitReason = root.str("gateway_exit_reason"),
    )
}

private fun parsePlatforms(el: JsonElement?): List<PlatformStatus> = when (el) {
    is JsonObject -> el.map { (name, value) ->
        val obj = value as? JsonObject
        PlatformStatus(
            name = name,
            state = obj?.str("state").orEmpty().ifBlank {
                (value as? JsonPrimitive)?.contentOrNull.orEmpty()
            },
            error = obj?.str("error_message").orEmpty()
                .ifBlank { obj?.str("error").orEmpty() }
                .ifBlank { obj?.str("error_code").orEmpty() },
        )
    }
    is JsonArray -> el.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val name = obj.str("name").ifBlank { obj.str("platform") }.ifBlank { obj.str("id") }
        if (name.isBlank()) null
        else PlatformStatus(
            name = name,
            state = obj.str("state"),
            error = obj.str("error_message").ifBlank { obj.str("error") }.ifBlank { obj.str("error_code") },
        )
    }
    else -> emptyList()
}

private fun pressureOf(el: JsonElement?): String {
    val obj = el as? JsonObject ?: return (el as? JsonPrimitive)?.contentOrNull.orEmpty()
    return obj.str("pressure")
}

internal fun parseProfiles(body: String): List<ProfileRef> {
    val root = DashboardJson.parseToJsonElement(body)
    val arr = when (root) {
        is JsonArray -> root
        is JsonObject -> root["profiles"]?.jsonArrayOrNull()
            ?: root["items"]?.jsonArrayOrNull()
            ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return arr.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }?.let { ProfileScope.toRef(it) }
            is JsonObject -> {
                val id = el.str("id").ifBlank { el.str("profile_id") }.ifBlank { el.str("name") }
                if (id.isBlank()) null
                else ProfileScope.toRef(
                    id = id,
                    displayName = el.str("display_name").ifBlank { el.str("displayName") }.ifBlank { id },
                    model = el.str("model").ifBlank { el.str("model_name") },
                    gateway = when {
                        el.bool("gateway_running") -> "running"
                        el.str("gateway").isNotBlank() -> el.str("gateway")
                        else -> "stopped"
                    },
                    sessionCount = el.int("session_count"),
                )
            }
            else -> null
        }
    }
}

internal fun parseSessions(body: String): List<SessionRef> {
    val root = DashboardJson.parseToJsonElement(body)
    val arr = when (root) {
        is JsonArray -> root
        is JsonObject -> root["sessions"]?.jsonArrayOrNull()
            ?: root["items"]?.jsonArrayOrNull()
            ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val id = obj.str("id").ifBlank { obj.str("session_id") }
        val profile = obj.str("profile").ifBlank { obj.str("profile_id") }.ifBlank { obj.str("profile_name") }
        if (id.isBlank()) null
        else {
            val started = obj.long("updated_at").takeIf { it > 0 }
                ?: obj.long("started_at")
            val epochMs = when {
                started > 10_000_000_000L -> started
                started > 0L -> started * 1000
                else -> 0L
            }
            SessionRef(
                id = id,
                profileId = profile,
                title = obj.str("title")
                    .ifBlank { obj.str("display_name") }
                    .ifBlank { obj.str("preview") }
                    .ifBlank { id },
                updatedAtEpochMs = epochMs,
                unread = obj.bool("unread") || obj.int("unread_count") > 0 ||
                    obj["ended_at"] == null || obj["ended_at"] is JsonNull,
            )
        }
    }
}

internal fun parseMessages(body: String): List<ChatMessage> {
    val root = DashboardJson.parseToJsonElement(body)
    val arr = when (root) {
        is JsonArray -> root
        is JsonObject -> root["messages"]?.jsonArrayOrNull()
            ?: root["items"]?.jsonArrayOrNull()
            ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return arr.mapIndexedNotNull { index, el ->
        val obj = el as? JsonObject ?: return@mapIndexedNotNull null
        val role = when (obj.str("role").lowercase()) {
            "user" -> MessageRole.USER
            "tool" -> MessageRole.TOOL
            else -> MessageRole.ASSISTANT
        }
        val id = obj.str("row_id").ifBlank { obj.str("_row_id") }.ifBlank { obj.str("id") }
            .ifBlank { "m$index" }
        val text = messageText(obj)
        ChatMessage(
            id = id,
            role = role,
            text = text,
            toolName = obj.str("name").ifBlank { obj.str("tool") }.ifBlank { null },
            toolDetail = obj.str("detail").ifBlank { null },
        )
    }
}

internal fun parseApproval(body: String, kindHint: String = "approval"): ApprovalPrompt? {
    if (body.isBlank() || body == "null" || body == "{}") return null
    val root = runCatching { DashboardJson.parseToJsonElement(body) }.getOrNull() ?: return null
    val obj = when (root) {
        is JsonObject -> root["approval"] as? JsonObject
            ?: root["clarify"] as? JsonObject
            ?: root["prompt"] as? JsonObject
            ?: root
        else -> return null
    }
    return parsePromptObject(obj, kindHint)
}

internal fun parsePromptObject(obj: JsonObject, kindHint: String = "approval"): ApprovalPrompt? {
    val id = obj.str("request_id").ifBlank { obj.str("id") }
    val command = obj.str("command")
        .ifBlank { obj.str("summary") }
        .ifBlank { obj.str("text") }
        .ifBlank { obj.str("question") }
        .ifBlank { obj.str("prompt") }
        .ifBlank { obj.str("message") }
        .ifBlank { kindHint }
    if (id.isBlank()) return null
    val choices = choiceList(obj["choices"] ?: obj["options"])
        .ifEmpty {
            when (kindHint) {
                "sudo", "secret" -> listOf("submit", "deny")
                "clarify" -> listOf("ok", "deny")
                else -> listOf("once", "deny")
            }
        }
    return ApprovalPrompt(
        requestId = id,
        kind = obj.str("kind").ifBlank { kindHint },
        command = command,
        choices = choices,
    )
}

internal fun parseSessionChange(payload: JsonObject, fallbackProfile: String): SessionChange? {
    val id = payload.str("id").ifBlank { payload.str("session_id") }
    if (id.isBlank()) return null
    val profile = payload.str("profile").ifBlank { payload.str("profile_id") }.ifBlank { fallbackProfile }
    if (profile.isBlank()) return null
    val started = payload.long("updated_at").takeIf { it > 0 } ?: payload.long("started_at")
    val epochMs = when {
        started > 10_000_000_000L -> started
        started > 0L -> started * 1000
        else -> 0L
    }
    val op = payload.str("op").ifBlank { payload.str("action") }.ifBlank { "upsert" }
    return SessionChange(
        op = op,
        session = SessionRef(
            id = id,
            profileId = profile,
            title = payload.str("title").ifBlank { payload.str("display_name") }.ifBlank { payload.str("preview") }
                .ifBlank { id },
            updatedAtEpochMs = epochMs,
            unread = payload.bool("unread") || op.lowercase() == "upsert",
        ),
    )
}

internal fun parseRpcSession(result: JsonObject, fallbackProfile: String): SessionRef {
    val live = result.str("session_id").ifBlank { result.str("id") }
    val stored = result.str("stored_session_id").ifBlank { live }
    require(stored.isNotBlank()) { "session create returned no id" }
    val info = result["info"] as? JsonObject
    val profile = result.str("profile").ifBlank { result.str("profile_id") }
        .ifBlank { info.str("profile_name") }.ifBlank { fallbackProfile }
    return SessionRef(
        id = stored,
        profileId = profile,
        title = result.str("title").ifBlank { "new thread" },
        updatedAtEpochMs = result.long("updated_at").takeIf { it > 0 } ?: result.long("started_at"),
        unread = false,
    )
}

internal fun parsePairing(body: String): PairingStatus {
    val root = runCatching { DashboardJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw IllegalArgumentException("pair response was not json")
    val status = root.str("status").ifBlank { root.str("state") }.ifBlank { "pending" }
    return PairingStatus(
        status = status,
        deviceId = root.str("device_id"),
        profileId = root.str("profile").ifBlank { root.str("profile_id") },
        credential = root.str("credential"),
    )
}

internal fun parseDeviceTicket(body: String): DeviceTicket {
    val root = runCatching { DashboardJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw IllegalArgumentException("device ticket response was not json")
    val ticket = root.str("ticket")
    if (ticket.isBlank()) throw IllegalArgumentException("device register returned no ticket")
    val caps = root["capabilities"]?.jsonArrayOrNull()
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()
    val ttl = root["ttl_sec"]?.jsonPrimitive?.intOrNull ?: 30
    return DeviceTicket(ticket = ticket, capabilities = caps, ttlSec = ttl)
}

internal fun parseWsTicket(body: String): String {
    val root = runCatching { DashboardJson.parseToJsonElement(body) }.getOrNull()
    val obj = root as? JsonObject
    val ticket = obj.str("ticket").ifBlank { obj.str("ws_ticket") }
    if (ticket.isNotBlank()) return ticket
    throw IllegalArgumentException("ws-ticket response had no ticket")
}

internal fun parseSurvivorRowIds(result: JsonObject): List<Long?>? {
    val el = result["survivor_user_row_ids"] ?: return null
    val arr = el as? JsonArray ?: return null
    return RewindPolicy.survivorRowIds(
        arr.map { item ->
            when (item) {
                JsonNull -> null
                is JsonPrimitive -> item.longOrNull ?: item.intOrNull ?: item.contentOrNull
                else -> null
            }
        },
    )
}

internal fun parseCreatedSession(body: String, fallbackProfile: String): SessionRef {
    val root = DashboardJson.parseToJsonElement(body)
    val obj = when (root) {
        is JsonObject -> root["session"]?.let { it as? JsonObject } ?: root
        else -> error("session create returned no object")
    }
    val id = obj.str("id").ifBlank { obj.str("session_id") }
    require(id.isNotBlank()) { "session create returned no id" }
    val profile = obj.str("profile").ifBlank { obj.str("profile_id") }.ifBlank { fallbackProfile }
    return SessionRef(
        id = id,
        profileId = profile,
        title = obj.str("title").ifBlank { "new thread" },
        updatedAtEpochMs = obj.long("updated_at"),
        unread = false,
    )
}

private fun messageText(obj: JsonObject): String {
    val direct = obj.str("text")
    if (direct.isNotBlank()) return direct
    return when (val content = obj["content"] ?: obj["parts"]) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("") { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull.orEmpty()
                is JsonObject -> el.str("text").ifBlank { el.str("content") }
                else -> ""
            }
        }
        is JsonObject -> content.str("text").ifBlank { content.str("content") }
        else -> ""
    }
}

private fun choiceList(el: JsonElement?): List<String> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> item.str("label").ifBlank { item.str("id") }.ifBlank { item.str("value") }
                .takeIf { it.isNotBlank() }
            else -> null
        }
    }
}

private fun JsonObject?.str(key: String): String {
    val el = this?.get(key) as? JsonPrimitive ?: return ""
    return el.contentOrNull.orEmpty()
}

private fun JsonObject.bool(key: String): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: false

private fun JsonObject.int(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: 0

private fun JsonObject.long(key: String): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: 0L

private fun JsonElement.jsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()
