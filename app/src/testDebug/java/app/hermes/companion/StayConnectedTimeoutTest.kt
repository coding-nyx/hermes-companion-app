package app.hermes.companion

import android.app.Application
import android.app.Service
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class StayConnectedTimeoutTest {
    @After fun reset() { StayConnectedService.backgroundTimedOut = false }

    @Test fun timeoutStopsServiceAndRejectsAutomaticRestart() {
        val service = Robolectric.buildService(StayConnectedService::class.java).create().get()
        service.onTimeout(1, 1)
        assertTrue(StayConnectedService.backgroundTimedOut)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
    }
}
