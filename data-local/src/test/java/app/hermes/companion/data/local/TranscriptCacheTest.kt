package app.hermes.companion.data.local

import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.SessionChange
import app.hermes.companion.model.SessionRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TranscriptCacheTest {
    private lateinit var db: CompanionDatabase
    private lateinit var cache: TranscriptCache

    @Before
    fun setUp() {
        db = CompanionDatabase.inMemory(RuntimeEnvironment.getApplication())
        cache = TranscriptCache(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun partitionsByOriginAndProfile() = runBlocking {
        cache.replaceSessions(MOCK, "coder", listOf(session("s-cod", "coder", "auth")))
        cache.replaceSessions(MOCK, "ops", listOf(session("s-ops", "ops", "cleanup")))
        cache.replaceSessions(LAB, "coder", listOf(session("s-lab", "coder", "lab")))
        assertEquals(listOf("s-cod"), cache.sessions(MOCK, "coder").map { it.id })
        assertEquals(listOf("s-ops"), cache.sessions(MOCK, "ops").map { it.id })
        assertEquals(listOf("s-lab"), cache.sessions(LAB, "coder").map { it.id })
        assertTrue(cache.sessions(MOCK, "personal").isEmpty())
    }

    @Test
    fun serverWinsReplaceClearsStaleRows() = runBlocking {
        cache.replaceSessions(MOCK, "coder", listOf(session("old", "coder", "gone")))
        cache.replaceSessions(MOCK, "coder", listOf(session("new", "coder", "fresh")))
        assertEquals(listOf("new"), cache.sessions(MOCK, "coder").map { it.id })
    }

    @Test
    fun readThroughWritesOnSuccessAndFallsBackOnError() = runBlocking {
        val remote = listOf(session("live", "coder", "from host"))
        val first = cache.readSessions(MOCK, "coder") { remote }
        assertEquals("live", first.single().id)
        val fallback = cache.readSessions(MOCK, "coder") { error("offline") }
        assertEquals("live", fallback.single().id)
    }

    @Test(expected = IllegalStateException::class)
    fun readThroughRethrowsWhenCacheEmpty() {
        runBlocking {
            cache.readSessions(MOCK, "coder") { throw IllegalStateException("down") }
        }
    }

    @Test
    fun messagesServerWinsAndSkipStreaming() = runBlocking {
        val sid = "s-cod"
        cache.replaceMessages(
            MOCK,
            "coder",
            sid,
            listOf(
                ChatMessage("u1", MessageRole.USER, "hi"),
                ChatMessage("a1", MessageRole.ASSISTANT, "partial", streaming = true),
                ChatMessage("t1", MessageRole.TOOL, "ls", toolName = "terminal", toolDetail = "ls"),
            ),
        )
        val stored = cache.messages(MOCK, "coder", sid)
        assertEquals(listOf("u1", "t1"), stored.map { it.id })
        cache.replaceMessages(MOCK, "coder", sid, listOf(ChatMessage("u2", MessageRole.USER, "next")))
        assertEquals(listOf("u2"), cache.messages(MOCK, "coder", sid).map { it.id })
    }

    @Test
    fun applyChangeIgnoresForeignProfileAndDeletesOwned() = runBlocking {
        cache.replaceSessions(MOCK, "coder", listOf(session("s-cod", "coder", "auth")))
        cache.applyChange(MOCK, "coder", SessionChange("upsert", session("s-ops", "ops", "nope")))
        assertEquals(listOf("s-cod"), cache.sessions(MOCK, "coder").map { it.id })
        cache.applyChange(MOCK, "coder", SessionChange("delete", session("s-cod", "coder", "auth")))
        assertTrue(cache.sessions(MOCK, "coder").isEmpty())
    }

    @Test
    fun deleteSessionPurgesMessages() = runBlocking {
        val sid = "s-del"
        cache.replaceSessions(MOCK, "coder", listOf(session(sid, "coder", "gone")))
        cache.replaceMessages(MOCK, "coder", sid, listOf(ChatMessage("u1", MessageRole.USER, "hi")))
        cache.deleteSession(MOCK, "coder", sid)
        assertTrue(cache.sessions(MOCK, "coder").isEmpty())
        assertTrue(cache.messages(MOCK, "coder", sid).isEmpty())
    }

    private fun session(id: String, profile: String, title: String) = SessionRef(
        id = id,
        profileId = profile,
        title = title,
        updatedAtEpochMs = 1L,
    )

    companion object {
        private const val MOCK = "http://127.0.0.1:9119"
        private const val LAB = "http://127.0.0.1:19219"
    }
}
