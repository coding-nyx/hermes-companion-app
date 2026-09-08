package app.hermes.companion.domain

import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.SessionChange
import app.hermes.companion.model.SessionRef

/**
 * One profile, one world. Threads never mix. Wire calls always carry a profile id.
 */
object ProfileScope {
    fun glyph(id: String, displayName: String = id): String {
        val src = displayName.ifBlank { id }.filter { it.isLetterOrDigit() }
        return src.take(3).uppercase().padEnd(3, '·')
    }

    fun toRef(
        id: String,
        displayName: String = id,
        model: String = "",
        gateway: String = "",
        sessionCount: Int = 0,
    ): ProfileRef = ProfileRef(
        id = id,
        displayName = displayName.ifBlank { id },
        glyph = glyph(id, displayName),
        model = model,
        gateway = gateway,
        sessionCount = sessionCount,
    )

    fun resolveActive(profiles: List<ProfileRef>, stickyId: String?): ProfileRef? {
        if (profiles.isEmpty()) return null
        return profiles.find { it.id == stickyId }
            ?: profiles.find { it.id == "default" }
            ?: profiles.first()
    }

    /** Server-wins list, then fail-closed filter so a leaky payload cannot paint the wrong world. */
    fun visibleSessions(all: List<SessionRef>, activeProfileId: String?): List<SessionRef> {
        val id = activeProfileId ?: return emptyList()
        return all.filter { it.profileId == id }
    }

    fun requireProfileId(profileId: String?): String {
        require(!profileId.isNullOrBlank()) { "profile must be set on every hermes call" }
        return profileId
    }

    fun requireOwnedSession(session: SessionRef, activeProfileId: String?): SessionRef {
        val profile = requireProfileId(activeProfileId)
        require(session.profileId == profile) {
            "session ${session.id} belongs to ${session.profileId}, not $profile"
        }
        return session
    }

    /**
     * Apply a `sessions.changed` patch. Foreign-profile rows are ignored.
     * Missing profile on the event is a leak-class gap — caller should refetch.
     */
    fun applyChange(
        sessions: List<SessionRef>,
        change: SessionChange,
        activeProfileId: String?,
    ): List<SessionRef> {
        val profile = activeProfileId ?: return sessions
        if (change.session.profileId != profile) return sessions
        val op = change.op.lowercase()
        if (op == "delete" || op == "remove") {
            return sessions.filter { it.id != change.session.id }
        }
        val idx = sessions.indexOfFirst { it.id == change.session.id }
        return if (idx >= 0) {
            // Patches are sparse: fold into the known row instead of replacing it (A18.9).
            sessions.toMutableList().also { it[idx] = SessionLists.merge(it[idx], change.session) }
        } else {
            SessionLists.prepend(change.session, sessions)
        }
    }
}
