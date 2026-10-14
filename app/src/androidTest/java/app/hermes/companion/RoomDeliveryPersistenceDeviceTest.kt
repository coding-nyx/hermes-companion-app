package app.hermes.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.local.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomDeliveryPersistenceDeviceTest {
    @Test fun encryptedJournalReopensWithoutLeakingOwnersOrDowngradingAReceipt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "room-journal-test-${UUID.randomUUID()}"
        val owner = DraftOwner("https://fixture/proxy/a", "fixture", "room:fixture")
        val record = RoomDeliveryRecord("fixture-id", owner, "**private fixture**", listOf("rm-1"), "uncertain", createdAt = 1792000000123)
        try {
            val store = RoomDeliveryStore.encrypted(ctx, file); store.save(record)
            val reopened = RoomDeliveryStore.encrypted(ctx, file)
            assertEquals(listOf(record), reopened.list(owner))
            assertTrue(reopened.list(owner.copy(origin = "https://fixture/proxy/b")).isEmpty())
            assertTrue(reopened.list(owner.copy(profile = "other")).isEmpty())
            assertTrue(ctx.getSharedPreferences(file, 0).all.values.none { it.toString().contains(record.text) })
            reopened.save(record.copy(state = "sent", sequence = 2)); store.save(record)
            assertEquals(2, RoomDeliveryStore.encrypted(ctx, file).list(owner).single().sequence)
            store.remove(owner.copy(session = "room:other"), record.id); assertEquals(1, store.list(owner).size)
            store.remove(owner, record.id); assertTrue(store.list(owner).isEmpty())
        } finally { ctx.deleteSharedPreferences(file) }
    }
}
