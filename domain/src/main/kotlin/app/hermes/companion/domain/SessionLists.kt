package app.hermes.companion.domain

import app.hermes.companion.model.SessionRef

/**
 * Session-rail hygiene: LazyColumn keys must be unique, and create + bus refetch
 * can race-insert the same id. Keep one row per id; created/newest wins order.
 */
object SessionLists {
    /** Prepend [created] once; drop any prior row with the same id. */
    fun prepend(created: SessionRef, existing: List<SessionRef>): List<SessionRef> =
        listOf(created) + existing.filterNot { it.id == created.id }

    /**
     * Dedupe by id (first occurrence wins), then order by updatedAt descending.
     * Stable id tie-break so Compose keys stay deterministic across equal timestamps.
     */
    fun normalize(sessions: List<SessionRef>): List<SessionRef> =
        sessions
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<SessionRef> { it.updatedAtEpochMs }
                    .thenBy { it.id },
            )
}
