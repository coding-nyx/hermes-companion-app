package app.hermes.companion.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class, OutboxEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class CompanionDatabase : RoomDatabase() {
    abstract fun transcript(): CompanionDao

    companion object {
        fun create(context: Context): CompanionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                CompanionDatabase::class.java,
                "companion-cache.db",
            ).fallbackToDestructiveMigration().build()

        fun inMemory(context: Context): CompanionDatabase =
            Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
