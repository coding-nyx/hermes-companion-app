package app.hermes.companion.data.local

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomDeliveryStoreTest {
    private val owner = DraftOwner("https://lab/a", "ash", "room:r")
    @Test fun recreationPreservesUncertainSubmissionAndKnownBaselineWithoutReplayingIt() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("room-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val record = RoomDeliveryRecord("id", owner, "**formatted**", listOf("rm-1"), "uncertain", createdAt = 1792000000123)
        RoomDeliveryStore(prefs).save(record)
        assertEquals(listOf(record), RoomDeliveryStore(prefs).list(owner))
        for (other in listOf(owner.copy(origin = "https://lab/b"), owner.copy(profile = "other"), owner.copy(session = "room:x")))
            assertTrue(RoomDeliveryStore(prefs).list(other).isEmpty())
    }
    @Test fun lateFailureCannotDowngradeReceiptAndWrongOwnerCannotClearIt() {
        val store = RoomDeliveryStore(); val record = RoomDeliveryRecord("id", owner, "text", null)
        store.save(record); store.save(record.copy(state = "sent", sequence = 7)); store.save(record.copy(state = "uncertain"))
        assertEquals(7, store.list(owner).single().sequence)
        store.remove(owner.copy(profile = "other"), "id"); assertEquals(1, store.list(owner).size)
        store.remove(owner, "id"); assertTrue(store.list(owner).isEmpty())
    }
    @Test fun concurrentAdmissionOfIdenticalTextClaimsOnlyOneSubmission() {
        val store = RoomDeliveryStore()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val ready = java.util.concurrent.CountDownLatch(1)
            val tasks = (1..8).map { i -> pool.submit(java.util.concurrent.Callable {
                ready.await(); store.claim(RoomDeliveryRecord("id-$i", owner, "same text", emptyList()))
            }) }
            ready.countDown()
            assertEquals(1, tasks.count { it.get() })
            assertEquals(1, store.list(owner).size)
            assertTrue(store.claim(RoomDeliveryRecord("other-owner", owner.copy(profile = "other"), "same text", emptyList())))
        } finally { pool.shutdownNow() }
    }

}
