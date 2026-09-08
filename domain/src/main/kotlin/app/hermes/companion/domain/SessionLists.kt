package app.hermes.companion.domain

import app.hermes.companion.model.SessionRef

/** Operator-chosen rail order. Persisted per phone; [CREATED] is the default. */
enum class ThreadSort(val label: String) {
    /** Newest thread first by host `started_at`; rows without one fall back to last activity. */
    CREATED("CREATED"),
    /** Most recent activity first. */
    ACTIVE("ACTIVE"),
    /** Title A→Z, case-insensitive; equal titles fall back to newest first. */
    TITLE("A–Z");

    companion object {
        val DEFAULT = CREATED

        fun parse(raw: String?): ThreadSort =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Session-rail hygiene. Hermes is the source of truth but its lists come from several places
 * (RPC page, REST page, Room cache, `sessions.changed` patches) that disagree on fields and
 * on tie-breaks. Everything that lands in `CompanionState.sessions` goes through here so the
 * rail is deterministic: one row per id, one comparator, one direction for every tie-break.
 */
object SessionLists {
    /** Prepend [created] once; drop any prior row with the same id. Drafts never land on the rail. */
    fun prepend(created: SessionRef, existing: List<SessionRef>): List<SessionRef> {
        val rest = existing.filterNot { it.id == created.id || DraftThread.isDraft(it) }
        if (DraftThread.isDraft(created)) return rest
        return listOf(created) + rest
    }

    /**
     * Dedupe by id (duplicates are [merge]d, so a richer row never loses fields to a sparser copy),
     * then order by [sort]. Local draft placeholders are not threads and never survive normalize.
     */
    fun normalize(sessions: List<SessionRef>, sort: ThreadSort = ThreadSort.DEFAULT): List<SessionRef> {
        val byId = LinkedHashMap<String, SessionRef>()
        for (row in sessions) {
            if (DraftThread.isDraft(row)) continue
            val prior = byId[row.id]
            byId[row.id] = if (prior == null) row else merge(prior, row)
        }
        return sort(byId.values.toList(), sort)
    }

    /** Order only; assumes unique ids. */
    fun sort(sessions: List<SessionRef>, sort: ThreadSort = ThreadSort.DEFAULT): List<SessionRef> =
        sessions.sortedWith(comparator(sort))

    /** Effective creation instant: host `started_at`, else last activity. Never 0 unless both are. */
    fun createdKey(row: SessionRef): Long =
        if (row.createdAtEpochMs > 0L) row.createdAtEpochMs else row.updatedAtEpochMs

    /** Effective activity instant: host `updated_at`, else creation. */
    fun activeKey(row: SessionRef): Long =
        if (row.updatedAtEpochMs > 0L) row.updatedAtEpochMs else row.createdAtEpochMs

    /**
     * Newest first on the chosen key, then the other timestamp, then id **descending**.
     * Hermes ids are `YYYYMMDD_HHMMSS_hash`, so id-desc is still newest-first when a host
     * sends no timestamps at all — and it matches the Room query, so cache → remote never flips.
     */
    fun comparator(sort: ThreadSort): Comparator<SessionRef> {
        val newestFirst = compareByDescending<SessionRef> { createdKey(it) }
            .thenByDescending { activeKey(it) }
            .thenByDescending { it.id }
        return when (sort) {
            ThreadSort.CREATED -> newestFirst
            ThreadSort.ACTIVE -> compareByDescending<SessionRef> { activeKey(it) }
                .thenByDescending { createdKey(it) }
                .thenByDescending { it.id }
            ThreadSort.TITLE -> compareBy<SessionRef> { it.title.trim().lowercase() }
                .then(newestFirst)
        }
    }

    /**
     * Fold [patch] into [existing] without losing what the patch does not carry.
     * `sessions.changed` payloads are sparse (often just id + updated_at, sometimes not even that),
     * and a Room row may know `created_at` while the live page does not.
     * - title: patch wins only when it is a real title (not blank, not the id echo)
     * - timestamps: max of both, so a stale copy can never move a thread backwards
     * - unread: patch wins (the bus is the only source that flips it)
     * - ended: sticky once true unless the patch explicitly reopens with a newer timestamp
     */
    fun merge(existing: SessionRef, patch: SessionRef): SessionRef {
        val patchTitle = patch.title.trim()
        val title = if (patchTitle.isBlank() || patchTitle == patch.id) existing.title else patch.title
        val reopened = !patch.ended && existing.ended && patch.updatedAtEpochMs > existing.updatedAtEpochMs
        return existing.copy(
            profileId = patch.profileId.ifBlank { existing.profileId },
            title = title.ifBlank { existing.id },
            updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, patch.updatedAtEpochMs),
            createdAtEpochMs = when {
                existing.createdAtEpochMs > 0L && patch.createdAtEpochMs > 0L ->
                    minOf(existing.createdAtEpochMs, patch.createdAtEpochMs)
                else -> maxOf(existing.createdAtEpochMs, patch.createdAtEpochMs)
            },
            unread = patch.unread,
            ended = if (reopened) false else existing.ended || patch.ended,
            messageCount = maxOf(existing.messageCount, patch.messageCount),
            source = patch.source.ifBlank { existing.source },
            // Archive is a host decision; a newer full row may un-archive, a stale one never re-archives.
            archived = if (patch.updatedAtEpochMs > existing.updatedAtEpochMs) patch.archived else existing.archived || patch.archived,
        )
    }

    const val SOURCE_TELEGRAM = "telegram"

    /** Rows whose host `source` equals [source] (case-insensitive). Blank keeps everything. */
    fun bySource(sessions: List<SessionRef>, source: String): List<SessionRef> {
        val want = source.trim().lowercase()
        if (want.isEmpty()) return sessions
        return sessions.filter { it.source.trim().lowercase() == want }
    }

    /** Drop archived rows unless the operator asked for them. */
    fun archivedVisible(sessions: List<SessionRef>, showArchived: Boolean): List<SessionRef> =
        if (showArchived) sessions else sessions.filterNot { it.archived }

    /** Case-insensitive title / id / source filter for the rail search field. Blank keeps everything. */
    fun filter(sessions: List<SessionRef>, query: String): List<SessionRef> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return sessions
        return sessions.filter {
            it.title.lowercase().contains(q) || it.id.lowercase().contains(q) || it.source.lowercase().contains(q)
        }
    }
}
