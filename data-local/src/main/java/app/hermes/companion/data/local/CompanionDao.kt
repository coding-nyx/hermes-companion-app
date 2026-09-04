package app.hermes.companion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class CompanionDao {
    @Query(
        "SELECT * FROM sessions WHERE origin = :origin AND profileId = :profileId " +
            "ORDER BY updatedAtEpochMs DESC, id DESC",
    )
    abstract suspend fun sessions(origin: String, profileId: String): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSessions(rows: List<SessionEntity>)

    @Query("DELETE FROM sessions WHERE origin = :origin AND profileId = :profileId")
    abstract suspend fun clearSessions(origin: String, profileId: String)

    @Query("DELETE FROM sessions WHERE origin = :origin AND profileId = :profileId AND id = :id")
    abstract suspend fun deleteSession(origin: String, profileId: String, id: String)

    @Transaction
    open suspend fun replaceSessions(origin: String, profileId: String, rows: List<SessionEntity>) {
        clearSessions(origin, profileId)
        if (rows.isNotEmpty()) upsertSessions(rows)
    }

    @Query(
        "SELECT * FROM messages WHERE origin = :origin AND profileId = :profileId " +
            "AND sessionId = :sessionId ORDER BY ordinal ASC",
    )
    abstract suspend fun messages(origin: String, profileId: String, sessionId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMessages(rows: List<MessageEntity>)

    @Query(
        "DELETE FROM messages WHERE origin = :origin AND profileId = :profileId AND sessionId = :sessionId",
    )
    abstract suspend fun clearMessages(origin: String, profileId: String, sessionId: String)

    @Transaction
    open suspend fun replaceMessages(origin: String, profileId: String, sessionId: String, rows: List<MessageEntity>) {
        clearMessages(origin, profileId, sessionId)
        if (rows.isNotEmpty()) upsertMessages(rows)
    }

    @Query(
        "SELECT * FROM outbox WHERE origin = :origin AND profileId = :profileId " +
            "ORDER BY createdAtEpochMs ASC, id ASC",
    )
    abstract suspend fun outbox(origin: String, profileId: String): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOutbox(row: OutboxEntity)

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun deleteOutbox(id: String)

    @Query(
        "DELETE FROM outbox WHERE origin = :origin AND profileId = :profileId AND sessionId = :sessionId",
    )
    abstract suspend fun deleteOutboxForSession(origin: String, profileId: String, sessionId: String)
}
