package app.hermes.companion.domain

import app.hermes.companion.model.SessionRef

/**
 * Local placeholder for the composer. It is never written to the host or the Room
 * cache; [session.create] runs only after the operator actually sends a turn.
 */
object DraftThread {
    const val ID = "local:draft"

    fun isDraft(id: String?): Boolean = id == ID

    fun isDraft(session: SessionRef?): Boolean = session != null && session.id == ID

    /** Host / cache / history calls are only valid for a real session id. */
    fun isPersisted(id: String?): Boolean = !id.isNullOrBlank() && !isDraft(id)

    fun placeholder(profileId: String): SessionRef = SessionRef(
        id = ID,
        profileId = profileId,
        title = "new",
        updatedAtEpochMs = 0L,
    )
}
