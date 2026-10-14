package app.hermes.companion.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class, OutboxEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class CompanionDatabase : RoomDatabase() {
    abstract fun transcript(): CompanionDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE outbox ADD COLUMN baselineUserIdsJson TEXT NOT NULL DEFAULT 'null'")
            }
        }
        fun create(context: Context): CompanionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                CompanionDatabase::class.java,
                "companion-cache.db",
            ).addMigrations(MIGRATION_6_7).fallbackToDestructiveMigration().build()

        fun inMemory(context: Context): CompanionDatabase =
            Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
