package app.hermes.companion.data.local

import app.hermes.companion.model.OutboxItem

class OutboxStore(private val dao: CompanionDao) {
    constructor(db: CompanionDatabase) : this(db.transcript())

    suspend fun enqueue(item: OutboxItem) {
        dao.upsertOutbox(item.toEntity())
    }

    suspend fun pending(origin: String, profileId: String): List<OutboxItem> =
        dao.outbox(origin, profileId).map { it.toItem() }

    suspend fun remove(id: String) {
        dao.deleteOutbox(id)
    }

    suspend fun removeForSession(origin: String, profileId: String, sessionId: String) {
        dao.deleteOutboxForSession(origin, profileId, sessionId)
    }

    suspend fun markAttempt(id: String, origin: String, profileId: String, error: String) {
        val row = pending(origin, profileId).find { it.id == id } ?: return
        dao.upsertOutbox(
            row.copy(attempts = row.attempts + 1, lastError = error).toEntity(),
        )
    }
}
