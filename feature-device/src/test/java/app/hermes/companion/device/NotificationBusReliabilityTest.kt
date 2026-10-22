package app.hermes.companion.device

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class NotificationBusReliabilityTest {
    @Test fun burstBeforeCollectorIsBoundedAndOverflowCounted() = runBlocking {
        NotifStreamBus.droppedBeforeQueue.set(0)
        repeat(100) { NotifStreamBus.emit(NotifStreamEvent("$it", "com.example", "", "", "", false, true, 0)) }
        val events = NotifStreamBus.events.take(64).toList()
        assertEquals(36, NotifStreamBus.droppedBeforeQueue.get().toInt())
        assertEquals("36", events.first().key); assertEquals("99", events.last().key)
    }
    @Test fun consentRefreshRevokesOldNotificationHandles() {
        NotifStreamBus.rememberKey("hash", "real")
        NotifStreamBus.configure(false, emptySet(), "self", version = 9)
        assertNull(NotifStreamBus.realKey("hash")); assertEquals(9L, NotifStreamBus.consentVersion)
    }
}
