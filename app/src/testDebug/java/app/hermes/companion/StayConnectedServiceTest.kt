package app.hermes.companion

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import app.hermes.companion.data.local.StickyStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class StayConnectedServiceTest {
    private val apps = mutableListOf<CompanionApp>()
    private val services = mutableListOf<StayConnectedService>()

    @After fun cleanup() {
        services.forEach { it.onDestroy() }
        apps.forEach { it.appScope.cancel() }
        StayConnectedService.backgroundTimedOut = false
    }

    private fun service(stay: Boolean = true, stream: Boolean = false, initializeStore: Boolean = true): Pair<StayConnectedService, CompanionApp> {
        val app = CompanionApp().apply {
            Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
                .invoke(this, RuntimeEnvironment.getApplication())
        }
        apps += app
        if (initializeStore) {
            val sticky = StickyStore(app).apply { stayConnected = stay; notifStreamEnabled = stream; origin = null; lastGoodOrigin = null }
            CompanionApp::class.java.getDeclaredField("sticky").apply { isAccessible = true }.set(app, sticky)
        }
        val service = Robolectric.buildService(StayConnectedService::class.java).create().get()
        Service::class.java.getDeclaredField("mApplication").apply { isAccessible = true }.set(service, app)
        services += service
        return service to app
    }

    private fun keepJob(service: StayConnectedService): Job? = StayConnectedService::class.java
        .getDeclaredField("keepJob").apply { isAccessible = true }.get(service) as? Job

    @Test fun coldRefreshPromotesImmediatelyAndStartsConnectionLoop() {
        val (service, _) = service()

        assertEquals(Service.START_STICKY, service.onStartCommand(Intent().setAction(StayConnectedService.ACTION_REFRESH), 0, 1))

        assertEquals(17, shadowOf(service).lastForegroundNotificationId)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(keepJob(service)?.isActive == true)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun normalAndStickyRestartsPromoteWithoutEncryptedHostMetadata() {
        for (intent in listOf(Intent(), null)) {
            val (service, _) = service(stay = false, stream = true)
            assertEquals(Service.START_STICKY, service.onStartCommand(intent, 0, 1))
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertTrue(keepJob(service)?.isActive == true)
        }
    }

    @Test fun disabledOptInDoesNotRemainRunning() {
        val (service, _) = service(stay = false)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertNull(keepJob(service))
    }

    @Test fun refreshDoesNotReplaceAnActiveConnectionLoop() {
        val (service, _) = service()
        service.onStartCommand(Intent(), 0, 1)
        val job = keepJob(service)

        service.onStartCommand(Intent().setAction(StayConnectedService.ACTION_REFRESH), 0, 2)

        assertSame(job, keepJob(service))
    }

    @Test fun explicitStopAndTeardownCancelServiceWork() {
        val (service, _) = service()
        service.onStartCommand(Intent(), 0, 1)
        val job = keepJob(service)!!

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().setAction(StayConnectedService.ACTION_STOP), 0, 2))
        assertTrue(job.isCancelled)
        assertTrue(shadowOf(service).isStoppedBySelf)
        service.onDestroy()
        assertNull(keepJob(service))
    }

    @Test
    @Config(shadows = [TrackingForegroundPromotion::class])
    fun missingStartupPreferencesStopsAfterSatisfyingPromotion() {
        val (service, _) = service(initializeStore = false)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))

        assertEquals(1, Shadow.extract<TrackingForegroundPromotion>(service).promotionCount)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun periodicMetadataAndAccessibilityErrorsDoNotKillTheBridge() = runTest {
        val (service, app) = service()
        service.onStartCommand(Intent(), 0, 1)
        // Simulate unreadable saved metadata without making a real network request. deviceNode
        // is deliberately unavailable too, as during failed restoration.
        app.getSharedPreferences("companion.sticky", Context.MODE_PRIVATE).edit().putInt("origin", 1).commit()
        service.maintainConnection(app)
        assertTrue(keepJob(service)?.isActive == true)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    @Config(shadows = [RejectedForegroundPromotion::class])
    fun foregroundStartupRejectionStopsWithoutRetrying() {
        val (service, _) = service()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(keepJob(service))
    }

    @Implements(Service::class)
    class TrackingForegroundPromotion : ShadowService() {
        var promotionCount = 0
            private set

        @Implementation
        public override fun startForeground(id: Int, notification: Notification) {
            promotionCount++
            super.startForeground(id, notification)
        }
    }

    @Implements(Service::class)
    class RejectedForegroundPromotion : ShadowService() {
        @Implementation
        public override fun startForeground(id: Int, notification: Notification) {
            throw SecurityException("foreground promotion rejected")
        }
    }
}
