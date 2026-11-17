package app.hermes.companion

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.model.DeviceArm
import app.hermes.hands.client.HandsClient
import app.hermes.hands.client.HandsConnection
import app.hermes.hands.client.HandsConnectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSettings

/** Companion is a client; local permission grants cannot resurrect its retired controller. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceNodeArmingTest {
    private lateinit var app: CompanionApp
    private lateinit var sticky: StickyStore
    private val a11yKey = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private val otherService = "com.reader/.TalkBack"

    @Before fun setup() {
        app = CompanionApp().apply {
            Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
                .invoke(this, RuntimeEnvironment.getApplication())
        }
        sticky = StickyStore(app).apply { keepA11yOn = true; stayConnected = false; notifStreamEnabled = false }
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsScreenOn(true)
        shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        Settings.Secure.putString(app.contentResolver, a11yKey, otherService)
    }

    private fun node(scope: TestScope): DeviceNodeCoordinator {
        val hands = HandsClient(object : HandsConnectionStore {
            override fun load(): HandsConnection? = null
            override fun save(connection: HandsConnection) = Unit
            override fun clear() = Unit
        })
        return DeviceNodeCoordinator(app, HostClientPool(),
            DeviceCredStore(app.getSharedPreferences("client-only-arming", 0)), sticky, scope.backgroundScope, hands)
    }

    private fun assertNoLocalController(node: DeviceNodeCoordinator) {
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
        assertFalse(node.state.value.armPending)
        assertEquals(otherService, Settings.Secure.getString(app.contentResolver, a11yKey))
        assertTrue("Companion must never acquire a phone-control wake lock", ShadowPowerManager.getLatestWakeLock()?.isHeld != true)
        assertNull("Companion must not start a local control service", shadowOf(app).nextStartedService)
    }

    @Test fun localArmCannotUseGrantedSecureSettingsOrOverlayPermission() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS))
        val node = node(this)
        node.arm()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertNoLocalController(node)
    }

    @Test fun localDirectOnlyArmHasNoLegacyPermissionFallback() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val node = node(this)
        node.arm(directOnly = true, durationSeconds = 30)
        runCurrent()
        assertNoLocalController(node)
    }

    @Test fun staleAccessibilityEventsAndKeepOnPreferenceCannotGrantControl() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val node = node(this)
        node.setA11yBound(true)
        node.recoverAccessibility()
        node.refreshAccessibilityState()
        node.arm()
        runCurrent()
        assertNoLocalController(node)
        assertFalse(node.state.value.a11yBound)
    }

    @Test fun accessibilityOnRedirectsToHandsInsteadOfChangingAndroidSettings() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val node = node(this)
        assertFalse(node.turnAccessibilityOn())
        runCurrent()
        val redirect = shadowOf(app).nextStartedActivity
        assertEquals("app.hermes.hands", redirect.component?.packageName)
        assertEquals("app.hermes.hands.MainActivity", redirect.component?.className)
        assertNoLocalController(node)
    }

    @Test fun accessibilityOffDoesNotDisableOtherServicesOrOwnLocalControl() = runTest {
        val node = node(this)
        assertFalse(node.turnAccessibilityOff())
        runCurrent()
        assertEquals("app.hermes.hands", shadowOf(app).nextStartedActivity.component?.packageName)
        assertNoLocalController(node)
    }

    @Test fun localDisarmAndPrivateEntryPauseCannotCreateAController() = runTest {
        val node = node(this)
        node.arm()
        node.disarm()
        node.pauseForPrivateEntry()
        runCurrent()
        assertNoLocalController(node)
    }

    @Test fun protectionEditingOpensHandsWithoutWritingCompanionRules() = runTest {
        sticky.protectedPackages = setOf("app.existing.rule")
        val node = node(this)
        node.addProtectedPackage("app.new.rule")
        node.removeProtectedPackage("app.existing.rule")
        runCurrent()
        assertEquals(setOf("app.existing.rule"), sticky.protectedPackages)
        repeat(2) { assertEquals("app.hermes.hands", shadowOf(app).nextStartedActivity.component?.packageName) }
        assertNoLocalController(node)
    }

    @Test fun statusNamesHandsAndDoesNotInferConsentFromUnlockedPhone() = runTest {
        val node = node(this)
        runCurrent()
        val status = node.deviceStatus()
        assertEquals("hands", status.getString("owner"))
        assertFalse(status.getBoolean("available"))
        assertFalse(status.getBoolean("armed"))
        assertNoLocalController(node)
    }
}
