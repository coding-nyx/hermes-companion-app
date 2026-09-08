package app.hermes.companion.data.remote

import app.hermes.companion.model.AgentDir
import app.hermes.companion.model.AgentDirListing
import app.hermes.companion.model.AgentEvent
import app.hermes.companion.model.AgentRoot
import app.hermes.companion.model.AgentPane
import app.hermes.companion.model.AgentSession
import app.hermes.companion.model.AgentTool
import app.hermes.companion.model.ApprovalPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Wire shapes for the plugin-owned `/companion/agents` routes (hermes-plugin/agents.py). */
object AgentJson {
    private fun JsonObject?.str(key: String): String = this?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject?.int(key: String, default: Int = 0): Int = this?.get(key)?.jsonPrimitive?.intOrNull ?: default
    private fun JsonObject?.bool(key: String): Boolean = this?.get(key)?.jsonPrimitive?.booleanOrNull ?: false
    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray
    private fun JsonObject?.epochMs(key: String): Long = ((this?.get(key)?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()

    fun tool(obj: JsonObject?): AgentTool? {
        val id = obj.str("id")
        if (id.isBlank()) return null
        return AgentTool(
            id = id,
            label = obj.str("label").ifBlank { id },
            glyph = obj.str("glyph").ifBlank { id.take(2).uppercase() },
            installed = obj.bool("installed"),
            path = obj.str("path"),
            version = obj.str("version"),
            modes = obj.arr("modes")?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().ifEmpty { listOf("pty") },
            installHint = obj.str("install_hint"),
            loginHint = obj.str("login_hint"),
        )
    }

    /** `{tools, tmux, default_cwd}` → tools, tmux present, default cwd. */
    fun tools(body: String): Triple<List<AgentTool>, Boolean, String> {
        val root = DashboardJson.parseToJsonElement(body) as? JsonObject
        return Triple(root.arr("tools")?.mapNotNull { tool(it as? JsonObject) }.orEmpty(), root.bool("tmux"), root.str("default_cwd"))
    }

    fun session(obj: JsonObject?): AgentSession? {
        val id = obj.str("id")
        if (id.isBlank()) return null
        return AgentSession(
            id = id,
            tool = obj.str("tool").ifBlank { "shell" },
            mode = obj.str("mode").ifBlank { "pty" },
            cwd = obj.str("cwd"),
            title = obj.str("title"),
            command = obj.str("command"),
            status = obj.str("status").ifBlank { "starting" },
            exitCode = obj?.get("exit_code")?.jsonPrimitive?.intOrNull,
            lastLine = obj.str("last_line"),
            cols = obj.int("cols", 100),
            rows = obj.int("rows", 40),
            createdAtEpochMs = obj.epochMs("created_at"),
            updatedAtEpochMs = obj.epochMs("updated_at"),
            attach = obj.str("attach"),
        )
    }

    fun sessions(body: String): List<AgentSession> {
        val root = DashboardJson.parseToJsonElement(body) as? JsonObject
        return root.arr("sessions")?.mapNotNull { session(it as? JsonObject) }.orEmpty()
    }

    fun sessionEnvelope(body: String): AgentSession? =
        session((DashboardJson.parseToJsonElement(body) as? JsonObject).obj("session"))

    fun pane(obj: JsonObject?): AgentPane {
        val cursor = obj.arr("cursor")?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()
        return AgentPane(
            ansi = obj.str("ansi"),
            cursorX = cursor.getOrNull(0) ?: 0,
            cursorY = cursor.getOrNull(1) ?: 0,
            cols = obj.int("cols", 100),
            rows = obj.int("rows", 40),
        )
    }

    fun paneBody(body: String): Pair<AgentPane, AgentSession?> {
        val root = DashboardJson.parseToJsonElement(body) as? JsonObject
        return pane(root) to session(root.obj("session"))
    }

    sealed class Event {
        data class Pane(val pane: AgentPane) : Event()
        data class Status(val session: AgentSession) : Event()
        data class Error(val code: String) : Event()
        /** Structured session: one normalised transcript event. */
        data class Structured(val event: AgentEvent) : Event()
        /** Structured session: the first frame replays the transcript so far. */
        data class Replay(val session: AgentSession?, val events: List<AgentEvent>, val approval: ApprovalPrompt?) : Event()
    }

    /** One frame from `/companion/agents/events`. Null for heartbeats and unknown types. */
    fun event(raw: String): Event? {
        val obj = runCatching { DashboardJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return when (obj.str("type")) {
            "agent.pane" -> Event.Pane(pane(obj))
            "agent.status" -> session(obj.obj("session"))?.let { Event.Status(it) }
            "agent.error" -> structured(obj)?.let { Event.Structured(it) } ?: Event.Error(obj.str("error").ifBlank { "agent_error" })
            "agent.ready.replay" -> Event.Replay(
                session(obj.obj("session")),
                obj.arr("events")?.mapNotNull { structured(it as? JsonObject) }.orEmpty(),
                approval(obj.obj("approval")),
            )
            else -> structured(obj)?.let { Event.Structured(it) }
        }
    }

    fun approval(obj: JsonObject?): ApprovalPrompt? {
        val id = obj.str("request_id")
        if (id.isBlank()) return null
        return ApprovalPrompt(
            requestId = id,
            kind = obj.str("kind").ifBlank { "approval" },
            command = obj.str("command").ifBlank { obj.str("tool") },
            choices = obj.arr("choices")?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().ifEmpty { listOf("once", "deny") },
            speaker = obj.str("tool").ifBlank { null },
        )
    }

    /** `agent.*` transcript events of a structured session; null for pane/status/heartbeat frames. */
    fun structured(obj: JsonObject?): AgentEvent? = when (obj.str("type")) {
        "agent.ready" -> AgentEvent.Ready(obj.str("claude_session_id"), obj.str("model"))
        "agent.user" -> AgentEvent.User(obj.str("text"))
        "agent.delta" -> AgentEvent.Delta(obj.str("text"))
        "agent.tool.start" -> AgentEvent.ToolStart(obj.str("tool_id"), obj.str("name").ifBlank { "tool" }, obj.str("detail"))
        "agent.tool.complete" -> AgentEvent.ToolComplete(
            obj.str("tool_id"), obj.str("name").ifBlank { "tool" }, obj.str("detail"), obj.bool("error"),
            obj?.get("duration_ms")?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L,
        )
        "agent.approval" -> approval(obj)?.let { AgentEvent.Approval(it) }
        "agent.approval.resolved" -> AgentEvent.ApprovalResolved(obj.str("request_id"), obj.str("decision"))
        "agent.turn.start" -> AgentEvent.TurnStart
        "agent.turn.end" -> AgentEvent.TurnEnd(
            obj.str("text"), obj.bool("is_error"),
            obj?.get("cost_usd")?.jsonPrimitive?.doubleOrNull, obj?.get("duration_ms")?.jsonPrimitive?.doubleOrNull?.toLong(),
        )
        "agent.error" -> if (obj.str("session_id").isNotBlank()) AgentEvent.Error(obj.str("error")) else null
        "agent.exit" -> AgentEvent.Exit(obj?.get("exit_code")?.jsonPrimitive?.intOrNull, obj.str("stderr"))
        else -> null
    }

    fun dirs(body: String): AgentDirListing {
        val root = DashboardJson.parseToJsonElement(body) as? JsonObject
        return AgentDirListing(
            path = root.str("path"),
            parent = root?.get("parent")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            roots = root.arr("roots")?.mapNotNull { (it as? JsonObject)?.let { o -> AgentRoot(o.str("path"), o.str("label")) } }.orEmpty().filter { it.path.isNotBlank() },
            dirs = root.arr("dirs")?.mapNotNull { (it as? JsonObject)?.let { o -> AgentDir(o.str("name"), o.str("path"), o.bool("git"), o.bool("project")) } }.orEmpty().filter { it.path.isNotBlank() },
            recent = root.arr("recent")?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            truncated = root.bool("truncated"),
            git = root.bool("git"),
        )
    }

    /** `GET …/transcript?after=` → events, pending approval, session. */
    fun transcriptBody(body: String): Triple<List<AgentEvent>, ApprovalPrompt?, AgentSession?> {
        val root = DashboardJson.parseToJsonElement(body) as? JsonObject
        return Triple(root.arr("events")?.mapNotNull { structured(it as? JsonObject) }.orEmpty(), approval(root.obj("approval")), session(root.obj("session")))
    }
}
