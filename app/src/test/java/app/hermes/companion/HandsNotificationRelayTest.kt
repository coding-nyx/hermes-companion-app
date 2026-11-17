package app.hermes.companion

import android.app.Application
import app.hermes.hands.client.HandsReply
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HandsNotificationRelayTest {
    private val clock = AtomicLong(1_000_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var route: HandsNotificationRelay.Route? = baseRoute()
    private var localPause = false
    private val store = MemoryOutbox()
    private val gateway = FakeGateway()
    private val phone = FakePhone()
    private var status = HandsNotificationRelay.RelayState()
    private lateinit var relay: HandsNotificationRelay

    @Before fun setup() { relay = model() }
    @After fun cleanup() { relay.close(); scope.cancel() }
    private fun model(outbox: MemoryOutbox = store) = HandsNotificationRelay(phone::command, { route }, { gateway }, outbox,
        { localPause }, scope, clock::get, { status = it })
    private fun event(id: String = UUID.randomUUID().toString(), pkg: String = APP, title: String = "Synthetic title", text: String = "Synthetic body", mode: String = "full", at: Long = clock.get(), category: String = "msg") =
        JSONObject().put("delivery_id", id).put("consent_version", phone.version).put("captured_at_ms", at).put("ts_ms", at)
            .put("notification", JSONObject().put("delivery_id", id).put("key", "abcdef0123456789abcdef0123456789")
                .put("package", pkg).put("title", title).put("text", text).put("category", category)
                .put("ongoing", false).put("clearable", true).put("passive", true).put("content_mode", mode))
    private fun add(row: JSONObject = event()): String { phone.events += row; return row.getString("delivery_id") }
    private suspend fun tick() { relay.pollOnce() }

    @Test fun requiresBothLocalRouteAndPhoneConsentAndNeverArmsOrCaptures() = runBlocking {
        route = null; tick(); assertTrue(phone.commands.isEmpty()); assertTrue(gateway.sent.isEmpty())
        route = baseRoute(); phone.enabled = false; tick(); assertTrue(gateway.sent.isEmpty())
        phone.enabled = true; phone.bound = false; tick(); assertTrue(gateway.sent.isEmpty())
        assertTrue(phone.commands.all { it.startsWith("device.notifications.") })
    }

    @Test fun encryptedDurabilityPrecedesAnyNetworkSendAndFailureStopsForwarding() = runBlocking {
        val id = add(); store.failWrite = true; tick()
        assertTrue(gateway.sent.isEmpty()); assertTrue(phone.acked.isEmpty())
        store.failWrite = false
        gateway.onSend = { sent ->
            val durable = JSONObject(checkNotNull(store.value)).getJSONArray("rows")
            assertTrue((0 until durable.length()).any { durable.getJSONObject(it).getString("delivery_id") == sent.id })
        }
        tick(); assertEquals(id, gateway.sent.single().id)
        assertEquals(2, JSONObject(checkNotNull(store.value)).getInt("version"))
    }

    @Test fun sendSuccessIsNotAckAndRetriesKeepUuidAndRespectThirtySeconds() = runBlocking {
        val id = add(); tick(); tick()
        assertEquals(1, gateway.sent.size); assertTrue(phone.acked.isEmpty())
        clock.addAndGet(29_999); tick(); assertEquals(1, gateway.sent.size)
        clock.incrementAndGet(); tick(); assertEquals(2, gateway.sent.size)
        assertTrue(gateway.sent.all { it.id == id }); assertTrue(phone.acked.isEmpty())
        gateway.accepted += id; tick()
        assertEquals(listOf(id), phone.acked); assertNull(store.value); assertEquals(1L, status.delivered)
    }

    @Test fun gatewayDispositionIsDurableBeforePhoneAckAndLostPhoneReplyDoesNotResend() = runBlocking {
        val id = add(); tick(); gateway.accepted += id; phone.failAck = true
        assertTrue(runCatching { tick() }.isFailure)
        val durable = JSONObject(checkNotNull(store.value)).getJSONArray("rows").getJSONObject(0)
        assertTrue(durable.getBoolean("ack_phone")); assertEquals(1, gateway.sent.size)
        phone.failAck = false; tick()
        assertEquals(listOf(id), phone.acked); assertEquals(1, gateway.sent.size); assertNull(store.value)
    }

    @Test fun durableGatewayAckCanFinishAfterRestartWithoutGatewayConnection() = runBlocking {
        val id = add(); tick(); gateway.accepted += id; phone.failAck = true
        runCatching { tick() }
        val restored = MemoryOutbox().also { it.value = store.value }
        relay.close(); relay = model(restored); gateway.support = false; phone.failAck = false
        tick(); assertEquals(listOf(id), phone.acked); assertEquals(1, gateway.sent.size); assertNull(restored.value)
    }

    @Test fun oldFormatAndForeignScopesNeverRestoreOrAckPhone() = runBlocking {
        store.value = "[{\"delivery_id\":\"legacy\"}]"
        tick(); assertNull(store.value); assertTrue(gateway.sent.isEmpty()); assertTrue(phone.acked.isEmpty())
        add(); gateway.support = false; tick(); val good = store.value
        val variants = listOf(baseRoute().copy(profile = "other"), baseRoute().copy(credentialHash = "another-digest"),
            baseRoute().copy(localVersion = 9), baseRoute().copy(origin = "https://other.invalid"), baseRoute().copy(deviceId = "other-device"))
        for (other in variants) {
            val isolated = MemoryOutbox().also { it.value = good }
            relay.close(); phone.events.clear(); route = other; relay = model(isolated); gateway.support = true
            tick(); assertTrue(gateway.sent.isEmpty()); assertNull(isolated.value); assertTrue(phone.acked.isEmpty())
        }
    }

    @Test fun handsRecipientOrConsentVersionChangeInvalidatesStoredData() = runBlocking {
        add(); gateway.support = false; tick(); val old = store.value
        relay.close(); phone.events.clear(); phone.version++
        val restored = MemoryOutbox().also { it.value = old }; relay = model(restored); gateway.support = true
        tick(); assertTrue(gateway.sent.isEmpty()); assertTrue(phone.acked.isEmpty()); assertNull(restored.value)
        add(); gateway.support = false; tick(); val second = restored.value
        relay.close(); phone.events.clear(); phone.clientId = "different-hands-recipient"
        val different = MemoryOutbox().also { it.value = second }; relay = model(different); gateway.support = true
        tick(); assertTrue(gateway.sent.isEmpty()); assertTrue(phone.acked.isEmpty()); assertNull(different.value)
    }

    @Test fun receiverNeedsPassiveAndAckSupportAndContentHonorsPhoneCap() = runBlocking {
        phone.content = "title"
        val id = add(); gateway.support = false; tick(); assertTrue(gateway.sent.isEmpty()); assertNotNull(store.value)
        gateway.support = true; tick()
        val n = JSONObject(gateway.sent.single().body)
        assertEquals(id, n.getString("delivery_id")); assertTrue(n.getBoolean("passive"))
        assertEquals("title", n.getString("content_mode")); assertEquals("Synthetic title", n.getString("title")); assertEquals("", n.getString("text"))
    }

    @Test fun packageCallProtectionAndSelfEchoFiltersCannotBeBypassedByHandsPayload() = runBlocking {
        phone.apps = setOf(APP, "app.hermes.hands", "org.telegram.messenger"); phone.includeCalls = false
        add(event(pkg = "com.foreign.app")); add(event(pkg = "app.hermes.hands")); add(event(pkg = "org.telegram.messenger")); add(event(category = "call"))
        tick(); assertTrue(gateway.sent.isEmpty())
        phone.apps = emptySet(); add(); tick(); assertTrue(gateway.sent.isEmpty())
    }

    @Test fun retentionUsesNarrowerPhoneCapAndExpiredEventsNeverSend() = runBlocking {
        phone.retention = 1
        add(); gateway.support = false; tick()
        clock.addAndGet(60_001); gateway.support = true; tick()
        assertTrue(gateway.sent.isEmpty()); assertTrue(phone.acked.isEmpty()); assertEquals(1L, status.expired)
    }

    @Test fun capacityDefersExcessAtHandsWithoutAcknowledgingUndeliveredNotifications() = runBlocking {
        gateway.support = false
        repeat(100) { add() }; tick()
        phone.events.clear(); repeat(100) { add() }; tick()
        assertEquals(128, status.queued); assertEquals(72L, status.overflow); assertTrue(phone.acked.isEmpty())
        assertEquals(128, JSONObject(checkNotNull(store.value)).getJSONArray("rows").length())
        tick(); assertEquals(72L, status.overflow)
    }

    @Test fun capacityRejectionRetriesButTerminalGatewayPolicyRejectionCanDiscard() = runBlocking {
        val id = add(); tick(); gateway.rejected[id] = "dedupe_capacity"; tick()
        assertTrue(phone.acked.isEmpty()); assertNotNull(store.value)
        gateway.rejected[id] = "policy_blocked"; tick()
        assertEquals(listOf(id), phone.acked); assertEquals(1L, status.dropped); assertEquals(0L, status.delivered)
    }

    @Test fun localInvalidationWhilePhoneReadIsInFlightNeverPublishesOrAcksOldRoute() = runBlocking {
        withTimeout(2_000) {
            add(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            phone.beforeList = { entered.complete(Unit); release.await() }
            val polling = launch { tick() }; entered.await()
            relay.invalidateRoute(); route = baseRoute().copy(profile = "new-profile")
            release.complete(Unit); polling.join()
            assertTrue(gateway.sent.isEmpty()); assertTrue(phone.acked.isEmpty()); assertNull(store.value)
        }
    }

    @Test fun observedPrivacyOrScopeChangeBetweenReadAndSendClearsQueuedData() = runBlocking {
        add(); phone.onStream = { count -> if (count == 2) phone.version++ }
        tick(); assertTrue(gateway.sent.isEmpty()); assertTrue(phone.acked.isEmpty()); assertNull(store.value)
        phone.onStream = {}; add(); phone.onStream = { count -> if (count == 4) phone.privateEntry = true }
        tick(); assertTrue(gateway.sent.isEmpty()); assertTrue(phone.acked.isEmpty()); assertNull(store.value)
    }

    @Test fun phoneScopeIsRecheckedForEachIndividualPublish() = runBlocking {
        val first = add(); add()
        phone.onStream = { count -> if (count == 4) phone.version++ }
        tick()
        assertEquals(listOf(first), gateway.sent.map { it.id })
        assertNull(store.value); assertTrue(phone.acked.isEmpty())
    }

    @Test fun routeOffAndPauseDiscardWithoutAckingUnknownPhoneScope() = runBlocking {
        add(); gateway.support = false; tick(); assertNotNull(store.value)
        relay.invalidateRoute(); route = null; tick()
        assertNull(store.value); assertTrue(phone.acked.isEmpty())
        route = baseRoute(); phone.events.clear(); add(); tick(); relay.pause()
        assertNull(store.value); assertFalse(status.enabled); assertTrue(phone.acked.isEmpty())
    }

    private class MemoryOutbox : HandsNotificationRelay.Outbox {
        var value: String? = null
        var failWrite = false
        override fun read() = value
        override fun write(json: String) { check(!failWrite); value = json }
        override fun clear() { value = null }
    }
    private data class Sent(val id: String, val route: HandsNotificationRelay.Route, val body: String)
    private class FakeGateway : HandsNotificationRelay.Gateway {
        var support = true
        val accepted = mutableSetOf<String>()
        val rejected = mutableMapOf<String, String>()
        val sent = mutableListOf<Sent>()
        var onSend: (Sent) -> Unit = {}
        override fun supported() = support
        override fun acknowledged(id: String) = accepted.remove(id)
        override fun rejection(id: String) = rejected.remove(id)
        override fun send(route: HandsNotificationRelay.Route, timestamp: Long, notification: String): Boolean {
            val row = Sent(JSONObject(notification).getString("delivery_id"), route, notification)
            onSend(row); sent += row; return true
        }
    }
    private inner class FakePhone {
        var enabled = true; var bound = true; var privateEntry = false
        var clientId = "synthetic-companion-recipient"; var version = 7L; var retention = 15
        var apps = setOf(APP); var content = "full"; var includeCalls = true
        var failAck = false; var streamCalls = 0
        val events = mutableListOf<JSONObject>(); val acked = mutableListOf<String>(); val commands = mutableListOf<String>()
        var beforeList: suspend () -> Unit = {}
        var onStream: (Int) -> Unit = {}
        suspend fun command(action: String, args: JSONObject, controller: String): HandsReply {
            assertEquals(route?.controllerId, controller); commands += action
            val value = when (action) {
                "device.notifications.stream" -> {
                    onStream(++streamCalls)
                    JSONObject().put("enabled", enabled).put("nls_bound", bound).put("paused", privateEntry)
                        .put("client_id", clientId).put("consent_version", version).put("retention_minutes", retention)
                        .put("packages", JSONArray(apps)).put("content_mode", content).put("include_calls", includeCalls)
                }
                "device.notifications.list" -> {
                    beforeList()
                    JSONObject().put("consent_version", version).put("events", JSONArray(events))
                }
                "device.notifications.ack" -> {
                    if (failAck) return HandsReply(false, errorCode = "hands_unavailable")
                    val ids = args.getJSONArray("delivery_ids")
                    for (i in 0 until ids.length()) { val id = ids.getString(i); acked += id; events.removeAll { it.getString("delivery_id") == id } }
                    JSONObject().put("consent_version", version).put("acknowledged", ids.length())
                }
                else -> error("Unexpected command $action")
            }
            return HandsReply(true, value.toString())
        }
    }
    companion object {
        private const val APP = "com.synthetic.app"
        private fun baseRoute() = HandsNotificationRelay.Route("https://synthetic.invalid", "test-profile", "synthetic-device", "credential-digest", 2)
    }
}
