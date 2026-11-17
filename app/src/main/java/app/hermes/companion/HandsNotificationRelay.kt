package app.hermes.companion

import android.content.Context
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.NotificationOutboxStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.device.DevicePrivacy
import app.hermes.companion.domain.NotificationCapturePolicy
import app.hermes.companion.domain.NotificationStreamPolicy
import app.hermes.hands.client.HandsClient
import app.hermes.hands.client.HandsReply
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Companion is only a recipient/transport. Android capture and consent belong to Hands. */
class HandsNotificationRelay internal constructor(
    private val command: suspend (String, JSONObject, String) -> HandsReply,
    private val currentRoute: () -> Route?,
    private val gateway: (String) -> Gateway?,
    private val outbox: Outbox,
    private val paused: () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onStatus: (RelayState) -> Unit,
) {
    constructor(app: Context, hands: HandsClient, clients: HostClientPool, creds: DeviceCredStore,
        sticky: StickyStore, scope: CoroutineScope, onStatus: (RelayState) -> Unit) : this(
        command = { action, args, controller -> hands.command(action, args.toString(), controller) },
        currentRoute = {
            val origin = sticky.notifStreamTargetOrigin()
            val profile = origin?.let(sticky::notifStreamProfileFor)
            val cred = origin?.let(creds::load)
            if (!sticky.notifStreamEnabled || origin.isNullOrBlank() || profile.isNullOrBlank() || cred == null) null
            else Route(origin.trimEnd('/'), profile, cred.deviceId, digest(cred.credential), sticky.notifStreamConsentVersion)
        },
        gateway = { origin -> clients.existing(origin)?.let { client -> object : Gateway {
            override fun supported() = client.deviceLaneOpen() && client.passiveNotificationStreamSupported()
            override fun acknowledged(id: String) = client.consumeNotificationAcknowledgment(id)
            override fun rejection(id: String) = client.consumeNotificationRejection(id)
            override fun send(route: Route, timestamp: Long, notification: String) =
                client.sendDeviceEvent("notification", route.deviceId, route.profile, timestamp, notification)
        } } },
        outbox = NotificationOutboxStore(app).let { store -> object : Outbox {
            override fun read() = store.read()
            override fun write(json: String) = store.write(json)
            override fun clear() = store.clear()
        } },
        paused = { hands.state.value.privacyActive }, scope = scope, now = System::currentTimeMillis, onStatus = onStatus,
    )

    data class RelayState(
        val enabled: Boolean = false, val nlsBound: Boolean = false, val apps: Set<String> = emptySet(),
        val content: String = "metadata", val retentionMinutes: Int = 15, val includeCalls: Boolean = true,
        val message: String = "Notification forwarding is off.", val lastAgeMs: Long? = null,
        val queued: Int = 0, val delivered: Long = 0, val dropped: Long = 0,
        val expired: Long = 0, val overflow: Long = 0,
    )

    internal data class Route(val origin: String, val profile: String, val deviceId: String, val credentialHash: String,
        val localVersion: Long) {
        val controllerId get() = HandsClient.controllerId(origin, profile)
    }
    internal interface Gateway {
        fun supported(): Boolean
        fun acknowledged(id: String): Boolean
        fun rejection(id: String): String?
        fun send(route: Route, timestamp: Long, notification: String): Boolean
    }
    internal interface Outbox { fun read(): String?; fun write(json: String); fun clear() }
    private data class PhoneScope(val clientId: String, val version: Long, val retentionMinutes: Int,
        val apps: Set<String>, val content: String, val includeCalls: Boolean)
    private data class Pending(val id: String, val route: Route, val phone: PhoneScope, val captured: Long,
        val timestamp: Long, val notification: String, var acknowledgePhone: Boolean = false,
        var rejected: Boolean = false, var lastAttempt: Long = 0)

    private val gate = Any()
    private val ticks = Mutex()
    private val epoch = AtomicLong()
    private val pending = LinkedHashMap<String, Pending>()
    private val overflowIds = LinkedHashSet<String>()
    private var activeRoute: Route? = null
    private var activePhone: PhoneScope? = null
    private var restored = false
    private var storageReady = true
    private var job: Job? = null
    private var state = RelayState()
    private var lastReceived: Long? = null

    fun start() = synchronized(gate) {
        if (job?.isActive == true) return@synchronized
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { pollOnce() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { synchronized(gate) { invalidateLocked("Waiting for Hands notification access.") } }
                delay(POLL_MS)
            }
        }
    }

    /** Call before changing the pinned route, credential, policy or permissions. */
    fun invalidateRoute() = synchronized(gate) { invalidateLocked("Notification route changed; pending deliveries were discarded.") }
    fun pause() = synchronized(gate) {
        job?.cancel(); job = null
        invalidateLocked("Notification forwarding is paused.")
    }
    fun close() = pause()

    /** One serialized IO cycle. Also used by synthetic transport/concurrency tests. */
    internal suspend fun pollOnce() = ticks.withLock {
        val ticket = epoch.get()
        val route = synchronized(gate) {
            val next = routeNow()
            if (next == null || isPaused()) { invalidateLocked("Notification forwarding is off or paused."); return@withLock }
            if (activeRoute != null && activeRoute != next) invalidateLocked("Notification route changed; queue discarded.")
            activeRoute = next
            next
        }
        // A route change above deliberately invalidates any older in-flight work.
        val workEpoch = epoch.get()
        if (ticket != workEpoch && !valid(workEpoch, route)) return@withLock
        val status = request("stream", JSONObject(), route)
        if (!valid(workEpoch, route)) return@withLock
        val phone = phoneScope(status)
        if (phone == null || !status.optBoolean("enabled") || !status.optBoolean("nls_bound") || status.optBoolean("paused")) {
            synchronized(gate) { if (valid(workEpoch, route)) invalidateLocked("Approve this Companion client and notification apps in Hands.") }
            return@withLock
        }
        synchronized(gate) {
            if (!valid(workEpoch, route)) return@withLock
            if (activePhone != null && activePhone != phone) {
                clearPendingLocked(); activePhone = phone
            } else activePhone = phone
            if (!restored) restoreLocked(route, phone)
            state = state.copy(enabled = true, nlsBound = true,
                apps = strings(status.optJSONArray("packages")), content = status.optString("content_mode", "metadata"),
                retentionMinutes = status.optInt("retention_minutes", 15).coerceIn(1, 60), includeCalls = status.optBoolean("include_calls", true))
        }
        val listed = request("list", JSONObject().put("limit", 100).put("after_cursor", 0), route)
        if (!valid(workEpoch, route)) return@withLock
        if (listed.optLong("consent_version", -1) != phone.version) {
            synchronized(gate) { invalidateLocked("Hands notification approval changed; queue discarded.") }; return@withLock
        }
        synchronized(gate) {
            if (!valid(workEpoch, route)) return@withLock
            val rows = listed.optJSONArray("events") ?: throw IllegalArgumentException("Missing events")
            for (i in 0 until minOf(rows.length(), 100)) {
                val row = rows.optJSONObject(i) ?: continue
                val entry = decodeEvent(row, route, phone) ?: continue
                val old = pending[entry.id]
                if (old != null) {
                    require(old.notification == entry.notification && old.captured == entry.captured && old.timestamp == entry.timestamp) { "Conflicting notification ID" }
                    continue
                }
                if (pending.size >= CAP) {
                    if (overflowIds.add(entry.id)) state = state.copy(overflow = state.overflow + 1)
                    while (overflowIds.size > CAP) overflowIds.remove(overflowIds.first())
                    // Leave it in Hands rather than ACKing a delivery the gateway never accepted.
                    continue
                }
                pending[entry.id] = entry; lastReceived = now()
            }
            expireLocked()
            if (!persistLocked()) return@withLock
        }
        // Re-read the phone scope immediately before publishing previously captured data.
        val current = request("stream", JSONObject(), route)
        if (!valid(workEpoch, route)) return@withLock
        if (phoneScope(current) != phone || !current.optBoolean("enabled") || !current.optBoolean("nls_bound") || current.optBoolean("paused")) {
            synchronized(gate) { invalidateLocked("Hands notification access changed; queue discarded.") }; return@withLock
        }
        val endpoint = gateway(route.origin)
        synchronized(gate) {
            if (!valid(workEpoch, route) || activePhone != phone) return@withLock
            if (endpoint?.supported() == true) {
                pending.values.forEach { q ->
                    if (!q.acknowledgePhone && endpoint.acknowledged(q.id)) q.acknowledgePhone = true
                    if (!q.acknowledgePhone) {
                        val rejection = endpoint.rejection(q.id)
                        if (rejection in TERMINAL_REJECTIONS) {
                            q.acknowledgePhone = true; q.rejected = true
                        }
                    }
                }
            }
            if (!persistLocked()) return@withLock
        }
        // Persist gateway disposition before retiring anything from Hands.
        val ackIds = synchronized(gate) { pending.values.filter { it.acknowledgePhone }.map { it.id } }
        if (ackIds.isNotEmpty()) {
            if (!valid(workEpoch, route)) return@withLock
            val ack = request("ack", JSONObject().put("delivery_ids", JSONArray(ackIds)), route)
            if (!valid(workEpoch, route)) return@withLock
            if (ack.optLong("consent_version", -1) != phone.version) {
                synchronized(gate) { invalidateLocked("Hands notification approval changed; queue discarded.") }; return@withLock
            }
            synchronized(gate) {
                if (!valid(workEpoch, route) || activePhone != phone) return@withLock
                ackIds.forEach { id -> pending.remove(id)?.let {
                    state = if (it.rejected) state.copy(dropped = state.dropped + 1) else state.copy(delivered = state.delivered + 1)
                } }
                if (!persistLocked()) return@withLock
            }
        }
        val outgoing = synchronized(gate) {
            if (!valid(workEpoch, route) || activePhone != phone || !storageReady) return@withLock
            if (endpoint?.supported() != true) {
                publishLocked("Waiting for a gateway with passive notification ACK support."); return@withLock
            }
            expireLocked()
            if (!persistLocked()) return@withLock
            pending.values.filter { !it.acknowledgePhone &&
                (it.lastAttempt == 0L || now() - it.lastAttempt !in 0 until RETRY_MS) }.map { it.id }
        }
        for (id in outgoing) {
            // A queued batch is not one approval: each publish rechecks the current phone scope.
            val beforeSend = request("stream", JSONObject(), route)
            if (!valid(workEpoch, route)) return@withLock
            if (phoneScope(beforeSend) != phone || !beforeSend.optBoolean("enabled") || !beforeSend.optBoolean("nls_bound") || beforeSend.optBoolean("paused")) {
                synchronized(gate) { invalidateLocked("Hands notification access changed; queue discarded.") }
                return@withLock
            }
            val sent = synchronized(gate) {
                if (!valid(workEpoch, route) || activePhone != phone || endpoint?.supported() != true) return@synchronized false
                val q = pending[id] ?: return@synchronized true
                if (!fresh(q.captured, phone)) return@synchronized true
                q.lastAttempt = now()
                endpoint.send(route, q.timestamp, q.notification)
            }
            if (!sent) break
        }
        synchronized(gate) {
            if (valid(workEpoch, route)) publishLocked("${state.delivered} acknowledged · ${pending.size} queued · ${state.dropped} discarded")
        }
    }

    private suspend fun request(action: String, args: JSONObject, route: Route): JSONObject {
        val reply = command("device.notifications.$action", args, route.controllerId)
        check(reply.ok) { "Hands notification request refused" }
        return JSONObject(reply.resultJson)
    }
    private fun phoneScope(status: JSONObject): PhoneScope? {
        val id = status.optString("client_id")
        val version = status.optLong("consent_version", -1)
        val retention = status.optInt("retention_minutes", -1)
        val apps = strings(status.optJSONArray("packages"))
        val content = status.optString("content_mode", status.optString("content"))
        return if (id.isNotBlank() && id.length <= 128 && version >= 0 && retention in 1..60 && apps.size <= 100 &&
            apps.all { it.length <= 255 && it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) } && content in CONTENT)
            PhoneScope(id, version, retention, apps, content, status.optBoolean("include_calls", true)) else null
    }
    private fun decodeEvent(row: JSONObject, route: Route, phone: PhoneScope): Pending? = runCatching {
        val id = row.getString("delivery_id")
        require(UUID.fromString(id).toString() == id)
        require(row.getLong("consent_version") == phone.version)
        val captured = row.getLong("captured_at_ms")
        require(fresh(captured, phone))
        val original = row.getJSONObject("notification")
        require(original.opt("passive") == true && original.getString("delivery_id") == id)
        val pkg = original.getString("package")
        val category = original.optString("category")
        require(NotificationCapturePolicy.selected(pkg, phone.apps, category, phone.includeCalls))
        require(NotificationStreamPolicy.shouldForward(pkg, OWN_PACKAGES))
        val sourceMode = original.getString("content_mode")
        require(sourceMode in CONTENT)
        val mode = CONTENT[minOf(CONTENT.indexOf(sourceMode), CONTENT.indexOf(phone.content))]
        val content = NotificationCapturePolicy.content(DevicePrivacy.sanitize(original.optString("title")), DevicePrivacy.sanitize(original.optString("text")), mode)
        val key = original.getString("key")
        require(key.matches(Regex("[a-fA-F0-9]{32,64}")))
        val notification = JSONObject().put("delivery_id", id).put("key", key).put("package", pkg)
            .put("title", content.first).put("text", content.second).put("category", category.take(80))
            .put("ongoing", original.optBoolean("ongoing")).put("clearable", original.optBoolean("clearable"))
            .put("passive", true).put("content_mode", mode)
        Pending(id, route, phone, captured, row.optLong("ts_ms", captured), notification.toString())
    }.getOrNull()

    private fun restoreLocked(route: Route, phone: PhoneScope) {
        restored = true
        try {
            val raw = outbox.read() ?: return
            val root = JSONObject(raw) // Old Companion arrays deliberately cannot restore.
            require(root.getInt("version") == 2)
            require(root.getString("route") == routeJson(route).toString())
            require(root.getString("hands_client") == phone.clientId && root.getLong("hands_version") == phone.version && root.getString("hands_policy") == phonePolicyJson(phone).toString())
            val rows = root.getJSONArray("rows")
            require(rows.length() <= CAP)
            for (i in 0 until rows.length()) {
                val q = rows.getJSONObject(i)
                val row = JSONObject().put("delivery_id", q.getString("delivery_id")).put("consent_version", phone.version)
                    .put("captured_at_ms", q.getLong("captured_at_ms")).put("ts_ms", q.getLong("ts_ms"))
                    .put("notification", q.getJSONObject("notification"))
                val entry = decodeEvent(row, route, phone) ?: continue
                entry.acknowledgePhone = q.optBoolean("ack_phone")
                entry.rejected = q.optBoolean("rejected")
                require(entry.id !in pending)
                pending[entry.id] = entry
            }
        } catch (_: Exception) { pending.clear(); storageReady = runCatching { outbox.clear() }.isSuccess }
    }
    private fun routeJson(route: Route) = JSONObject().put("origin", route.origin).put("profile", route.profile)
        .put("device", route.deviceId).put("credential_hash", route.credentialHash).put("local_version", route.localVersion)
    private fun phonePolicyJson(phone: PhoneScope) = JSONObject().put("apps", JSONArray(phone.apps.sorted()))
        .put("content", phone.content).put("retention", phone.retentionMinutes).put("calls", phone.includeCalls)
    private fun persistLocked(): Boolean {
        storageReady = runCatching {
            if (pending.isEmpty()) outbox.clear() else {
                val rows = JSONArray()
                pending.values.forEach { q -> rows.put(JSONObject().put("delivery_id", q.id)
                    .put("captured_at_ms", q.captured).put("ts_ms", q.timestamp).put("notification", JSONObject(q.notification))
                    .put("ack_phone", q.acknowledgePhone).put("rejected", q.rejected)) }
                val first = pending.values.first()
                check(pending.values.all { it.route == first.route && it.phone == first.phone })
                outbox.write(JSONObject().put("version", 2).put("route", routeJson(first.route).toString())
                    .put("hands_client", first.phone.clientId).put("hands_version", first.phone.version)
                    .put("hands_policy", phonePolicyJson(first.phone).toString()).put("rows", rows).toString())
            }
        }.isSuccess
        if (!storageReady) publishLocked("Encrypted notification queue unavailable; forwarding paused.")
        return storageReady
    }
    private fun expireLocked() {
        val expiredIds = pending.values.filter { !fresh(it.captured, it.phone) }.map { it.id }
        expiredIds.forEach { pending.remove(it) }
        if (expiredIds.isNotEmpty()) state = state.copy(expired = state.expired + expiredIds.size, dropped = state.dropped + expiredIds.size)
    }
    private fun clearPendingLocked() {
        state = state.copy(dropped = state.dropped + pending.size)
        pending.clear(); overflowIds.clear(); restored = true
        storageReady = runCatching { outbox.clear() }.isSuccess
    }
    private fun invalidateLocked(message: String) {
        epoch.incrementAndGet()
        clearPendingLocked(); activeRoute = null; activePhone = null
        lastReceived = null
        state = state.copy(enabled = false, nlsBound = false)
        publishLocked(message)
    }
    private fun valid(ticket: Long, route: Route) = epoch.get() == ticket && !isPaused() && routeNow() == route
    private fun routeNow() = runCatching { currentRoute() }.getOrNull()?.takeIf {
        it.origin.isNotBlank() && it.profile.isNotBlank() && it.deviceId.isNotBlank() && it.credentialHash.isNotBlank() &&
            it.origin.length <= 2048 && it.profile.length <= 128 && it.deviceId.length <= 256
    }
    private fun isPaused() = runCatching { paused() }.getOrDefault(true)
    private fun fresh(captured: Long, phone: PhoneScope) = now() >= captured && now() - captured <= phone.retentionMinutes.coerceIn(1, 60) * 60_000L
    private fun publishLocked(message: String) {
        state = state.copy(queued = pending.size, message = message, lastAgeMs = lastReceived?.let { (now() - it).coerceAtLeast(0) })
        runCatching { onStatus(state) }
    }

    companion object {
        private const val CAP = 128
        private const val POLL_MS = 2_000L
        private const val RETRY_MS = 30_000L
        private val CONTENT = listOf("metadata", "title", "full")
        private val TERMINAL_REJECTIONS = setOf("policy_blocked", "invalid_event", "delivery_id_conflict")
        private val OWN_PACKAGES = setOf("app.hermes.hands", "app.hermes.companion", "app.hermes.companion.preview")
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun strings(array: JSONArray?) = if (array == null) emptySet() else (0 until array.length()).mapNotNull { array.opt(it) as? String }.toSet()
    }
}
