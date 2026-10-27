package app.hermes.companion.data.remote

import app.hermes.companion.model.*
import app.hermes.companion.data.remote.NativeBotsJson.text
import app.hermes.companion.data.remote.NativeBotsJson.flag
import kotlinx.serialization.json.*

/** Every method captures the gateway AND profile; no method consults a UI-selected destination. */
interface NativeBotsApi {
    suspend fun roster(owner: BotOwner): BotRoster
    suspend fun configure(bot: NativeBot, edit: BotEdit)
    suspend fun create(owner: BotOwner, edit: BotEdit)
    suspend fun canonical(bot: NativeBot, operationId: String): SessionRef
    suspend fun capabilities(owner: BotOwner): NativeGroupCapabilities
    suspend fun groups(owner: BotOwner): List<NativeGroup>
    suspend fun createGroup(operation: NativeBotOperation): NativeGroup
    suspend fun group(owner: BotOwner, id: String): NativeGroupSnapshot
    suspend fun log(owner: BotOwner, id: String, after: Long): NativeGroupPage
    suspend fun send(operation: NativeBotOperation): NativeGroupEvent
    suspend fun rename(owner: BotOwner, id: String, name: String, eventId: String): NativeGroup
    suspend fun stop(owner: BotOwner, id: String, cancelId: String)
    suspend fun disband(owner: BotOwner, id: String, cancelId: String)
    suspend fun approve(owner: BotOwner, id: String, action: NativeGroupAction, choice: String)
    suspend fun retry(owner: BotOwner, id: String, action: NativeGroupAction)
}

class DashboardNativeBotsApi(private val clients: HostClientPool) : NativeBotsApi {
    private suspend fun request(owner: BotOwner, method: String, fields: JsonObject = buildJsonObject {}) =
        clients.forOrigin(owner.origin).botRequest(owner.origin, owner.profile, method, fields)
    private fun room(id: String, extra: JsonObject = buildJsonObject {}) = JsonObject(extra + ("room_id" to JsonPrimitive(id)))

    override suspend fun roster(owner: BotOwner) = NativeBotsJson.roster(owner.origin, request(owner, "profiles.list"))

