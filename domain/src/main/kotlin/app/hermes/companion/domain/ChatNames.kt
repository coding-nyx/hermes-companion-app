package app.hermes.companion.domain

import app.hermes.companion.model.*

/** Presentation names never replace session titles, profile ids or transcript roles on the wire. */
object ChatNames {
    fun profiles(origin: String?, profiles: List<ProfileRef>, bots: List<NativeBot>): List<ProfileRef> {
        val local = bots.filter { origin != null && it.owner.origin == GatewayBook.key(origin) }
        return (profiles.map { profile ->
            local.firstOrNull { it.owner.profile == profile.id }?.title?.takeIf(String::isNotBlank)
                ?.let { profile.copy(displayName = it) } ?: profile
        } + local.filter { bot -> profiles.none { it.id == bot.owner.profile } }
            .map { ProfileScope.toRef(it.owner.profile, it.title) })
    }

    fun agent(profile: String?, profiles: List<ProfileRef>): String =
        profiles.firstOrNull { it.id == profile }?.displayName?.takeIf(String::isNotBlank)
            ?: profile?.takeIf(String::isNotBlank) ?: "Agent"

    fun session(session: SessionRef, profiles: List<ProfileRef>): String =
        if (session.title == "Bot Chat") agent(session.profileId, profiles) else session.title.ifBlank { session.id }

    fun groupSpeaker(event: NativeGroupEvent, group: NativeGroup, profiles: List<ProfileRef>): String {
        if (event.kind == "message.user") return "You"
        val member = group.members.firstOrNull { it.id == event.actorId && event.actorId.isNotBlank() }
            ?: group.members.singleOrNull { it.profile == event.actorProfile && event.actorProfile.isNotBlank() }
            ?: group.members.singleOrNull { event.actor.isNotBlank() && event.actor in listOf(it.id, it.profile, it.handle, it.name) }
        // A structured actor carries identity even when the host's display_name is "Hermes".
        val profile = member?.profile ?: event.actorProfile.takeIf(String::isNotBlank)
        return profiles.firstOrNull { it.id == profile && member?.peer != true }?.displayName?.takeIf(String::isNotBlank)
            ?: member?.name?.takeIf(String::isNotBlank) ?: event.actor.takeIf(String::isNotBlank)
            ?: profile ?: "Agent"
    }
}
