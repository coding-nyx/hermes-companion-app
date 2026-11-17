package app.hermes.hands.client

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HandsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryStore
    private lateinit var client: HandsClient

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        val endpoint = server.url("/").toString().trimEnd('/')
        store = MemoryStore(HandsConnection(endpoint, "approved-secret"))
        client = HandsClient(store, endpointAllowed = { it == endpoint })
    }
    @After fun teardown() { server.shutdown() }

    @Test fun forwardsBearerAndIsolatesLeaseByController() = runTest {
        server.enqueue(ok("""{"armed":true,"lease_id":"lease-a"}"""))
        assertTrue(client.command("device.arm", "{}", "host-a/profile-a").ok)
        val arm = takeRequest()
        assertEquals("Bearer approved-secret", arm.getHeader("Authorization"))
        server.enqueue(ok("{}"))
        client.command("device.click", """{"x":10,"y":20,"lease_id":"forged"}""", "host-b/profile-a")
        val other = JSONObject(takeRequest().body.readUtf8())
        assertFalse(other.has("lease_id"))
        assertFalse(other.getJSONObject("arguments").has("lease_id"))
        assertEquals("host-b/profile-a", other.getString("controller_id"))
        server.enqueue(ok("{}"))
        client.command("device.click", """{"x":10,"y":20}""", "host-a/profile-a")
        val owned = JSONObject(takeRequest().body.readUtf8())
        assertEquals("lease-a", owned.getString("lease_id"))
        assertEquals(10, owned.getJSONObject("arguments").getInt("x"))
    }

    @Test fun neverAdoptsLeaseFromStatus() = runTest {
        server.enqueue(ok("""{"armed":true,"lease_id":"other-client-lease"}"""))
        val status = client.command("device.status", "{}", "new-controller")
        assertFalse(JSONObject(status.resultJson).has("lease_id"))
        takeRequest()
        server.enqueue(ok("{}"))
        client.command("device.type", """{"text":"hello"}""", "new-controller")
        assertFalse(JSONObject(takeRequest().body.readUtf8()).has("lease_id"))
    }

    @Test fun forwardsSnapshotTokensPerControllerAndInvalidatesAfterMutation() = runTest {
        server.enqueue(ok("""{"snapshot_id":"snapshot-one","nodes":[{"ref":"e1"}]}"""))
        client.command("device.snapshot", "{}", "one")
        takeRequest()
        assertEquals("stale_ref", client.command("device.click", """{"ref":"e1"}""", "two").errorCode)
        server.enqueue(ok("{}"))
        assertTrue(client.command("device.click", """{"ref":"e1"}""", "one").ok)
        assertEquals("snapshot-one", JSONObject(takeRequest().body.readUtf8()).getJSONObject("arguments").getString("snapshot_id"))
        assertEquals("stale_ref", client.command("device.click", """{"ref":"e1"}""", "one").errorCode)
        assertEquals(2, server.requestCount)
    }

    @Test fun learnsPostActionSnapshotForRefSwipeAndNormalizesCoordinateClick() = runTest {
        server.enqueue(ok("""{"snapshot":{"snapshot_id":"follow-up","nodes":[{"ref":"e2"},{"ref":"e3"}]}}"""))
        client.command("device.click", """{"xy":[42,64]}""", "one")
        val click = JSONObject(takeRequest().body.readUtf8()).getJSONObject("arguments")
        assertEquals(42, click.getInt("x")); assertEquals(64, click.getInt("y"))
        server.enqueue(ok("{}"))
        client.command("device.swipe", """{"from_ref":"e2","to_ref":"e3"}""", "one")
        val swipe = JSONObject(takeRequest().body.readUtf8()).getJSONObject("arguments")
        assertEquals("follow-up", swipe.getString("snapshot_id"))
        assertFalse(swipe.has("x1"))
    }

    @Test fun anotherControllerCannotTurnItsMissingLeaseIntoGlobalStop() = runTest {
        val reply = client.command("device.disarm", """{"lease_id":"someone-elses"}""", "other-controller")
        assertEquals("lease_mismatch", reply.errorCode)
        assertEquals(0, server.requestCount)
        server.enqueue(ok("""{"armed":false}"""))
        assertTrue(client.stopAll())
        val localStop = JSONObject(takeRequest().body.readUtf8())
        assertEquals("companion-local-ui", localStop.getString("controller_id"))
        assertFalse(localStop.has("lease_id"))
    }

    @Test fun staleCleanupCannotReleaseNewerLeaseForSameController() = runTest {
        server.enqueue(ok("""{"armed":true,"lease_id":"new-current-lease"}"""))
        client.command("device.arm", "{}", "controller")
        takeRequest()
        val reply = client.command("device.disarm", """{"lease_id":"old-lease"}""", "controller")
        assertEquals("lease_mismatch", reply.errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun concurrentHoldersAreRetainedAndOnlyRequestedHolderIsReleased() = runTest {
        server.enqueue(ok("""{"armed":true,"lease_id":"hook-holder"}"""))
        client.command("device.arm", "{}", "controller")
        takeRequest()
        server.enqueue(ok("""{"armed":true,"lease_id":"tool-holder"}"""))
        client.command("device.arm", "{}", "controller")
        takeRequest()
        server.enqueue(ok("""{"armed":true}"""))
        assertTrue(client.command("device.disarm", """{"lease_id":"hook-holder"}""", "controller").ok)
        assertEquals("hook-holder", JSONObject(takeRequest().body.readUtf8()).getString("lease_id"))
        server.enqueue(ok("{}"))
        client.command("device.snapshot", "{}", "controller")
        assertEquals("tool-holder", JSONObject(takeRequest().body.readUtf8()).getString("lease_id"))
        server.enqueue(ok("""{"armed":true}"""))
        assertTrue(client.command("device.arm.renew", """{"lease_id":"tool-holder"}""", "controller").ok)
        assertEquals("tool-holder", JSONObject(takeRequest().body.readUtf8()).getString("lease_id"))
    }

    @Test fun preservesUnsupportedErrorsWithoutFallback() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":false,"error":{"code":"unsupported","message":"Backend does not support SMS"}}"""))
        val reply = client.command("device.sms.list", "{}", "controller")
        assertFalse(reply.ok)
        assertEquals("unsupported", reply.errorCode)
        assertEquals("Backend does not support SMS", reply.message)
        assertTrue(client.state.value.configured)
        assertEquals(1, server.requestCount)
    }

    @Test fun localStopBypassesSlowCommandAndDiscardsItsLateResult() = runTest {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (JSONObject(request.body.readUtf8()).getString("action")) {
                "device.arm" -> ok("""{"armed":true,"lease_id":"holder"}""")
                "device.snapshot" -> {
                    started.countDown()
                    check(stopped.await(5, TimeUnit.SECONDS))
                    ok("""{"armed":true,"nodes":[{"ref":"late"}]}""")
                }
                "device.disarm" -> { stopped.countDown(); ok("""{"armed":false}""") }
                else -> MockResponse().setResponseCode(400)
            }
        }
        client.command("device.arm", "{}", "controller")
        val pending = async { client.command("device.snapshot", "{}", "controller") }
        withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
        assertTrue(client.stopAll())
        assertEquals("disarmed", pending.await().errorCode)
        assertFalse(client.state.value.armed)
    }

    @Test fun unauthorizedOwnerStaysSelected() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals("hands_unauthorized", client.command("device.snapshot", "{}", "controller").errorCode)
        assertTrue(client.state.value.configured)
        assertFalse(client.state.value.reachable)
        assertNotNull(store.load())
    }

    @Test fun missingEncryptedCredentialNeverSelectsLegacyControl() = runTest {
        val selectedButUnreadable = object : HandsConnectionStore {
            override fun load(): HandsConnection? = null
            override fun isSelected() = true
            override fun save(connection: HandsConnection) = Unit
            override fun clear() = Unit
        }
        val unavailable = HandsClient(selectedButUnreadable)
        assertTrue(unavailable.state.value.configured)
        assertEquals("hands_credentials_unavailable", unavailable.command("device.snapshot", "{}", "controller").errorCode)
        assertFalse(unavailable.disconnect())
        assertTrue(unavailable.state.value.configured)
        assertEquals(0, server.requestCount)
    }

    @Test fun refusesModeSwitchWhileUnknownControllerIsArmed() = runTest {
        server.enqueue(ok("""{"armed":true}"""))
        assertFalse(client.disconnect())
        assertTrue(client.state.value.configured)
        assertNotNull(store.load())
    }

    @Test fun releasesOwnLeaseBeforeDisconnecting() = runTest {
        server.enqueue(ok("""{"armed":true,"lease_id":"owned"}"""))
        client.command("device.arm", "{}", "owner")
        takeRequest()
        server.enqueue(ok("""{"armed":false}"""))
        server.enqueue(ok("""{"armed":false}"""))
        assertTrue(client.disconnect())
        val stop = JSONObject(takeRequest().body.readUtf8())
        assertEquals("device.disarm", stop.getString("action"))
        assertEquals("owned", stop.getString("lease_id"))
        assertNull(store.load())
        assertFalse(client.state.value.configured)
    }

    @Test fun restrictsHandoffEndpointAndSeparatesOriginsAndProfiles() {
        assertTrue(HandsClient.isHandsEndpoint("http://127.0.0.1:8765"))
        for (endpoint in listOf("http://evil.test:8765", "http://127.0.0.1:8766", "http://token@127.0.0.1:8765", "http://127.0.0.1:8765/redirect")) {
            assertFalse(endpoint, HandsClient.isHandsEndpoint(endpoint))
        }
        assertNotEquals(HandsClient.controllerId("https://lab", "one"), HandsClient.controllerId("https://raj", "one"))
        assertNotEquals(HandsClient.controllerId("https://lab", "one"), HandsClient.controllerId("https://lab", "two"))
        assertEquals(HandsClient.controllerId("https://lab/", "one"), HandsClient.controllerId("https://lab", "one"))
    }

    @Test fun controllerCleanupReleasesOnlyItsKnownHoldersAndPreservesAnotherOwner() = runTest {
        for ((controller, holder) in listOf("closing" to "closed-one", "closing" to "closed-two", "active" to "active-holder")) {
            server.enqueue(ok("""{"armed":true,"lease_id":"$holder"}"""))
            assertTrue(client.command("device.arm", "{}", controller).ok)
            takeRequest()
        }
        repeat(2) { server.enqueue(ok("""{"armed":true}""")) }
        assertTrue(client.releaseController("closing"))
        val releases = List(2) { JSONObject(takeRequest().body.readUtf8()) }
        assertEquals(setOf("closed-one", "closed-two"), releases.map { it.getString("lease_id") }.toSet())
        releases.forEach {
            assertEquals("device.disarm", it.getString("action"))
            assertEquals("closing", it.getString("controller_id"))
            assertTrue(it.has("lease_id"))
        }
        server.enqueue(ok("{}"))
        client.command("device.snapshot", "{}", "active")
        assertEquals("active-holder", JSONObject(takeRequest().body.readUtf8()).getString("lease_id"))
        val before = server.requestCount
        assertTrue(client.releaseController("closing"))
        assertEquals(before, server.requestCount)
    }

    @Test fun cleanupNeverBorrowsStatusLeaseOrSendsGlobalStopWithoutKnownHolder() = runTest {
        server.enqueue(ok("""{"armed":true,"lease_id":"foreign-holder"}"""))
        client.refresh()
        assertEquals("/v1/status", takeRequest().path)
        assertTrue(client.releaseController("unknown"))
        assertEquals(1, server.requestCount)
        assertTrue(client.state.value.armed)
        assertTrue(client.state.value.configured)
    }

    @Test fun cleanupClearsOnlyThatControllersSnapshotReferences() = runTest {
        for (controller in listOf("closing", "active")) {
            server.enqueue(ok("""{"snapshot_id":"$controller-snapshot","nodes":[{"ref":"e1"}]}"""))
            client.command("device.snapshot", "{}", controller)
            takeRequest()
        }
        assertTrue(client.releaseController("closing"))
        assertEquals("stale_ref", client.command("device.click", """{"ref":"e1"}""", "closing").errorCode)
        server.enqueue(ok("{}"))
        assertTrue(client.command("device.click", """{"ref":"e1"}""", "active").ok)
        val forwarded = JSONObject(takeRequest().body.readUtf8()).getJSONObject("arguments")
        assertEquals("active-snapshot", forwarded.getString("snapshot_id"))
    }

    @Test fun uncertainControllerCleanupKeepsSelectionAndOnlyRetriesTheSameOwnedHolder() = runTest {
        server.enqueue(ok("""{"armed":true,"lease_id":"owned"}"""))
        client.command("device.arm", "{}", "owner"); takeRequest()
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(client.releaseController("owner"))
        val first = JSONObject(takeRequest().body.readUtf8())
        assertEquals("owned", first.getString("lease_id"))
        assertEquals("hands_release_uncertain", client.state.value.error)
        assertTrue(client.state.value.configured)
        server.enqueue(ok("""{"armed":false}"""))
        assertTrue(client.releaseController("owner"))
        val second = JSONObject(takeRequest().body.readUtf8())
        assertEquals(first.getString("lease_id"), second.getString("lease_id"))
        assertEquals("owner", second.getString("controller_id"))
    }

    @Test fun lateArmAfterLaneCleanupIsReleasedScopedWithoutStoppingNewOwner() = runTest {
        val started = CountDownLatch(1)
        val finishOldArm = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.clone().readUtf8())
                return when {
                    body.getString("action") == "device.arm" && body.getString("controller_id") == "closing" -> {
                        started.countDown(); check(finishOldArm.await(5, TimeUnit.SECONDS))
                        ok("""{"armed":true,"lease_id":"late-old-holder"}""")
                    }
                    body.getString("action") == "device.arm" -> ok("""{"armed":true,"lease_id":"new-owner-holder"}""")
                    body.getString("action") == "device.disarm" -> ok("""{"armed":true}""")
                    else -> ok("{}")
                }
            }
        }
        val old = async { client.command("device.arm", "{}", "closing") }
        withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
        assertTrue(client.releaseController("closing"))
        assertTrue(client.command("device.arm", "{}", "active").ok)
        finishOldArm.countDown()
        assertEquals("disarmed", old.await().errorCode)
        val requests = List(3) { JSONObject(takeRequest().body.readUtf8()) }
        val cleanup = requests.single { it.getString("action") == "device.disarm" }
        assertEquals("closing", cleanup.getString("controller_id"))
        assertEquals("late-old-holder", cleanup.getString("lease_id"))
        client.command("device.snapshot", "{}", "active")
        assertEquals("new-owner-holder", JSONObject(takeRequest().body.readUtf8()).getString("lease_id"))
    }

    @Test fun protectionAndHoldMetadataOnlyComeFromLiveStatusAndMissingDataIsUnknown() = runTest {
        server.enqueue(ok("""{"armed":true,"protected_packages":["app.private","app.accounts.*",7,"","app.*.invalid","APP.UPPER","*"],"keep_awake":{"enabled":true,"armed_hold":true}}"""))
        val state = client.refresh(); takeRequest()
        assertEquals(setOf("app.private", "app.accounts.*"), state.protectedPackages)
        assertTrue(JSONObject(state.keepAwakeJson).getBoolean("enabled"))
        assertTrue(JSONObject(state.keepAwakeJson).getBoolean("available"))
        server.enqueue(ok("""{"protected_packages":["forged.provider.field"],"keep_awake":{"enabled":false}}"""))
        client.command("device.sms.list", "{}", "owner"); takeRequest()
        assertEquals(setOf("app.private", "app.accounts.*"), client.state.value.protectedPackages)
        assertTrue(JSONObject(client.state.value.keepAwakeJson).getBoolean("enabled"))
        server.enqueue(ok("""{"armed":false}"""))
        client.refresh(); takeRequest()
        assertTrue(client.state.value.protectedPackages.isEmpty())
        assertFalse(JSONObject(client.state.value.keepAwakeJson).getBoolean("available"))
        assertFalse(JSONObject(client.state.value.keepAwakeJson).getBoolean("enabled"))
    }

    @Test fun failedStatusDoesNotClaimAnOldWakeHoldIsStillAvailable() = runTest {
        server.enqueue(ok("""{"keep_awake":{"enabled":true}}"""))
        client.refresh(); takeRequest()
        server.enqueue(MockResponse().setResponseCode(401))
        client.refresh(); takeRequest()
        assertFalse(client.state.value.reachable)
        assertFalse(JSONObject(client.state.value.keepAwakeJson).getBoolean("available"))
    }

    @Test fun shizukuScreenReadinessComesOnlyFromStatusWithoutBorrowingItsLease() = runTest {
        server.enqueue(ok("""{"armed":true,"a11y_bound":false,"screen_control_available":true,"lease_id":"foreign-holder"}"""))
        val status = client.command("device.status", "{}", "owner")
        takeRequest()
        assertTrue(client.state.value.screenControlAvailable)
        assertFalse(client.state.value.a11yBound)
        assertFalse(JSONObject(status.resultJson).has("lease_id"))
        server.enqueue(ok("""{"screen_control_available":false,"lanes":"l0"}"""))
        client.command("device.snapshot", "{}", "owner")
        assertFalse(JSONObject(takeRequest().body.readUtf8()).has("lease_id"))
        assertTrue(client.state.value.screenControlAvailable)
        for ((metadata, available) in listOf(
            """{"a11y_bound":false,"lanes":"l0+l1"}""" to true,
            """{"screen_control_available":false,"lanes":"l0+l1"}""" to false,
            """{"a11y_bound":true}""" to false,
        )) {
            server.enqueue(ok(metadata)); client.refresh(); takeRequest()
            assertEquals(available, client.state.value.screenControlAvailable)
        }
        server.enqueue(MockResponse().setResponseCode(401)); client.refresh(); takeRequest()
        assertFalse(client.state.value.screenControlAvailable)
    }

    @Test fun permissionApprovalAndDefinitiveDisarmForgetAllOwnedHoldersAndRefs() = runTest {
        for ((action, stopped) in listOf(
            "device.files.enable" to """{"requested":true,"local_approval_required":true}""",
            "device.status" to """{"armed":false}""",
            "device.secure_entry" to """{"request_id":"110c2ef0-196e-442b-bc47-c46bac2a8905","status":"pending","privacy_active":true}""",
        )) {
            server.enqueue(ok("""{"keep_awake":{"enabled":true}}"""))
            client.refresh(); takeRequest()
            for (owner in listOf("first", "second")) {
                server.enqueue(ok("""{"armed":true,"lease_id":"$owner-holder"}"""))
                client.command("device.arm", "{}", owner); takeRequest()
                server.enqueue(ok("""{"snapshot_id":"$owner-snapshot","nodes":[{"ref":"e1"}]}"""))
                client.command("device.snapshot", "{}", owner); takeRequest()
            }
            server.enqueue(ok(stopped))
            val approval = client.command(action, "{}", "first")
            takeRequest()
            assertTrue(approval.ok)
            assertEquals(JSONObject(stopped).keys().asSequence().toSet(), JSONObject(approval.resultJson).keys().asSequence().toSet())
            assertFalse(client.state.value.armed)
            assertFalse(JSONObject(client.state.value.keepAwakeJson).getBoolean("available"))
            val count = server.requestCount
            for (owner in listOf("first", "second")) {
                assertEquals("stale_ref", client.command("device.click", """{"ref":"e1"}""", owner).errorCode)
                assertEquals("lease_mismatch", client.command("device.arm.renew", """{"lease_id":"$owner-holder"}""", owner).errorCode)
                assertTrue(client.releaseController(owner))
            }
            assertEquals(count, server.requestCount)
        }
    }

    @Test fun localApprovalDiscardsLateControlReplyWithoutAdoptingProviderMetadata() = runTest {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (JSONObject(request.body.readUtf8()).getString("action")) {
                "device.arm" -> ok("""{"armed":true,"lease_id":"holder"}""")
                "device.snapshot" -> {
                    started.countDown(); check(finish.await(5, TimeUnit.SECONDS))
                    ok("""{"armed":true,"snapshot_id":"late","nodes":[{"ref":"e1"}]}""")
                }
                "device.accessibility.enable" -> ok("""{"local_approval_required":true,"protected_packages":["untrusted"],"keep_awake":{"enabled":true}}""")
                else -> MockResponse().setResponseCode(400)
            }
        }
        client.command("device.arm", "{}", "owner")
        val pending = async { client.command("device.snapshot", "{}", "owner") }
        withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(client.command("device.accessibility.enable", "{}", "owner").ok)
        } finally { finish.countDown() }
        assertEquals("disarmed", pending.await().errorCode)
        assertFalse(client.state.value.armed)
        assertTrue(client.state.value.protectedPackages.isEmpty())
        assertFalse(JSONObject(client.state.value.keepAwakeJson).getBoolean("available"))
        assertEquals("stale_ref", client.command("device.click", """{"ref":"e1"}""", "owner").errorCode)
        assertTrue(client.releaseController("owner"))
        assertEquals(3, server.requestCount)
    }

    private fun takeRequest(): okhttp3.mockwebserver.RecordedRequest =
        requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) { "Expected Hands HTTP request within 5 seconds" }

    private fun ok(result: String) = MockResponse().setBody("""{"ok":true,"result":$result}""")
    private class MemoryStore(private var connection: HandsConnection?) : HandsConnectionStore {
        override fun load() = connection
        override fun save(connection: HandsConnection) { this.connection = connection }
        override fun clear() { connection = null }
    }
}