    override suspend fun configure(bot: NativeBot, edit: BotEdit) {
        require(edit.title.isNotBlank() && edit.model.isBlank() == edit.provider.isBlank()) { "Name is required; model and provider must be entered together" }
        val original = Json.parseToJsonElement(bot.metadataJson).jsonObject
        val section = edit.section.trim()
        val sectionId = if (section.isBlank()) null else if (section == bot.section && original.text("sectionId").isNotBlank()) original.text("sectionId")
            else roster(bot.owner).bots.asSequence().filter { it.section == section }
                .map { Json.parseToJsonElement(it.metadataJson).jsonObject.text("sectionId") }.firstOrNull { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
        val meta = Json.parseToJsonElement(bot.metadataJson).jsonObject.toMutableMap().apply {
            put("title", JsonPrimitive(edit.title.trim())); put("description", JsonPrimitive(edit.description.trim()))
            put("hidden", JsonPrimitive(edit.hidden))
            if (sectionId == null) { remove("sectionId"); remove("sectionName") }
            else { put("sectionId", JsonPrimitive(sectionId)); put("sectionName", JsonPrimitive(section)) }
        }
        val result = request(bot.owner, "profiles.configure", buildJsonObject {
            put("name", bot.owner.profile); putJsonObject("ui_meta") { put("hermes-bots", JsonObject(meta)) }
            bot.metadataRevision?.let { putJsonObject("ui_meta_expected_revisions") { put("hermes-bots", it) } }
            put("description", edit.description.trim())
            if (edit.model.isNotBlank()) { put("model", edit.model.trim()); put("provider", edit.provider.trim()) }
        })
        val applied = result["applied"] as? JsonObject
        if (result.flag("confirm_required")) throw DashboardException("bot_model_confirmation", result.text("confirm_message").ifBlank { "Confirm this model change on the host" })
        if (!result.flag("ok") || applied?.flag("ui_meta") != true || applied["ui_meta_conflicts"] != null ||
            (edit.model.isNotBlank() && applied.flag("model") != true))
            throw DashboardException("bot_save", "Bot settings were not fully saved. Refresh before editing again.")
    }

    override suspend fun create(owner: BotOwner, edit: BotEdit) {
        require(edit.profile.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "Use a profile name with letters, numbers, hyphens or underscores" }
        require(edit.model.isBlank() == edit.provider.isBlank()) { "Enter both model and provider, or leave both empty" }
        val result = request(owner, "profiles.create", buildJsonObject {
            put("name", edit.profile); put("description", edit.description.trim()); put("clone_channels", false)
            if (edit.model.isNotBlank()) { put("model", edit.model.trim()); put("provider", edit.provider.trim()) }
        })
        if (!result.flag("ok")) throw DashboardException("bot_create", "Host did not confirm bot creation")
        // Re-read the new profile: preserves metadata and uses the server's actual CAS revision.
        val bot = roster(owner).bots.firstOrNull { it.owner.profile == edit.profile }
            ?: throw DashboardException("bot_create", "Profile was created. Refresh the roster to finish setup.")
        configure(bot, edit)
    }

    override suspend fun canonical(bot: NativeBot, operationId: String): SessionRef {
        val owner = bot.owner
        // Exact-title lookup includes hidden rows, independent of recency and cached pointers.
        suspend fun lookup(): SessionRef? {
            val result = request(owner, "session.list", buildJsonObject { put("title", "Bot Chat"); put("include_hidden", true); put("limit", 200) })
            val rows = result["sessions"] as? JsonArray ?: throw DashboardException("bot_registry", "Cannot check Bot Chat identity")
            val row = rows.mapNotNull { it as? JsonObject }.firstOrNull { it.text("title") == "Bot Chat" } ?: return null
            val id = row.text("resolved_id").ifBlank { row.text("id") }
            if (id.isBlank()) throw DashboardException("bot_registry", "Invalid Bot Chat identity")
            return SessionRef(id, owner.profile, "Bot Chat", 0)
        }
        val existing = lookup()
        if (bot.metadataJson == "{}") configure(bot, BotEdit(owner.profile, bot.title, bot.description, hidden = bot.hidden, section = bot.section))
        existing?.let { return it }
        // A known canonical row must not be forked on a transient empty registry response.
        if (bot.canonicalId != null) throw DashboardException("bot_registry", "Bot Chat is temporarily unavailable. Try opening it again.")
        val createParams = buildJsonObject {
            put("title", "Bot Chat"); put("hidden", true); put("follow_profile_config", true); put("idempotency_key", operationId)
        }
        val created = try { request(owner, "session.create", createParams) } catch (e: DashboardException) {
            // Older hosts reject this field before executing the method. Only that explicit
            // schema rejection is safe to retry; never strip identity after a lost response.
            val message = e.message.orEmpty()
            if (e.code !in setOf("rpc_4000", "rpc_-32602") || !message.startsWith("invalid params for session.create:") ||
                !message.contains("idempotency_key: Extra inputs are not permitted")) throw e
            request(owner, "session.create", JsonObject(createParams - "idempotency_key"))
        }
        val live = created.text("session_id"); val stored = created.text("stored_session_id").ifBlank { live }
        if (live.isBlank() || stored.isBlank()) throw DashboardException("bot_create", "Host returned no Bot Chat identity")
        try {
            request(owner, "session.title", buildJsonObject { put("session_id", live); put("title", "Bot Chat") })
        } catch (e: DashboardException) {
            if (e.message.orEmpty().contains("already in use", ignoreCase = true)) lookup()?.let { return it }
            throw e // Lost acknowledgments are reconciled on the next explicit open, never auto-replayed.
        }
        return SessionRef(stored, owner.profile, "Bot Chat", System.currentTimeMillis())
    }

    override suspend fun capabilities(owner: BotOwner) = NativeBotsJson.capabilities(request(owner, "groups.capabilities"))
    override suspend fun groups(owner: BotOwner): List<NativeGroup> {
        val result = mutableListOf<NativeGroup>(); var offset = 0
        do {
            val page = request(owner, "groups.list", buildJsonObject { put("limit", 100); put("offset", offset) })
            val rows = page["rooms"] as? JsonArray ?: throw DashboardException("groups_protocol", "Missing group list")
            result += rows.map { NativeBotsJson.group(it.jsonObject) }
            val next = (page["next_offset"] as? JsonPrimitive)?.intOrNull ?: break
            if (next <= offset) throw DashboardException("groups_protocol", "Group list cursor did not advance")
            offset = next
        } while (result.size < 10_000)
        return result.distinctBy { it.id }
    }
    override suspend fun createGroup(operation: NativeBotOperation): NativeGroup {
        require(operation.members.size in 2..6 && operation.members.map { it.profile }.distinct().size == operation.members.size)
        val result = request(operation.owner, "groups.create", room(operation.target, buildJsonObject {
            put("name", operation.text.trim()); put("members", JsonArray(operation.members.map(NativeBotsJson::member)))
        }))
        return NativeBotsJson.group(result["room"]!!.jsonObject)
    }
    override suspend fun group(owner: BotOwner, id: String) = NativeBotsJson.snapshot(request(owner, "groups.state", room(id)))
    override suspend fun log(owner: BotOwner, id: String, after: Long): NativeGroupPage {
        val result = request(owner, "groups.log", room(id, buildJsonObject { put("since_seq", after); put("limit", 200) }))
        (result["events"] as? JsonArray)?.forEach {
            val event = it.jsonObject
            if (event.text("room_id") != id) throw DashboardException("groups_protocol", "Group history belongs to another room")
        }
        return NativeBotsJson.page(result)
    }
    override suspend fun send(operation: NativeBotOperation): NativeGroupEvent {
        val result = request(operation.owner, "groups.send", room(operation.target, buildJsonObject {
            put("event_id", operation.id); putJsonObject("payload") { put("text", operation.text); put("thread_id", operation.thread) }
        }))
        if (!result.flag("accepted")) throw DashboardException("group_send", "Host rejected this message")
        return NativeBotsJson.event(result["event"]!!.jsonObject)
    }
    override suspend fun rename(owner: BotOwner, id: String, name: String, eventId: String) = NativeBotsJson.group(
        request(owner, "groups.rename", room(id, buildJsonObject { put("name", name.trim()); put("event_id", eventId) }))["room"]!!.jsonObject)
    override suspend fun stop(owner: BotOwner, id: String, cancelId: String) { request(owner, "groups.stop", room(id, buildJsonObject { put("cancel_id", cancelId) })) }
    override suspend fun disband(owner: BotOwner, id: String, cancelId: String) { request(owner, "groups.disband", room(id, buildJsonObject { put("cancel_id", cancelId) })) }
    override suspend fun approve(owner: BotOwner, id: String, action: NativeGroupAction, choice: String) {
        require(action.kind == "approval" && action.request.isNotBlank() && choice in listOf("once", "deny"))
        request(owner, "groups.approve", room(id, buildJsonObject {
            put("member_id", action.member); put("task_id", action.task); put("execution_generation", action.generation)
            put("request_id", action.request); put("choice", choice)
        }))
    }
    override suspend fun retry(owner: BotOwner, id: String, action: NativeGroupAction) {
        request(owner, "groups.retry", room(id, buildJsonObject { put("member_id", action.member); put("task_id", action.task); put("execution_generation", action.generation) }))
    }
}
