package app.hermes.companion.data.local

import app.hermes.companion.model.OutboxItem
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
class OutboxStoreTest {
    private lateinit var db: CompanionDatabase
    private lateinit var outbox: OutboxStore

    @Before
    fun setUp() {
        db = CompanionDatabase.inMemory(RuntimeEnvironment.getApplication())
        outbox = OutboxStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun fifoPerOriginAndProfile() = runBlocking {
        outbox.enqueue(item("b", MOCK, "coder", 2))
        outbox.enqueue(item("a", MOCK, "coder", 1))
        outbox.enqueue(item("c", MOCK, "ops", 1))
        assertEquals(listOf("a", "b"), outbox.pending(MOCK, "coder").map { it.id })
        assertEquals(listOf("c"), outbox.pending(MOCK, "ops").map { it.id })
    }

    @Test
    fun removeAndMarkAttempt() = runBlocking {
        outbox.enqueue(item("a", MOCK, "coder", 1))
        outbox.markAttempt("a", MOCK, "coder", "session busy")
        assertEquals(1, outbox.pending(MOCK, "coder").single().attempts)
        assertEquals("session busy", outbox.pending(MOCK, "coder").single().lastError)
        outbox.remove("a")
        assertTrue(outbox.pending(MOCK, "coder").isEmpty())
    }

    @Test
    fun removeForSessionLeavesOtherSessions() = runBlocking {
        outbox.enqueue(item("a", MOCK, "coder", 1).copy(sessionId = "s1"))
        outbox.enqueue(item("b", MOCK, "coder", 2).copy(sessionId = "s2"))
        outbox.removeForSession(MOCK, "coder", "s1")
        assertEquals(listOf("b"), outbox.pending(MOCK, "coder").map { it.id })
    }

    private fun item(id: String, origin: String, profile: String, created: Long) = OutboxItem(
        id = id,
        origin = origin,
        profileId = profile,
        sessionId = "s1",
        text = "hi $id",
        createdAtEpochMs = created,
    )

    companion object {
        private const val MOCK = "http://127.0.0.1:9119"
    }
}
