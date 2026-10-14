package app.hermes.companion.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ParityMigrationTest {
    @Test fun upgradingVersionSixPreservesQueuedMessagesAndSessions() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "parity-migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("""CREATE TABLE sessions (origin TEXT NOT NULL, profileId TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL, unread INTEGER NOT NULL, ended INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL,
                messageCount INTEGER NOT NULL, source TEXT NOT NULL, archived INTEGER NOT NULL, PRIMARY KEY(origin, profileId, id))""")
            old.execSQL("""CREATE TABLE messages (origin TEXT NOT NULL, profileId TEXT NOT NULL, sessionId TEXT NOT NULL, id TEXT NOT NULL,
                ordinal INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, toolName TEXT, toolDetail TEXT, blocksJson TEXT NOT NULL,
                PRIMARY KEY(origin, profileId, sessionId, id))""")
            old.execSQL("""CREATE INDEX index_messages_origin_profileId_sessionId ON messages(origin, profileId, sessionId)""")
            old.execSQL("""CREATE TABLE outbox (id TEXT NOT NULL PRIMARY KEY, origin TEXT NOT NULL, profileId TEXT NOT NULL, sessionId TEXT NOT NULL,
                text TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, attempts INTEGER NOT NULL, lastError TEXT NOT NULL, attachmentsJson TEXT NOT NULL)""")
            old.execSQL("""CREATE INDEX index_outbox_origin_profileId_createdAtEpochMs ON outbox(origin, profileId, createdAtEpochMs)""")
            old.execSQL("""INSERT INTO sessions VALUES ('host', 'coder', 's', 'keep this thread', 10, 0, 0, 10, 1, 'cli', 0)""")
            old.execSQL("""INSERT INTO outbox VALUES ('u', 'host', 'coder', 's', 'do not lose this message', 12, 0, '', '')""")
            old.version = 6
        }
        val db = Room.databaseBuilder(context, CompanionDatabase::class.java, name)
            .addMigrations(CompanionDatabase.MIGRATION_6_7).build()
        try {
            val item = OutboxStore(db).pending("host", "coder").single()
            assertEquals("do not lose this message", item.text)
            assertNull(item.baselineUserIds)
            val session = TranscriptCache(db).sessions("host", "coder").single()
            assertEquals("keep this thread", session.title)
            assertFalse(session.pinned)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
