package app.hermes.companion.data.local

import android.content.Context
import androidx.room.*
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "session_mutations")
data class SessionMutationEntity(
    @PrimaryKey val ownerKey: String,
    val operationId: String,
    val origin: String,
    val profile: String,
    val session: String,
    val kind: String,
    val requested: String,
    val state: String,
    val detail: String,
    val startedAt: Long,
)
@Dao
interface SessionMutationDao {
    @Query("SELECT * FROM session_mutations WHERE ownerKey = :key") suspend fun find(key: String): SessionMutationEntity?
    @Query("SELECT * FROM session_mutations WHERE origin = :origin AND profile = :profile AND session = :session AND state IN ('pending', 'unknown')")
    suspend fun pending(origin: String, profile: String, session: String): List<SessionMutationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: SessionMutationEntity)
    @Query("UPDATE session_mutations SET state = :state, detail = :detail WHERE ownerKey = :key AND operationId = :id AND state IN ('pending', 'unknown')")
    suspend fun finish(key: String, id: String, state: String, detail: String)
    @Transaction suspend fun claim(value: SessionMutationEntity): Boolean {
        if (find(value.ownerKey)?.state in setOf("pending", "unknown")) return false
        put(value); return true
    }
}
/** A separate database protects rollback to the preceding APK. Future changes require explicit migrations. */
@Database(entities = [SessionMutationEntity::class], version = 1, exportSchema = false)
abstract class CompanionRecoveryDatabase : RoomDatabase() {
    abstract fun mutations(): SessionMutationDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context.applicationContext, CompanionRecoveryDatabase::class.java,
            "companion-recovery.db").build()
        fun inMemory(context: Context) = Room.inMemoryDatabaseBuilder(context, CompanionRecoveryDatabase::class.java).build()
    }
}
class SessionMutationStore(private val dao: SessionMutationDao) {
    companion object { fun create(context: Context) = SessionMutationStore(CompanionRecoveryDatabase.create(context).mutations()) }
    suspend fun claim(owner: DraftOwner, kind: String, requested: String): SessionMutationEntity? {
        val key = Json.encodeToString(owner) + "|" + kind
        val value = SessionMutationEntity(key, UUID.randomUUID().toString(), owner.origin, owner.profile, owner.session,
            kind, requested, "pending", "", System.currentTimeMillis())
        return if (dao.claim(value)) value else null
    }
    suspend fun pending(owner: DraftOwner) = dao.pending(owner.origin, owner.profile, owner.session)
    suspend fun finish(operation: SessionMutationEntity, state: String, detail: String = "") =
        dao.finish(operation.ownerKey, operation.operationId, state, detail)
}
