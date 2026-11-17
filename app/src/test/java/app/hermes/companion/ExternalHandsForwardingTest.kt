package app.hermes.companion

import android.app.Application
import android.content.Context
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.model.DeviceCommand
import app.hermes.companion.model.DeviceCred
import app.hermes.hands.client.HandsClient
import app.hermes.hands.client.HandsConnection
import app.hermes.hands.client.HandsConnectionStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalHandsForwardingTest {
    private lateinit var server: MockWebServer
    private lateinit var app: CompanionApp
    private lateinit var credentials: DeviceCredStore
    private lateinit var hands: HandsClient
    private lateinit var sticky: StickyStore

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        app = CompanionApp().apply {
            Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
                .invoke(this, RuntimeEnvironment.getApplication())
        }
        credentials = DeviceCredStore(app.getSharedPreferences("external-hands-forwarding", 0))
        credentials.save(DeviceCred("phone-a", "paired-profile", "relay-secret", "https://lab.example"))
        credentials.save(DeviceCred("phone-b", "paired-profile", "relay-secret", "https://raj.example"))
        val endpoint = server.url("/").toString().trimEnd('/')
        hands = HandsClient(object : HandsConnectionStore {
            override fun load() = HandsConnection(endpoint, "hands-secret")
            override fun save(connection: HandsConnection) = Unit
            override fun clear() = Unit
        }, endpointAllowed = { it == endpoint })
        sticky = StickyStore(app).apply { stayConnected = false; notifStreamEnabled = false }
    }
    @After fun teardown() { server.shutdown() }

    private fun takeRequest(): okhttp3.mockwebserver.RecordedRequest =
        requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) { "Expected Hands HTTP request within 5 seconds" }

    private fun node(scope: TestScope, owner: HandsClient = hands): DeviceNodeCoordinator {
        check(!sticky.notifStreamEnabled) { "Enable forwarding after Coordinator initialization in this unit fixture" }
        return DeviceNodeCoordinator(app, HostClientPool(), credentials, sticky, scope.backgroundScope, owner).also {
            // These tests cover explicit command routing. The relay has its own transport suite;
            // stop its background poller before enabling this fixture's pinned destination.
            val relay = DeviceNodeCoordinator::class.java.getDeclaredField("notifications")
                .apply { isAccessible = true }.get(it) as HandsNotificationRelay
            relay.pause()
        }
    }

    @Test fun selectedExternalSnapshotRunsWhileLocalAccessibilityAndArmAreOff() = runTest {
        val node = node(this)
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"app":"example","nodes":[{"ref":"e1","text":"Button"}]}}"""))
        val result = node.forwardExternalCommand("https://lab.example", DeviceCommand("one", "device.snapshot", deviceId = "phone-a"))!!
        assertTrue(result.ok)
        assertEquals("e1", JSONObject(result.resultJson).getJSONArray("nodes").getJSONObject(0).getString("ref"))
        val sent = JSONObject(takeRequest().body.readUtf8())
        assertEquals(HandsClient.controllerId(deviceLaneKey("https://lab.example"), "paired-profile"), sent.getString("controller_id"))
    }

    @Test fun externalUnsupportedRemainsAnExternalFailureWithoutLocalFallback() = runTest {
        val node = node(this)
        server.enqueue(MockResponse().setBody("""{"ok":false,"error":{"code":"unsupported_action","message":"SMS is unavailable"}}"""))
        val result = node.forwardExternalCommand("https://lab.example", DeviceCommand("one", "device.sms.list"))!!
        assertFalse(result.ok)
        assertEquals("unsupported_action", result.errorCode)
        assertEquals("SMS is unavailable", result.errorMessage)
        assertEquals(1, server.requestCount)
    }

    @Test fun hostAndProfileIdentityRemainDistinctAndDoNotShareLease() = runTest {
        val node = node(this)
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"lease_id":"owner-holder","armed":true}}"""))
        node.forwardExternalCommand("https://lab.example", DeviceCommand("arm", "device.arm", profile = "one"))
        val owner = JSONObject(takeRequest().body.readUtf8()).getString("controller_id")
        for ((host, profile) in listOf("https://raj.example" to "one", "https://lab.example" to "two")) {
            server.enqueue(MockResponse().setBody("""{"ok":false,"error":{"code":"controller_busy"}}"""))
            node.forwardExternalCommand(host, DeviceCommand("next", "device.snapshot", profile = profile))
            val request = JSONObject(takeRequest().body.readUtf8())
            assertNotEquals(owner, request.getString("controller_id"))
            assertFalse(request.has("lease_id"))
        }
    }

    @Test fun rejectsUnpairedHostOrMismatchedDeviceBeforeForwarding() = runTest {
        val node = node(this)
        assertEquals("unauthorized", node.forwardExternalCommand("https://unknown.example", DeviceCommand("one", "device.arm"))!!.errorCode)
        assertEquals("unauthorized", node.forwardExternalCommand("https://lab.example", DeviceCommand("two", "device.arm", deviceId = "phone-b"))!!.errorCode)
        assertEquals(0, server.requestCount)
    }

    @Test fun pairedHostWithoutHandsAlwaysGetsHandsRequiredAndNoLocalFallback() = runTest {
        val absent = HandsClient(object : HandsConnectionStore {
            override fun load(): HandsConnection? = null
            override fun save(connection: HandsConnection) = Unit
            override fun clear() = Unit
        })
        val node = node(this, absent)
        for (action in listOf("device.arm", "device.snapshot", "device.sms.list", "device.files", "device.status")) {
            val result = node.forwardExternalCommand("https://lab.example", DeviceCommand("missing", action))!!
            assertFalse(result.ok)
            assertEquals(action, "hands_required", result.errorCode)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun providerFilesAndConsentedNotificationCommandsUsePairedOwnerHolder() = runTest {
        sticky.notifStreamOrigin = "https://lab.example"
        sticky.setNotifStreamProfile("https://lab.example", "paired-profile")
        val node = node(this)
        sticky.notifStreamEnabled = true
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"armed":true,"lease_id":"paired-holder"}}"""))
        assertTrue(node.forwardExternalCommand("https://lab.example", DeviceCommand("arm", "device.arm"))!!.ok)
        val arm = JSONObject(takeRequest().body.readUtf8())
        val cases = listOf(
            "device.sms.list" to """{"limit":1}""",
            "device.files" to """{"action":"stat","path":"/Download/synthetic"}""",
            "device.notifications.list" to """{"after_cursor":0,"origin":"https://lab.example","profile":"paired-profile"}""",
        )
        for ((action, arguments) in cases) {
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"synthetic":true}}"""))
            assertTrue(node.forwardExternalCommand("https://lab.example", DeviceCommand(action, action, argumentsJson = arguments))!!.ok)
            val forwarded = JSONObject(takeRequest().body.readUtf8())
            assertEquals(action, forwarded.getString("action"))
            assertEquals(arm.getString("controller_id"), forwarded.getString("controller_id"))
            assertEquals("paired-holder", forwarded.getString("lease_id"))
            assertFalse(forwarded.getJSONObject("arguments").has("origin"))
            assertFalse(forwarded.getJSONObject("arguments").has("profile"))
        }
    }

    @Test fun notificationRoutingCannotLeakIntoAnotherPairedHostOrProfile() = runTest {
        sticky.notifStreamOrigin = "https://lab.example"
        sticky.setNotifStreamProfile("https://lab.example", "paired-profile")
        val node = node(this)
        sticky.notifStreamEnabled = true
        val requests = listOf(
            "https://raj.example" to DeviceCommand("foreign-host", "device.notifications.list"),
            "https://lab.example" to DeviceCommand("foreign-profile", "device.notifications.list", profile = "other-profile"),
            "https://lab.example" to DeviceCommand("forged-route", "device.notifications.list", argumentsJson = """{"origin":"https://raj.example"}"""),
        )
        for ((origin, request) in requests) {
            assertEquals("notification_scope_mismatch", node.forwardExternalCommand(origin, request)!!.errorCode)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun disabledForwardingCannotBeBypassedWithAnExplicitNotificationCommand() = runTest {
        sticky.notifStreamOrigin = "https://lab.example"
        sticky.setNotifStreamProfile("https://lab.example", "paired-profile")
        sticky.notifStreamEnabled = false
        val node = node(this)
        for (action in listOf("device.notifications.list", "device.notifications.actions", "device.notifications.reply")) {
            assertEquals("notification_scope_mismatch", node.forwardExternalCommand("https://lab.example", DeviceCommand(action, action))!!.errorCode)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun withdrawingNotificationApprovalDropsAnInFlightBody() = runTest {
        sticky.notifStreamOrigin = "https://lab.example"
        sticky.setNotifStreamProfile("https://lab.example", "paired-profile")
        val node = node(this)
        sticky.notifStreamEnabled = true
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                sticky.notifStreamEnabled = false
                sticky.notifStreamConsentVersion++
                return MockResponse().setBody("""{"ok":true,"result":{"synthetic_private_body":"must remain local"}}""")
            }
        }
        val result = node.forwardExternalCommand("https://lab.example", DeviceCommand("pending", "device.notifications.list"))!!
        assertEquals("notification_scope_mismatch", result.errorCode)
        assertFalse(result.resultJson.contains("must remain local"))
        assertEquals(1, server.requestCount)
    }

    @Test fun statusLeaseCannotBeBorrowedByAHostForRenewalOrCleanup() = runTest {
        val node = node(this)
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"armed":true,"lease_id":"foreign-holder"}}"""))
        val status = node.forwardExternalCommand("https://lab.example", DeviceCommand("status", "device.status"))!!
        assertFalse(JSONObject(status.resultJson).has("lease_id"))
        takeRequest()
        for (action in listOf("device.arm.renew", "device.disarm")) {
            val reply = node.forwardExternalCommand("https://lab.example", DeviceCommand(action, action,
                argumentsJson = """{"lease_id":"foreign-holder"}"""))!!
            assertEquals("lease_mismatch", reply.errorCode)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun closingOneOriginOnlyReleasesItsTrackedControllerHolders() = runTest {
        val node = node(this)
        for ((origin, holder) in listOf("https://lab.example" to "lab-holder", "https://raj.example" to "raj-holder")) {
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"armed":true,"lease_id":"$holder"}}"""))
            assertTrue(node.forwardExternalCommand(origin, DeviceCommand(holder, "device.arm"))!!.ok)
            takeRequest()
        }
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"armed":true}}"""))
        DeviceNodeCoordinator::class.java.getDeclaredMethod("markLane", String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(node, deviceLaneKey("https://lab.example"), false)
        runCurrent()
        val cleanup = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertNotNull(cleanup)
        val body = JSONObject(cleanup!!.body.readUtf8())
        assertEquals("device.disarm", body.getString("action"))
        assertEquals("lab-holder", body.getString("lease_id"))
        assertEquals(HandsClient.controllerId(deviceLaneKey("https://lab.example"), "paired-profile"), body.getString("controller_id"))
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"nodes":[]}}"""))
        node.forwardExternalCommand("https://raj.example", DeviceCommand("next", "device.snapshot"))
        assertEquals("raj-holder", JSONObject(takeRequest().body.readUtf8()).getString("lease_id"))
    }

    @Test fun malformedArgumentsAndUnknownOriginFailBeforeHandsRequest() = runTest {
        val node = node(this)
        assertEquals("bad_args", node.forwardExternalCommand("https://lab.example", DeviceCommand("bad", "device.files", argumentsJson = "[]"))!!.errorCode)
        credentials.clear("https://lab.example")
        assertEquals("unauthorized", node.forwardExternalCommand("https://lab.example", DeviceCommand("revoked", "device.arm"))!!.errorCode)
        assertEquals(0, server.requestCount)
    }
}
