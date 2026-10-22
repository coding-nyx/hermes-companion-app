package app.hermes.companion.domain

import org.junit.Assert.*
import org.junit.Test

class NotificationDeliveryPolicyTest {
    @Test fun nothingTransmittedBeforeDurableSnapshot() {
        assertEquals(NotificationDeliveryPolicy.Action.WAIT_FOR_STORAGE, NotificationDeliveryPolicy.next(false, false, 0, 1000))
    }
    @Test fun sendIsRetriedUntilExplicitAcknowledgment() {
        assertEquals(NotificationDeliveryPolicy.Action.SEND, NotificationDeliveryPolicy.next(true, false, 0, 1000))
        assertEquals(NotificationDeliveryPolicy.Action.WAIT_FOR_RETRY, NotificationDeliveryPolicy.next(true, false, 1000, 10999))
        assertEquals(NotificationDeliveryPolicy.Action.SEND, NotificationDeliveryPolicy.next(true, false, 1000, 11000))
        assertEquals(NotificationDeliveryPolicy.Action.ACKNOWLEDGED, NotificationDeliveryPolicy.next(true, true, 1000, 1001))
    }
    @Test fun restoredItemRetriesAndClockReversalDoesNotStall() {
        assertEquals(NotificationDeliveryPolicy.Action.SEND, NotificationDeliveryPolicy.next(true, false, 0, 99999))
        assertEquals(NotificationDeliveryPolicy.Action.SEND, NotificationDeliveryPolicy.next(true, false, 99999, 1000))
    }
    @Test fun everyRouteIdentityPartMustMatch() {
        fun matches(origin: String = "https://host.invalid/one", profile: String = "a", device: String = "d", cred: String = "c") =
            NotificationDeliveryPolicy.sameScope("https://host.invalid/one", "a", "d", "c", origin, profile, device, cred)
        assertTrue(matches()); assertFalse(matches(origin = "https://host.invalid/two")); assertFalse(matches(profile = "b"))
        assertFalse(matches(device = "new")); assertFalse(matches(cred = "rotated"))
    }
}
