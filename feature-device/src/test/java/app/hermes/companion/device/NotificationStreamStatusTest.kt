package app.hermes.companion.device

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationStreamStatusTest {
    @Test fun disabledStreamDoesNotClaimToShareEvenWithAndroidAccess() {
        assertEquals("Off", notificationStreamStatus(enabled = false, accessGranted = true).label)
        assertEquals("Off", notificationStreamStatus(enabled = false, accessGranted = false).label)
    }

    @Test fun enabledStreamWithoutListenerClearlyNeedsAccess() {
        assertEquals("Needs access", notificationStreamStatus(enabled = true, accessGranted = false).label)
    }

    @Test fun enabledLabelDoesNotClaimDeliveryOrHostConnectivity() {
        assertEquals("Enabled", notificationStreamStatus(enabled = true, accessGranted = true).label)
    }
}
