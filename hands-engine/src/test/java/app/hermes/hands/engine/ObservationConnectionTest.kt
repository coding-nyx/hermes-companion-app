package app.hermes.hands.engine

import org.junit.Assert.*
import org.junit.Test

class ObservationConnectionTest {
    private class PlatformConnection(val windowId: Int) : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun snapshotValidationAndPrivateCaptureKeepTheSamePlatformWindowIdentity() {
        var registrations = 0
        val owner = ObservationConnection { PlatformConnection(++registrations) }
        val snapshot = owner.acquire()
        val validation = owner.acquire()
        val privateCapture = owner.acquire()
        assertSame(snapshot.value, validation.value)
        assertSame(snapshot.value, privateCapture.value)
        assertEquals(1, registrations)
        val local = owner.transfer(privateCapture)
        owner.close() // A remote cancellation cannot close a privately transferred target.
        assertEquals(0, local.closes)
        local.close()
        assertEquals(1, local.closes)
    }

    @Test fun anOldIdleCallbackCannotCloseAnObservationThatWasUsedAgain() {
        val owner = ObservationConnection { PlatformConnection(1) }
        val first = owner.acquire()
        val usedAgain = owner.acquire()
        assertFalse(owner.close(first.revision))
        assertEquals(0, usedAgain.value.closes)
        assertTrue(owner.close(usedAgain.revision))
        assertEquals(1, usedAgain.value.closes)
    }

    @Test fun transferredPrivateTargetsAndNewSessionsRejectStaleCleanupCallbacks() {
        var registrations = 0
        val owner = ObservationConnection { PlatformConnection(++registrations) }
        val original = owner.acquire()
        val private = owner.transfer(original)
        assertFalse(owner.close(original.revision))
        assertEquals(0, private.closes)
        val fresh = owner.acquire()
        assertEquals(2, fresh.value.windowId)
        assertFalse(owner.close(original.revision))
        assertEquals(0, fresh.value.closes)
        owner.close(); private.close()
        assertEquals(1, fresh.value.closes)
        assertEquals(1, private.closes)
    }

    @Test fun closedConnectionsReallocateIdentityAndFailedOldTransfersCannotBorrowIt() {
        var registrations = 0
        val owner = ObservationConnection { PlatformConnection(++registrations) }
        val old = owner.acquire()
        assertTrue(owner.close())
        val next = owner.acquire()
        assertNotEquals(old.value.windowId, next.value.windowId)
        assertThrows(IllegalStateException::class.java) { owner.transfer(old) }
        assertEquals(0, next.value.closes)
        owner.close(); assertEquals(1, next.value.closes)
    }
}
