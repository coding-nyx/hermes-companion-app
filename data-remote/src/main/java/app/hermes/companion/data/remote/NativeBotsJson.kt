package app.hermes.companion.data.remote

import app.hermes.companion.model.*
import kotlinx.serialization.json.*

/** Native Hermes contracts pinned in docs/protocol/bots.md; never parse Companion room envelopes here. */
internal object NativeBotsJson {
    fun roster(origin: String, root: JsonObject): BotRoster {
        val rows = root["profiles"] as? JsonArray ?: throw DashboardException("bots_protocol", "Host returned no bot roster")
        return BotRoster(rows.mapNotNull { value ->
            val row = value as? JsonObject ?: return@mapNotNull null
            val profile = row.text("name").ifBlank { row.text("id") }
            if (profile.isBlank()) return@mapNotNull null
            val meta = (row["ui_meta"] as? JsonObject)?.get("hermes-bots") as? JsonObject ?: buildJsonObject {}
            val canonical = row["canonical_session"] as? JsonObject
            NativeBot(BotOwner(origin.trimEnd('/'), profile),
                meta.text("title").ifBlank { row.text("display_name") }.ifBlank { profile },
                meta.text("description").ifBlank { row.text("description") }, row.text("model"), row.text("provider"),
                meta.flag("hidden"), meta.flag("pinned"), meta.text("sectionName"), row.flag("has_avatar"),
                canonical?.text("id")?.takeIf(String::isNotBlank), canonical?.text("resolved_id")?.takeIf(String::isNotBlank),
                canonical?.text("preview").orEmpty(), epoch(canonical?.get("last_active")), meta.toString(),
                (row["ui_meta_revisions"] as? JsonObject)?.let { (it["hermes-bots"] as? JsonPrimitive)?.intOrNull ?: 0 })
        }, root.text("install_id"), root.flag("bot_mode_protocol"))
    }

    fun capabilities(root: JsonObject) = NativeGroupCapabilities(root.flag("driver"), strings(root["methods"]).toSet(),
        strings(root["features"]).toSet(), (root["seatable_profiles"] as? JsonArray)?.let { strings(it).toSet() })

    fun group(row: JsonObject): NativeGroup {
        val id = row.text("room_id"); val name = row.text("name"); val authority = row.text("authority_gateway_id")
        val epoch = row.number("authority_epoch")
        if (id.isBlank() || name.isBlank() || authority.isBlank() || epoch < 1)
            throw DashboardException("groups_protocol", "Invalid group identity from host")
        val members = (row["members"] as? JsonArray).orEmpty().map { value ->
            val member = value as? JsonObject ?: throw DashboardException("groups_protocol", "Invalid group member")
            NativeGroupMember(member.text("member_id"), member.text("profile"), member.text("handle"), member.text("display_name").ifBlank { member.text("profile") },
                (member["target"] as? JsonObject)?.text("kind") == "peer")
        }
        return NativeGroup(id, name, members, authority, epoch, row.number("latest_seq"), epoch(row["updated_at"]))
    }

    fun event(row: JsonObject): NativeGroupEvent {
        val actor = row["actor"] as? JsonObject ?: buildJsonObject {}
        val payload = row["payload"] as? JsonObject ?: buildJsonObject {}
        val id = row.text("event_id"); val seq = row.number("seq"); val kind = row.text("kind")
        if (id.isBlank() || seq < 1 || kind.isBlank()) throw DashboardException("groups_protocol", "Invalid group event")
        return NativeGroupEvent(id, seq, kind,
            actor.text("display_name").ifBlank { actor.text("profile") }.ifBlank { actor.text("id") },
            payload.text("text").ifBlank { if (kind == "turn.failed") payload.text("error") else "" },
            payload.text("thread_id"), payload.toString(),
            actor.text("member_id").ifBlank { actor.text("id") }, actor.text("profile"))
    }

    fun page(root: JsonObject): NativeGroupPage {
        val authority = root["authority"] as? JsonObject ?: throw DashboardException("groups_protocol", "Missing group authority")
        val events = root["events"] as? JsonArray ?: throw DashboardException("groups_protocol", "Missing group events")
        return NativeGroupPage(events.map { event(it.jsonObject) }, root.number("cursor"), root.number("latest_seq"),
            root.flag("has_more"), authority.text("gateway_id"), authority.number("epoch"))
    }

    fun snapshot(root: JsonObject): NativeGroupSnapshot {
        val group = group(root["room"]?.jsonObject ?: throw DashboardException("groups_protocol", "Missing group"))
        val driver = root["driver_status"] as? JsonObject
        val actions = (driver?.get("pending_actions") as? JsonArray).orEmpty().mapNotNull { value ->
            val row = value as? JsonObject ?: return@mapNotNull null
            val kind = row.text("kind"); val member = row.text("member_id"); val task = row.text("task_id")
            val generation = (row["execution_generation"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            if (member.isBlank() || task.isBlank() || generation < 1) return@mapNotNull null
            NativeGroupAction(kind, member, task, generation, row.text("request_id"), row.text("command"), strings(row["choices"]))
        }
        return NativeGroupSnapshot(group, driver?.flag("working") == true,
            driver?.flag("blocked") == true, actions)
    }

    fun member(member: NativeGroupMember) = buildJsonObject {
        put("member_id", member.id); put("profile", member.profile); put("handle", member.handle); put("display_name", member.name)
    }
    fun strings(value: JsonElement?) = (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
    fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
    private fun epoch(value: JsonElement?): Long = (value as? JsonPrimitive)?.doubleOrNull?.let {
        if (it > 10_000_000_000) it.toLong() else (it * 1000).toLong()
    } ?: 0
}
