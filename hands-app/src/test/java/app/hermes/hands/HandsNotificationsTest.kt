package app.hermes.hands

import android.app.Application
import android.content.Context
import app.hermes.companion.device.NotifActions
import app.hermes.companion.device.NotifStreamBus
import app.hermes.companion.device.NotifStreamEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
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
@Config(sdk = [35], application = Application::class)
class HandsNotificationsTest {
    private val clock = AtomicLong(1_000_000)
    private var privateEntry = false
    private var protectedApps = emptySet<String>()
    private lateinit var context: Context
    private lateinit var owner: HandsNotifications
    private val access = FakeAccess()
    private var permits = 0

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("hands.notifications", Context.MODE_PRIVATE).edit().clear().commit()
        owner = model()
        NotifStreamBus.listenerBound = true
    }
    @After fun cleanup() { owner.close(); NotifStreamBus.listenerBound = false }
    private fun model() = HandsNotifications(context, { protectedApps }, { privateEntry }, clock::get, access, false)
    private fun enable(content: String = "full", apps: Set<String> = setOf(APP), calls: Boolean = true, retention: Int = 15) {
        owner.configureLocal(true, CLIENT, apps, content, retention, calls)
    }
    private fun event(key: String = KEY, pkg: String = APP, title: String = "Synthetic title", text: String = "Synthetic body", category: String = "msg", at: Long = clock.get(), version: Long = owner.state.value.consentVersion, mode: String = "full") =
        NotifStreamEvent(key, pkg, title, text, category, false, true, at, version, at, mode)
    private fun send(action: String, args: JSONObject = JSONObject(), client: String = CLIENT, permitted: Boolean = true): JSONObject =
        checkNotNull(owner.command(client, "device.notifications.$action", args) { permits++; check(permitted) { "disarmed" } })
    private fun result(reply: JSONObject): JSONObject { assertTrue(reply.toString(), reply.getBoolean("ok")); return reply.getJSONObject("result") }
    private fun failure(reply: JSONObject): String { assertFalse(reply.toString(), reply.getBoolean("ok")); return reply.getJSONObject("error").getString("code") }
    private fun rows(args: JSONObject = JSONObject()) = result(send("list", args)).getJSONArray("events")
    private fun firstNotification() = rows().getJSONObject(0).getJSONObject("notification")

    @Test fun sharingStartsOffAndNeedsLocalRecipientAndApps() {
        assertFalse(owner.state.value.enabled)
        assertEquals("local_consent_required", failure(send("list")))
        assertFalse(owner.isPassiveAllowed(CLIENT, "device.notifications.list", JSONObject()))
        assertThrows(IllegalArgumentException::class.java) { owner.configureLocal(true, null, setOf(APP)) }
        assertThrows(IllegalArgumentException::class.java) { owner.configureLocal(true, CLIENT, emptySet()) }
        assertEquals(0, permits)
    }

    @Test fun pinnedRecipientOnlyAndNoHostRoutingArguments() {
        enable(); owner.accept(event())
        assertEquals("recipient_mismatch", failure(send("list", client = "other-client")))
        assertEquals("recipient_mismatch", failure(send("ack", JSONObject().put("delivery_ids", JSONArray().put(UUID.randomUUID().toString())), client = "other-client")))
        assertFalse(owner.isPassiveAllowed("other-client", "device.notifications.list", JSONObject()))
        assertEquals("bad_args", failure(send("stream.set", JSONObject().put("origin", "https://example.invalid"))))
        assertEquals(1, rows().length())
    }

    @Test fun pollHasStableUuidLegacyPayloadAndScopeAndNeverArms() {
        enable(); owner.accept(event())
        val first = rows().getJSONObject(0)
        assertEquals(first.getString("delivery_id"), rows().getJSONObject(0).getString("delivery_id"))
        UUID.fromString(first.getString("delivery_id"))
        assertEquals(owner.state.value.consentVersion, first.getLong("consent_version"))
        val n = first.getJSONObject("notification")
        assertEquals(APP, n.getString("package")); assertEquals(KEY, n.getString("key"))
        assertEquals(first.getString("delivery_id"), n.getString("delivery_id"))
        assertTrue(n.getBoolean("passive")); assertEquals("untrusted", n.getString("content_trust"))
        assertEquals(0, permits)
    }

    @Test fun ackIsPassiveDuplicateSafeCurrentScopeOnlyAndDoesNotDismiss() {
        enable(); owner.accept(event())
        val id = rows().getJSONObject(0).getString("delivery_id")
        val body = JSONObject().put("delivery_ids", JSONArray().put(id).put(id))
        repeat(2) { assertEquals(1, result(send("ack", body, permitted = false)).getInt("acknowledged")) }
        assertEquals(0, rows().length()); assertEquals(0, permits); assertEquals(0, access.mutations)
        enable()
        assertEquals(0, result(send("ack", body)).getInt("acknowledged"))
    }

    @Test fun ackRejectsMalformedIdsAndUnboundedArraysWithoutChangingRing() {
        enable(); owner.accept(event())
        assertEquals("bad_args", failure(send("ack", JSONObject().put("delivery_ids", JSONArray().put("not-a-uuid")))))
        assertEquals("bad_args", failure(send("ack", JSONObject().put("delivery_ids", JSONArray().also { a -> repeat(257) { a.put(UUID.randomUUID().toString()) } }))))
        assertEquals(1, rows().length())
    }

    @Test fun metadataAndTitleCapsCannotBeExpandedByEventPayload() {
        enable("metadata"); owner.accept(event())
        assertEquals("", firstNotification().getString("title")); assertEquals("", firstNotification().getString("text"))
        enable("title"); owner.accept(event())
        assertEquals("Synthetic title", firstNotification().getString("title")); assertEquals("", firstNotification().getString("text"))
        enable("full"); owner.accept(event(mode = "metadata"))
        assertEquals("", firstNotification().getString("title")); assertEquals("metadata", firstNotification().getString("content_mode"))
    }

    @Test fun sensitiveContentIsMinimizedAndDuplicateCapturesDoNotMultiply() {
        enable(); val incoming = event(title = "Verification code", text = "Your code is 123456")
        owner.accept(incoming); owner.accept(incoming)
        assertEquals(1, rows().length()); assertEquals("", firstNotification().getString("text"))
        assertEquals("", firstNotification().getString("title"))
    }

    @Test fun unselectedSelfEchoTelegramProtectedAndUnapprovedCallsAreSuppressed() {
        val apps = setOf(APP, "com.other.test", "app.hermes.hands", "app.hermes.companion", "org.telegram.messenger")
        enable(apps = apps, calls = false); protectedApps = setOf("com.other.*"); owner.refresh()
        listOf("com.unselected.test", "com.other.test", "app.hermes.hands", "app.hermes.companion", "org.telegram.messenger").forEach { owner.accept(event(pkg = it)) }
        owner.accept(event(category = "call")); owner.accept(event(category = "missed_call"))
        assertEquals(0, rows().length())
        owner.accept(event()); assertEquals(1, rows().length())
    }

    @Test fun wildcardApprovesAllAppsExceptProtectedAndSuppressed() {
        owner.configureLocal(true, CLIENT, setOf("*"), "full", 15, true)
        assertTrue(owner.state.value.enabled)
        assertTrue("*" in owner.state.value.packages)
        assertEquals("All notifications are shared only with the approved client.", owner.state.value.message)
        // Accepts normal apps
        owner.accept(event(pkg = "com.any.app"))
        assertEquals(1, rows().length())
        // Suppresses Telegram, Hands/Companion self echo
        owner.accept(event(pkg = "org.telegram.messenger"))
        owner.accept(event(pkg = "app.hermes.hands"))
        assertEquals(1, rows().length())
    }

    @Test fun remoteScopeCanNarrowFromWildcard() {
        owner.configureLocal(true, CLIENT, setOf("*"), "full", 15, true)
        val narrow = JSONObject().put("packages", JSONArray().put(APP)).put("content", "title")
        assertEquals("title", result(send("stream.set", narrow)).getString("content"))
        assertEquals(setOf(APP), owner.state.value.packages)
    }

    @Test fun staleGenerationFutureCaptureAndExpiredRowsAreDiscarded() {
        enable(retention = 1)
        owner.accept(event(version = owner.state.value.consentVersion - 1))
        owner.accept(event(at = clock.get() + 1)); owner.accept(event(at = clock.get() - 60_001))
        assertEquals(0, rows().length())
        owner.accept(event()); clock.addAndGet(60_001); owner.refresh()
        assertEquals(0, rows().length())
    }

    @Test fun processLocalBusCollectorAcceptsOnlyCurrentSyntheticConsentAndStopsWithSharing() {
        owner.close()
        owner = HandsNotifications(context, { protectedApps }, { privateEntry }, clock::get, access, true)
        enable()
        NotifStreamBus.emit(event(version = owner.state.value.consentVersion - 1))
        NotifStreamBus.emit(event())
        val deadline = System.nanoTime() + 2_000_000_000L
        while (owner.state.value.queued == 0 && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(1, rows().length())
        owner.configureLocal(false)
        NotifStreamBus.emit(event())
        assertEquals(0, owner.state.value.queued)
        assertFalse(NotifStreamBus.streamEnabled)
    }

    @Test fun ringIsBoundedAndCursorFiltersOnlyNarrow() {
        enable()
        repeat(300) { i -> owner.accept(event(key = i.toString(16).padStart(32, '0'))) ; clock.incrementAndGet() }
        assertEquals(256, owner.state.value.queued)
        val first = result(send("list", JSONObject().put("limit", 100)))
        assertEquals(100, first.getJSONArray("events").length()); assertTrue(first.getBoolean("has_more"))
        val next = result(send("list", JSONObject().put("after_cursor", first.getLong("next_cursor")).put("limit", 100)))
        assertTrue(next.getJSONArray("events").getJSONObject(0).getLong("cursor") > first.getLong("next_cursor"))
        assertEquals(0, rows(JSONObject().put("since_ms", clock.get() + 1)).length())
        assertEquals(0, rows(JSONObject().put("package", "com.absent.app")).length())
        assertEquals("bad_args", failure(send("list", JSONObject().put("limit", 101))))
        assertEquals("bad_args", failure(send("list", JSONObject().put("after_cursor", 1.2))))
    }

    @Test fun remoteScopeCanOnlyNarrowAndRequiresPermit() {
        enable(apps = setOf(APP, "com.other.test"))
        val version = owner.state.value.consentVersion
        val narrow = JSONObject().put("packages", JSONArray().put(APP)).put("content", "title").put("retention_minutes", 2).put("include_calls", false)
        assertEquals("disarmed", failure(send("stream.set", narrow, permitted = false)))
        assertEquals("title", result(send("stream.set", narrow)).getString("content"))
        assertTrue(owner.state.value.consentVersion > version)
        listOf(JSONObject().put("enabled", true), JSONObject().put("packages", JSONArray().put("com.other.test")),
            JSONObject().put("content", "full"), JSONObject().put("retention_minutes", 3), JSONObject().put("include_calls", true)).forEach {
            assertEquals("local_consent_required", failure(send("stream.set", it)))
        }
        assertEquals("local_consent_required", failure(send("stream.on")))
    }

    @Test fun remoteOffClearsDeliveriesAndCannotBeReenabled() {
        enable(); owner.accept(event())
        assertFalse(result(send("stream.set", JSONObject().put("enabled", false))).getBoolean("enabled"))
        assertEquals(0, owner.state.value.queued); assertFalse(NotifStreamBus.streamEnabled)
        assertFalse(result(send("stream")).getBoolean("enabled"))
        assertTrue(owner.isPassiveAllowed(CLIENT, "device.notifications.stream", JSONObject()))
        assertEquals("local_consent_required", failure(send("stream.set", JSONObject().put("enabled", true))))
    }

    @Test fun pauseClearsScopeBlocksPassiveAndMutationAndDoesNotResumeOldRows() {
        enable(); owner.accept(event()); val version = owner.state.value.consentVersion
        privateEntry = true; owner.refresh()
        assertTrue(owner.state.value.consentVersion > version); assertFalse(NotifStreamBus.streamEnabled)
        assertFalse(owner.isPassiveAllowed(CLIENT, "device.notifications.list", JSONObject()))
        assertFalse(owner.isPassiveAllowed(CLIENT, "device.notifications.nls", JSONObject()))
        assertEquals("notifications_paused", failure(send("list")))
        owner.accept(event()); privateEntry = false; owner.refresh()
        assertEquals(0, rows().length()); assertTrue(NotifStreamBus.streamEnabled)
    }

    @Test fun revokeOnlyStopsItsOwnRecipientAndRestartNeverResumesCapture() {
        enable(); owner.accept(event()); owner.onClientRevoked("other-client")
        assertTrue(owner.state.value.enabled); assertEquals(1, rows().length())
        owner.onClientRevoked(CLIENT); assertFalse(owner.state.value.enabled); assertNull(owner.state.value.clientId)
        enable(); owner.close(); val version = owner.state.value.consentVersion
        owner = model(); assertFalse(owner.state.value.enabled); assertTrue(owner.state.value.consentVersion > version)
        assertEquals(0, owner.state.value.queued)
    }

    @Test fun actionListingIsPassiveButTitlesHonorLocalCap() {
        enable("metadata"); owner.accept(event()); access.live = LiveHandsNotification(APP)
        val actions = result(send("actions", JSONObject().put("key", KEY), permitted = false)).getJSONArray("actions")
        assertEquals("", actions.getJSONObject(0).getString("title")); assertEquals(0, permits)
        assertEquals("recipient_mismatch", failure(send("actions", JSONObject().put("key", KEY), client = "other")))
    }

    @Test fun actualNotificationMutationsRequirePermitAndNeverEchoReplyBody() {
        enable(); owner.accept(event()); access.live = LiveHandsNotification(APP)
        val body = JSONObject().put("key", KEY).put("text", "synthetic reply body")
        assertEquals("disarmed", failure(send("reply", body, permitted = false))); assertEquals(0, access.mutations)
        val reply = result(send("reply", body)); assertTrue(reply.getBoolean("replied")); assertFalse(reply.toString().contains("synthetic reply body"))
        result(send("action", JSONObject().put("key", KEY).put("index", 0)))
        result(send("dismiss", JSONObject().put("key", KEY)))
        result(send("snooze", JSONObject().put("key", KEY).put("duration_ms", 1_000)))
        assertEquals(4, access.mutations)
    }

    @Test fun actionEligibilityIsRecheckedAfterPermitAndAgainstLivePackageAndChannel() {
        enable(); owner.accept(event()); access.live = LiveHandsNotification("com.foreign.test")
        assertEquals("protected_package", failure(send("dismiss", JSONObject().put("key", KEY))))
        access.live = LiveHandsNotification(APP, channelId = "hands")
        assertEquals("protected_package", failure(send("dismiss", JSONObject().put("key", KEY))))
        access.live = LiveHandsNotification(APP)
        val response = owner.command(CLIENT, "device.notifications.dismiss", JSONObject().put("key", KEY)) { protectedApps = setOf(APP) }!!
        assertEquals("unknown_notification", failure(response)); assertEquals(0, access.mutations)
    }

    @Test fun notificationActionReadCannotPublishAcrossAPrivacyPause() {
        enable(); owner.accept(event()); access.live = LiveHandsNotification(APP)
        access.onActionRead = { privateEntry = true }
        assertEquals("notifications_paused", failure(send("actions", JSONObject().put("key", KEY))))
        assertEquals(0, access.mutations)
    }

    @Test fun noSecretsOrOtherListenersAppearInBasicReadinessAndUnknownActionReturnsNull() {
        android.provider.Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "com.personal.app/.Listener")
        val status = result(send("nls", client = "other"))
        assertFalse(status.has("enabled_listeners")); assertFalse(status.toString().contains("com.personal.app"))
        assertFalse(status.has("packages")); assertFalse(status.has("client_id"))
        assertNull(owner.command(CLIENT, "device.unrelated", JSONObject()) { fail("must not run") })
    }

    private class FakeAccess : HandsNotificationAccess {
        var live: LiveHandsNotification? = null
        var mutations = 0
        var onActionRead: () -> Unit = {}
        override fun current(key: String) = live
        override fun actions(key: String) = Result.success(listOf(NotifActions.ActionRow(0, "Synthetic action", false))).also { onActionRead() }
        override fun fire(key: String, index: Int) = Result.success("Synthetic action").also { mutations++ }
        override fun reply(key: String, text: String) = Result.success("Synthetic reply").also { mutations++ }
        override fun dismiss(key: String) = Result.success(Unit).also { mutations++ }
        override fun snooze(key: String, duration: Long) = Result.success(duration).also { mutations++ }
    }
    companion object {
        private const val CLIENT = "synthetic-client"
        private const val APP = "com.synthetic.app"
        private const val KEY = "abcdef0123456789abcdef0123456789"
    }
}
