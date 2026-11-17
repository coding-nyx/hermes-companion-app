package app.hermes.companion

import android.app.Application
import android.content.Context
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.model.DeviceArm
import app.hermes.hands.client.HandsClient
import app.hermes.hands.client.HandsConnection
import app.hermes.hands.client.HandsConnectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSettings

/** Lease/hold execution now belongs to Hands; Companion only projects live owner metadata. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceDisplayLifecycleTest {
    private lateinit var app: CompanionApp
    private lateinit var server: MockWebServer
    private lateinit var hands: HandsClient

    @Before fun setup() {
        app = CompanionApp().apply {
            Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
                .invoke(this, RuntimeEnvironment.getApplication())
        }
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsScreenOn(true)
        server = MockWebServer().also { it.start() }
        val endpoint = server.url("/").toString().trimEnd('/')
        hands = HandsClient(object : HandsConnectionStore {
            override fun load() = HandsConnection(endpoint, "synthetic-client-secret")
            override fun save(connection: HandsConnection) = Unit
            override fun clear() = Unit
        }, endpointAllowed = { it == endpoint })
    }
    @After fun teardown() { server.shutdown() }

    private fun takeRequest(): okhttp3.mockwebserver.RecordedRequest =
        requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) { "Expected Hands HTTP request within 5 seconds" }

    private fun node(scope: TestScope) = DeviceNodeCoordinator(app, HostClientPool(),
        DeviceCredStore(app.getSharedPreferences("client-only-display", 0)),
        StickyStore(app).apply { stayConnected = false; notifStreamEnabled = false }, scope.backgroundScope, hands)

    private fun assertNoAndroidHold() {
        assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld != true)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun liveOwnerMetadataCanReportArmedWithoutStartingLocalHoldOrService() = runTest {
        val node = node(this)
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"armed":true,"keep_awake":{"enabled":true,"armed_hold":true}}}"""))
        hands.refresh(); runCurrent()
        assertEquals(DeviceArm.ARMED, node.state.value.arm)
        assertTrue(node.keepAwakeStatus().getBoolean("enabled"))
        assertTrue(node.keepAwakeStatus().getBoolean("available"))
        assertEquals("hands", node.deviceStatus().getString("owner"))
        assertNoAndroidHold()
    }

    @Test fun missingOwnerHoldMetadataIsUnknownEvenWhenPhysicalScreenIsOn() = runTest {
        val node = node(this)
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"armed":true}}"""))
        hands.refresh(); runCurrent()
        assertFalse(node.keepAwakeStatus().getBoolean("enabled"))
        assertFalse(node.keepAwakeStatus().getBoolean("available"))
        assertNoAndroidHold()
    }

    @Test fun retiredStandaloneHoldApiCannotAcquireOrReleaseAndroidWakeLocks() = runTest {
        val node = node(this)
        for (enabled in listOf(true, false)) {
            assertEquals("hands_required", runCatching { node.setKeepAwake(enabled, 60) }.exceptionOrNull()?.message)
        }
        runCurrent()
        assertEquals(0, server.requestCount)
        assertNoAndroidHold()
    }

    @Test fun retiredLeaseRenewAndReleaseCannotBorrowAnOwnerFromStatus() = runTest {
        val node = node(this)
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"armed":true,"lease_id":"foreign-status-holder"}}"""))
        hands.refresh(); takeRequest(); runCurrent()
        assertEquals("hands_required", runCatching { node.renewArm("foreign-status-holder") }.exceptionOrNull()?.message)
        assertEquals("hands_required", runCatching { node.releaseArmLease("foreign-status-holder") }.exceptionOrNull()?.message)
        assertEquals(1, server.requestCount)
        assertNoAndroidHold()
    }

    @Test fun localArmButtonDoesNotAcquireAHolderEvenWithHandsConfigured() = runTest {
        val node = node(this)
        node.arm(directOnly = true, durationSeconds = 120)
        runCurrent()
        assertEquals(0, server.requestCount)
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
        assertNoAndroidHold()
    }

    @Test fun failedStatusInvalidatesHoldAvailabilityWithoutLocalFallback() = runTest {
        val node = node(this)
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"armed":true,"keep_awake":{"enabled":true}}}"""))
        hands.refresh(); runCurrent()
        server.enqueue(MockResponse().setResponseCode(401))
        hands.refresh(); runCurrent()
        assertFalse(node.state.value.externalHandsReachable)
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
        assertFalse(node.keepAwakeStatus().getBoolean("available"))
        assertTrue(node.state.value.externalHands)
        assertNoAndroidHold()
    }

    @Test fun protectionMetadataProjectsHandsRulesWithoutEditingLocalPreferences() = runTest {
        val node = node(this)
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"armed":false,"protected_packages":["app.private","app.auth"]}}"""))
        hands.refresh(); runCurrent()
        assertEquals(listOf("app.auth", "app.private"), node.state.value.protectedCustom)
        assertNoAndroidHold()
    }
}
