package app.hermes.companion

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.UserManager
import app.hermes.companion.data.local.StickyStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CompanionBootReceiverTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val receiver = CompanionBootReceiver()

    @Before fun resetPreferences() {
        StickyStore(app).apply { stayConnected = false; notifStreamEnabled = false }
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    }

    @Test fun disabledBackgroundFeaturesDoNotRestartAnything() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            receiver.onReceive(app, Intent(action))
        }
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test fun savedStayConnectedConsentRestoresOnlyBridgeAfterBoot() {
        StickyStore(app).stayConnected = true
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertBridgeOnly()
    }

    @Test fun savedNotificationStreamConsentRestoresOnlyBridgeAfterUpdate() {
        StickyStore(app).notifStreamEnabled = true
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertBridgeOnly()
        assertTrue(StickyStore(app).notifStreamEnabled)
    }

    @Test fun lockedBootAndUnrelatedBroadcastsCannotReadConsentOrRestoreControl() {
        StickyStore(app).apply { stayConnected = true; notifStreamEnabled = true }
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        receiver.onReceive(app, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        receiver.onReceive(app, Intent("app.hermes.companion.NOT_A_SYSTEM_RESTORE"))
        assertNull(shadowOf(app).nextStartedService)
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test fun rejectedBackgroundStartOffersOneUserResumeWithoutRetryingOrChangingConsent() {
        StickyStore(app).stayConnected = true
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var attempts = 0
        val restricted = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                attempts++
                throw IllegalStateException("background start restricted")
            }
        }
        receiver.onReceive(restricted, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, attempts)
        assertTrue(StickyStore(app).stayConnected)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(ShadowPowerManager.getLatestWakeLock())
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(BackgroundConnectionResume.ID)
        assertNotNull(notification)
        assertEquals(MainActivity::class.java.name, shadowOf(notification.contentIntent).savedIntent.component?.className)
        assertEquals(0, shadowOf(notification.contentIntent).savedIntent.extras?.size() ?: 0)
    }

    @Test fun rejectedRestoreWithoutNotificationPermissionStillDoesNotCrashOrRetry() {
        StickyStore(app).stayConnected = true
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val restricted = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? = throw SecurityException("restricted")
        }
        receiver.onReceive(restricted, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(BackgroundConnectionResume.ID))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun android15ManifestUsesDeclaredSpecialUseWithoutDataSyncBootBudget() {
        val service = app.packageManager.getServiceInfo(ComponentName(app, StayConnectedService::class.java),
            PackageManager.GET_META_DATA)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        val receiverInfo = app.packageManager.getReceiverInfo(ComponentName(app, CompanionBootReceiver::class.java), 0)
        assertFalse(receiverInfo.exported)
        assertFalse(receiverInfo.directBootAware)
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
        assertFalse(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC in permissions)
    }

    private fun assertBridgeOnly() {
        val intent = shadowOf(app).nextStartedService
        assertNotNull(intent)
        assertEquals(StayConnectedService::class.java.name, intent.component?.className)
        assertNull("No Hands, microphone or voice service may be restored", shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull("Boot must not restore a screen hold", ShadowPowerManager.getLatestWakeLock())
    }
}
