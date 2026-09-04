package app.hermes.companion.data.local

import android.content.Context
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.SessionChange
import app.hermes.companion.model.SessionRef

/**
 * Read-through Room cache. Hermes is source of truth.
 * Server wins on transcript; empty remote list clears the partition.
 * Never stores tokens, passwords, or streaming drafts.
 */
class TranscriptCache(private val dao: CompanionDao) {
    constructor(context: Context) : this(CompanionDatabase.create(context).transcript())
    constructor(db: CompanionDatabase) : this(db.transcript())

    suspend fun sessions(origin: String, profileId: String): List<SessionRef> =
        dao.sessions(origin, profileId).map { it.toRef() }

    suspend fun replaceSessions(origin: String, profileId: String, rows: List<SessionRef>) {
        dao.replaceSessions(origin, profileId, rows.map { it.toEntity(origin) })
    }

    suspend fun upsertSession(origin: String, session: SessionRef) {
        dao.upsertSessions(listOf(session.toEntity(origin)))
    }

    suspend fun deleteSession(origin: String, profileId: String, sessionId: String) {
        dao.deleteSession(origin, profileId, sessionId)
        dao.clearMessages(origin, profileId, sessionId)
    }

    suspend fun applyChange(origin: String, profileId: String, change: SessionChange) {
        if (change.session.profileId != profileId) return
        val op = change.op.lowercase()
        if (op == "delete" || op == "remove") {
            deleteSession(origin, profileId, change.session.id)
        } else {
            upsertSession(origin, change.session)
        }
    }

    suspend fun messages(origin: String, profileId: String, sessionId: String): List<ChatMessage> =
        dao.messages(origin, profileId, sessionId).map { it.toModel() }

    suspend fun replaceMessages(
        origin: String,
        profileId: String,
        sessionId: String,
        rows: List<ChatMessage>,
    ) {
        val persistable = persistableMessages(rows)
        dao.replaceMessages(
            origin,
            profileId,
            sessionId,
            persistable.mapIndexed { index, message ->
                message.toEntity(origin, profileId, sessionId, index)
            },
        )
    }

    suspend fun readSessions(
        origin: String,
        profileId: String,
        remote: suspend () -> List<SessionRef>,
    ): List<SessionRef> = readThrough(
        cached = { sessions(origin, profileId) },
        remote = remote,
        write = { replaceSessions(origin, profileId, it) },
    )

    suspend fun readMessages(
        origin: String,
        profileId: String,
        sessionId: String,
        remote: suspend () -> List<ChatMessage>,
    ): List<ChatMessage> = readThrough(
        cached = { messages(origin, profileId, sessionId) },
        remote = remote,
        write = { replaceMessages(origin, profileId, sessionId, it) },
    )

    private suspend fun <T> readThrough(
        cached: suspend () -> List<T>,
        remote: suspend () -> List<T>,
        write: suspend (List<T>) -> Unit,
    ): List<T> {
        return try {
            remote().also { write(it) }
        } catch (t: Throwable) {
            val hit = cached()
            if (hit.isNotEmpty()) hit else throw t
        }
    }
}
