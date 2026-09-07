package app.hermes.companion.domain

import app.hermes.companion.model.SessionRef

/**
 * Session-rail hygiene: LazyColumn keys must be unique, and create + bus refetch
 * can race-insert the same id. Keep one row per id; created/newest wins order.
 */
object SessionLists {
    /** Prepend [created] once; drop any prior row with the same id. Drafts never land on the rail. */
    fun prepend(created: SessionRef, existing: List<SessionRef>): List<SessionRef> {
        val rest = existing.filterNot { it.id == created.id || DraftThread.isDraft(it) }
        if (DraftThread.isDraft(created)) return rest
        return listOf(created) + rest
    }

    /**
     * Dedupe by id (first occurrence wins), then order by updatedAt descending.
     * Stable id tie-break so Compose keys stay deterministic across equal timestamps.
     * Local draft placeholders are not threads and never survive normalize.
     */
    fun normalize(sessions: List<SessionRef>): List<SessionRef> =
        sessions
            .filterNot { DraftThread.isDraft(it) }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<SessionRef> { it.updatedAtEpochMs }
                    .thenBy { it.id },
            )
}
