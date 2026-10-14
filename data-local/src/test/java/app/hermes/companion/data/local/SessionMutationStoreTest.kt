package app.hermes.companion.data.local

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SessionMutationStoreTest {
    private val owner = DraftOwner("https://lab/proxy", "ash", "session")
    @Test fun concurrentClaimsAdmitOneMutationAndUnknownIsNotReplayed() = runBlocking {
        val db = CompanionRecoveryDatabase.inMemory(RuntimeEnvironment.getApplication())
        try {
            val store = SessionMutationStore(db.mutations())
            val claims = (1..8).map { async(Dispatchers.Default) { store.claim(owner, "config.reasoning", "low") } }.awaitAll()
            assertEquals(1, claims.count { it != null })
            val operation = claims.filterNotNull().single()
            store.finish(operation, "unknown")
            assertNull(store.claim(owner, "config.reasoning", "high"))
            assertEquals(operation.operationId, store.pending(owner).single().operationId)
            store.finish(operation, "applied")
            assertNotNull(store.claim(owner, "config.reasoning", "high"))
        } finally { db.close() }
    }
    @Test fun anOldReceiptCannotOverwriteANewerClaimOrAnAcknowledgedResult() = runBlocking {
        val db = CompanionRecoveryDatabase.inMemory(RuntimeEnvironment.getApplication())
        try {
            val store = SessionMutationStore(db.mutations())
            val old = store.claim(owner, "redirect", "old")!!
            store.finish(old, "applied"); store.finish(old, "unknown")
            assertTrue(store.pending(owner).isEmpty())
            val next = store.claim(owner, "redirect", "new")!!
            store.finish(old, "rejected")
            assertEquals(next.operationId, store.pending(owner).single().operationId)
            assertTrue(store.pending(owner.copy(origin = "https://other")).isEmpty())
            assertTrue(store.pending(owner.copy(profile = "other")).isEmpty())
            assertTrue(store.pending(owner.copy(session = "other")).isEmpty())
        } finally { db.close() }
    }
}
