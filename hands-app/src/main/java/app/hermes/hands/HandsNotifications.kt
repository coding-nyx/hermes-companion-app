package app.hermes.hands

import android.content.Context
import app.hermes.companion.device.DirectToolbox
import app.hermes.companion.device.DevicePrivacy
import app.hermes.companion.device.NotifActions
import app.hermes.companion.device.NotifStreamBus
import app.hermes.companion.device.NotifStreamEvent
import app.hermes.companion.domain.NotificationCapturePolicy
import app.hermes.companion.domain.NotificationStreamPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HandsNotificationState(
    val enabled: Boolean = false,
    val clientId: String? = null,
    val packages: Set<String> = emptySet(),
    val content: String = "metadata",
    val retentionMinutes: Int = 15,
    val includeCalls: Boolean = true,
    val listenerBound: Boolean = false,
    val paused: Boolean = false,
    val queued: Int = 0,
    val message: String = "Notification sharing is off.",
    val consentVersion: Long = 0,
)

/** Phone-owned, memory-only deliveries. No agent, gateway, profile or automatic wake policy. */
class HandsNotifications internal constructor(
    private val context: Context,
    private val protectedPackages: () -> Set<String>,
    private val paused: () -> Boolean,
    private val now: () -> Long,
    private val access: HandsNotificationAccess,
    private val collectEvents: Boolean,
) {
    constructor(context: Context, protected: () -> Set<String>, paused: () -> Boolean) :
        this(context.applicationContext, protected, paused, System::currentTimeMillis, AndroidNotificationAccess(context.applicationContext), true)

    private val prefs = context.getSharedPreferences("hands.notifications", Context.MODE_PRIVATE)
    private val monitor = Any()
    private val _state = MutableStateFlow(HandsNotificationState(
        // A process restart never silently resumes capture, even if the old process died armed.
        clientId = prefs.getString("client", null),
        packages = prefs.getStringSet("packages", emptySet()).orEmpty().filter { validPackage(it) }.take(MAX_PACKAGES).toSet(),
        content = prefs.getString("content", "metadata").takeIf { it in CONTENT } ?: "metadata",
        retentionMinutes = prefs.getInt("retention", 15).coerceIn(1, 60),
        includeCalls = prefs.getBoolean("calls", true),
        consentVersion = prefs.getLong("version", 0).coerceAtLeast(0),
    ))
    val state: StateFlow<HandsNotificationState> = _state
    private data class Delivery(val id: String, val cursor: Long, val event: NotifStreamEvent, val version: Long)
    private val ring = ArrayDeque<Delivery>()
    private val acknowledgements = LinkedHashMap<String, Long>()
    private var cursor = 0L
    private var protections = emptySet<String>()
    private var lastPause = false
    private var scope: CoroutineScope? = null
    private var collector: Job? = null

    init { synchronized(monitor) { advanceScope(); refreshLocked() } }

    /** Only the phone UI may call this. The caller supplies an existing ClientTokens ID. */
    fun configureLocal(enabled: Boolean, clientId: String? = state.value.clientId,
        packages: Set<String> = state.value.packages, content: String = state.value.content,
        retentionMinutes: Int = state.value.retentionMinutes, includeCalls: Boolean = state.value.includeCalls) = synchronized(monitor) {
        require(packages.size <= MAX_PACKAGES && packages.all(::validPackage)) { "bad_args" }
        require(content in CONTENT && retentionMinutes in 1..60) { "bad_args" }
        require(clientId == null || clientId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "bad_args" }
        require(!enabled || (!clientId.isNullOrBlank() && packages.isNotEmpty())) { "local_consent_required" }
        _state.value = state.value.copy(enabled = enabled, clientId = clientId, packages = packages.toSet(),
            content = content, retentionMinutes = retentionMinutes, includeCalls = includeCalls)
        saveChoices()
        advanceScope()
        refreshLocked(forceBus = true)
    }

    fun refresh() = synchronized(monitor) { refreshLocked() }

    fun onClientRevoked(id: String) = synchronized(monitor) {
        if (state.value.clientId == id) {
            closeLocked()
            _state.value = state.value.copy(clientId = null)
            saveChoices()
        }
    }

    /** Local Stop/revocation clears the subscription and every queued delivery. */
    fun close() = synchronized(monitor) { closeLocked() }

    fun isPassiveAllowed(clientId: String, action: String, args: JSONObject): Boolean = synchronized(monitor) {
        refreshLocked()
        !isPaused() && (action in BASIC_STATUS || (action == "device.notifications.stream" && state.value.clientId == clientId) || (action in PASSIVE_ACTIONS && eligibleRecipient(clientId) &&
            (action != "device.notifications.actions" || args.optString("key").isNotBlank())))
    }

    /** Null means this owner does not implement the action. The runtime supplies its lease guard. */
    fun command(clientId: String, action: String, args: JSONObject, requirePermit: () -> Unit): JSONObject? {
        if (action !in ACTIONS) return null
        return synchronized(monitor) {
            try {
                refreshLocked()
                if (isPaused()) throw IllegalStateException("notifications_paused")
                val startingProtections = protectedNow()
                if (action in BASIC_STATUS) {
                    val status = basicStatus()
                    check(!isPaused()) { "notifications_paused" }
                    return@synchronized ok(status)
                }
                if (action in NLS_ENABLE) {
                    requirePermit()
                    check(!isPaused()) { "notifications_paused" }
                    val result = JSONObject(DirectToolbox.nlsEnable(context))
                    check(!isPaused()) { "notifications_paused" }
                    return@synchronized ok(result)
                }
                if (action == "device.notifications.stream" && state.value.clientId == clientId) {
                    val status = statusJson()
                    check(!isPaused() && startingProtections == protectedNow()) { "notification_scope_changed" }
                    return@synchronized ok(status)
                }
                requireRecipient(clientId)
                val result = when (action) {
                    "device.notifications.stream" -> statusJson()
                    "device.notifications.list" -> poll(args)
                    "device.notifications.ack" -> acknowledge(args)
                    "device.notifications.stream.set", "device.notifications.stream.on", "device.notifications.stream.off" -> {
                        requirePermit()
                        narrow(action, args)
                    }
                    "device.notifications.clear" -> {
                        requirePermit(); advanceScope(); refreshLocked(forceBus = true)
                        JSONObject().put("cleared", true).put("consent_version", state.value.consentVersion)
                    }
                    else -> notificationAction(action, args, requirePermit)
                }
                // The runtime also rechecks its pairing/privacy epoch before publishing passive reads.
                check(!isPaused()) { "notifications_paused" }
                if (startingProtections != protectedNow()) {
                    refreshLocked()
                    throw IllegalStateException("notification_scope_changed")
                }
                ok(result)
            } catch (failure: Exception) {
                val code = when (failure) {
                    is SecurityException -> "permission_denied"
                    is org.json.JSONException -> "bad_args"
                    else -> failure.message?.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "notification_failed"
                }
                error(code, when (code) {
                    "local_consent_required" -> "Choose the recipient and notification scope on the phone."
                    "recipient_mismatch" -> "Notification sharing is not approved for this client."
                    "notifications_paused" -> "Notification access is paused for local privacy or approval."
                    "bad_args" -> "Invalid notification arguments."
                    else -> "Notification request refused or unavailable."
                })
            }
        }
    }

    private fun basicStatus(): JSONObject {
        val nls = DirectToolbox.nlsStatus(context)
        return JSONObject().put("permission_granted", nls["nls_bound"] == true)
            .put("listener_component", nls["listener_component"])
            .put("nls_bound", NotifStreamBus.listenerBound).put("available", NotifStreamBus.listenerBound)
    }

    private fun statusJson() = JSONObject().put("enabled", state.value.enabled)
        .put("client_id", state.value.clientId ?: JSONObject.NULL).put("packages", JSONArray(state.value.packages.sorted()))
        .put("content", state.value.content).put("content_mode", state.value.content)
        .put("retention_minutes", state.value.retentionMinutes).put("include_calls", state.value.includeCalls)
        .put("nls_bound", NotifStreamBus.listenerBound).put("paused", isPaused())
        .put("queued", ring.count { it.id !in acknowledgements }).put("consent_version", state.value.consentVersion)
        .put("cursor", cursor).put("content_trust", "untrusted")

    private fun poll(args: JSONObject): JSONObject {
        requireOnly(args, setOf("after_cursor", "limit", "since_ms", "package"))
        val after = integer(args, "after_cursor", 0, 0, Long.MAX_VALUE)
        val limit = integer(args, "limit", 50, 1, 100).toInt()
        val since = integer(args, "since_ms", 0, 0, Long.MAX_VALUE)
        val pkg = optionalString(args, "package")
        require(pkg == null || validPackage(pkg)) { "bad_args" }
        val eligible = ring.filter { it.cursor > after && it.id !in acknowledgements && it.event.capturedAtMs >= since && (pkg == null || it.event.packageName == pkg) }
        val selected = eligible.take(limit)
        val events = JSONArray()
        selected.forEach { delivery ->
            val e = delivery.event
            val notification = JSONObject().put("key", e.key).put("package", e.packageName)
                .put("title", e.title).put("text", e.text).put("category", e.category)
                .put("ongoing", e.ongoing).put("clearable", e.clearable).put("passive", true)
                .put("delivery_id", delivery.id).put("content_mode", e.contentMode).put("content_trust", "untrusted")
                .put("post_time_ms", e.postTimeMs)
            events.put(JSONObject().put("delivery_id", delivery.id).put("cursor", delivery.cursor)
                .put("consent_version", delivery.version).put("ts_ms", e.capturedAtMs)
                .put("captured_at_ms", e.capturedAtMs).put("notification", notification))
        }
        return JSONObject().put("events", events).put("cursor", cursor)
            .put("next_cursor", selected.lastOrNull()?.cursor ?: after).put("has_more", eligible.size > selected.size)
            .put("consent_version", state.value.consentVersion).put("content_trust", "untrusted")
    }

    private fun acknowledge(args: JSONObject): JSONObject {
        requireOnly(args, setOf("delivery_ids"))
        val ids = args.optJSONArray("delivery_ids") ?: throw IllegalArgumentException("bad_args")
        require(ids.length() in 1..RING_CAP) { "bad_args" }
        val values = (0 until ids.length()).map { i ->
            val id = ids.opt(i) as? String ?: throw IllegalArgumentException("bad_args")
            require(runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)) { "bad_args" }
            id.lowercase()
        }.toSet()
        val known = ring.mapTo(HashSet()) { it.id }
        var count = 0
        values.forEach { id ->
            if (id in known || id in acknowledgements) {
                if (id !in acknowledgements) acknowledgements[id] = now()
                count++
            }
        }
        while (acknowledgements.size > ACK_CAP) acknowledgements.remove(acknowledgements.keys.first())
        publish()
        return JSONObject().put("acknowledged", count).put("consent_version", state.value.consentVersion)
    }

    private fun narrow(action: String, args: JSONObject): JSONObject {
        requireOnly(args, setOf("enabled", "packages", "content", "retention_minutes", "include_calls"))
        if (args.has("enabled")) require(args.opt("enabled") is Boolean) { "bad_args" }
        // Enabling is always local, including an idempotent remote 'on'.
        require(action != "device.notifications.stream.on" && args.opt("enabled") != true) { "local_consent_required" }
        if (action == "device.notifications.stream.off" || args.opt("enabled") == false) {
            closeLocked()
            return statusJson()
        }
        require(args.length() > 0) { "bad_args" }
        val st = state.value
        val packages = if (args.has("packages")) {
            val array = args.optJSONArray("packages") ?: throw IllegalArgumentException("bad_args")
            require(array.length() <= MAX_PACKAGES) { "bad_args" }
            (0 until array.length()).map { (array.opt(it) as? String)?.takeIf(::validPackage) ?: throw IllegalArgumentException("bad_args") }.toSet()
        } else st.packages
        val content = optionalString(args, "content") ?: st.content
        val retention = integer(args, "retention_minutes", st.retentionMinutes.toLong(), 1, 60).toInt()
        val calls = if (args.has("include_calls")) args.opt("include_calls") as? Boolean ?: throw IllegalArgumentException("bad_args") else st.includeCalls
        require(packages.all { it in st.packages } && content in CONTENT && CONTENT.indexOf(content) <= CONTENT.indexOf(st.content) &&
            retention <= st.retentionMinutes && (!calls || st.includeCalls)) { "local_consent_required" }
        _state.value = st.copy(packages = packages, content = content, retentionMinutes = retention, includeCalls = calls)
        saveChoices(); advanceScope(); refreshLocked(forceBus = true)
        return statusJson()
    }

    private fun notificationAction(action: String, args: JSONObject, requirePermit: () -> Unit): JSONObject {
        val fields = when (action) {
            "device.notifications.action" -> setOf("key", "index")
            "device.notifications.reply" -> setOf("key", "text")
            "device.notifications.snooze" -> setOf("key", "duration_ms")
            else -> setOf("key")
        }
        requireOnly(args, fields)
        val key = optionalString(args, "key") ?: throw IllegalArgumentException("bad_args")
        require(key.matches(Regex("[A-Fa-f0-9]{32,64}"))) { "bad_args" }
        val delivery = ring.lastOrNull { it.event.key == key } ?: throw IllegalStateException("unknown_notification")
        fun revalidate() {
            check(!isPaused()) { "notifications_paused" }
            check(eligibleEvent(delivery.event) && delivery.version == state.value.consentVersion) { "unknown_notification" }
            val live = access.current(key) ?: throw IllegalStateException("notification_gone")
            check(live.packageName == delivery.event.packageName && allowed(live.packageName, live.category, live.channelId, live.ongoing)) { "protected_package" }
        }
        revalidate()
        if (action == "device.notifications.actions") {
            val rows = actionResult(access.actions(key))
            revalidate()
            return JSONObject().put("actions", JSONArray().also { out -> rows.forEach { row ->
                val title = if (state.value.content == "metadata") "" else NotificationCapturePolicy.content(DevicePrivacy.sanitize(row.title), "", "title").first
                out.put(JSONObject().put("index", row.index).put("title", title).put("accepts_text", row.acceptsText))
            } }).put("content_trust", "untrusted")
        }
        requirePermit(); revalidate()
        return when (action) {
            "device.notifications.action" -> {
                val index = integer(args, "index", -1, 0, 100).toInt()
                val title = actionResult(access.fire(key, index))
                JSONObject().put("fired", cappedActionTitle(title)).put("index", index)
            }
            "device.notifications.reply" -> {
                val text = optionalString(args, "text") ?: throw IllegalArgumentException("bad_args")
                require(text.isNotBlank() && text.length <= 10_000) { "bad_args" }
                val title = actionResult(access.reply(key, text))
                JSONObject().put("replied", true).put("action", cappedActionTitle(title)).put("length", text.length)
            }
            "device.notifications.dismiss" -> {
                actionResult(access.dismiss(key)); JSONObject().put("dismissed", true)
            }
            "device.notifications.snooze" -> {
                require(args.has("duration_ms")) { "bad_args" }
                // Preserve NotifActions' bounded platform duration, including legacy clamping.
                val duration = integer(args, "duration_ms", 0, Long.MIN_VALUE, Long.MAX_VALUE).coerceIn(1_000, 86_400_000)
                JSONObject().put("snoozed_ms", actionResult(access.snooze(key, duration)))
            }
            else -> throw IllegalArgumentException("bad_args")
        }
    }

    private fun cappedActionTitle(title: String) = if (state.value.content == "metadata") "" else NotificationCapturePolicy.content(DevicePrivacy.sanitize(title), "", "title").first
    private fun <T> actionResult(result: Result<T>): T = result.getOrElse { throw IllegalStateException(NotifActions.errorCode(it)) }

    /** Collector entry point; internal visibility permits synthetic-only regression tests. */
    internal fun accept(event: NotifStreamEvent) = synchronized(monitor) {
        refreshLocked()
        if (!eligibleEvent(event) || event.consentVersion != state.value.consentVersion) return@synchronized
        val mode = CONTENT[minOf(CONTENT.indexOf(state.value.content), CONTENT.indexOf(event.contentMode).coerceAtLeast(0))]
        val content = NotificationCapturePolicy.content(DevicePrivacy.sanitize(event.title), DevicePrivacy.sanitize(event.text), mode)
        val safe = event.copy(title = content.first, text = content.second, contentMode = mode)
        if (ring.any { it.event == safe && it.version == state.value.consentVersion }) return@synchronized
        ring.addLast(Delivery(UUID.randomUUID().toString(), ++cursor, safe, state.value.consentVersion))
        while (ring.size > RING_CAP) ring.removeFirst()
        publish()
    }

    private fun eligibleRecipient(id: String) = state.value.enabled && state.value.clientId == id && !isPaused()
    private fun requireRecipient(id: String) {
        check(state.value.enabled) { "local_consent_required" }
        check(state.value.clientId == id) { "recipient_mismatch" }
        check(!isPaused()) { "notifications_paused" }
    }
    private fun eligibleEvent(e: NotifStreamEvent) = state.value.enabled && !isPaused() &&
        e.key.matches(Regex("[A-Fa-f0-9]{32,64}")) && fresh(e.capturedAtMs) && allowed(e.packageName, e.category, null, e.ongoing)
    private fun allowed(pkg: String, category: String, channel: String?, ongoing: Boolean) =
        NotificationCapturePolicy.selected(pkg, state.value.packages, category, state.value.includeCalls) &&
            NotificationStreamPolicy.shouldForward(pkg, protectedNow(), context.packageName, channel, ongoing)
    private fun fresh(at: Long) = now() >= at && now() - at <= state.value.retentionMinutes * 60_000L
    private fun isPaused() = runCatching { paused() }.getOrDefault(true)
    private fun protectedNow() = runCatching { protectedPackages() + SELF_PACKAGES + context.packageName }.getOrElse { setOf("*") }

    private fun refreshLocked(forceBus: Boolean = false) {
        val nextProtected = protectedNow()
        val nextPause = isPaused()
        val changed = nextProtected != protections || nextPause != lastPause
        if (changed) advanceScope()
        protections = nextProtected; lastPause = nextPause
        ring.removeAll { !fresh(it.event.capturedAtMs) || !allowed(it.event.packageName, it.event.category, null, it.event.ongoing) }
        acknowledgements.entries.removeAll { now() < it.value || now() - it.value > ACK_TTL_MS }
        if (forceBus || changed || NotifStreamBus.streamEnabled != (state.value.enabled && !nextPause)) {
            NotifStreamBus.configure(state.value.enabled && !nextPause, protections, context.packageName,
                state.value.packages, state.value.content, state.value.includeCalls, state.value.consentVersion)
        }
        if (state.value.enabled && collectEvents && collector?.isActive != true) {
            val next = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = next
            collector = next.launch { NotifStreamBus.events.collect(::accept) }
        } else if (!state.value.enabled) {
            stopCollector()
        }
        publish()
    }

    private fun publish() {
        _state.value = state.value.copy(listenerBound = NotifStreamBus.listenerBound, paused = isPaused(),
            queued = ring.count { it.id !in acknowledgements }, message = when {
                !state.value.enabled -> "Notification sharing is off."
                isPaused() -> "Sharing is paused for local privacy or approval."
                !NotifStreamBus.listenerBound -> "Allow notification access in Android settings."
                else -> "Selected notifications are shared only with the approved client."
            })
    }
    private fun advanceScope() {
        ring.clear(); acknowledgements.clear()
        val version = runCatching { Math.addExact(state.value.consentVersion, 1) }.getOrNull()
        if (version == null || !prefs.edit().putLong("version", version).commit()) {
            // Never expose an old generation under a newly selected recipient if storage fails.
            _state.value = state.value.copy(enabled = false, queued = 0)
            stopCollector()
            NotifStreamBus.configure(false, protectedNow(), context.packageName, version = state.value.consentVersion)
            throw IllegalStateException("notification_failed")
        }
        _state.value = state.value.copy(consentVersion = version, queued = 0)
    }
    private fun saveChoices() {
        prefs.edit().putString("client", state.value.clientId).putStringSet("packages", state.value.packages)
            .putString("content", state.value.content).putInt("retention", state.value.retentionMinutes)
            .putBoolean("calls", state.value.includeCalls).apply()
    }
    private fun closeLocked() {
        _state.value = state.value.copy(enabled = false)
        advanceScope()
        stopCollector()
        refreshLocked(forceBus = true)
    }
    private fun stopCollector() { collector?.cancel(); collector = null; scope?.cancel(); scope = null }

    companion object {
        const val MAX_PACKAGES = 100
        private const val RING_CAP = 256
        private const val ACK_CAP = 1024
        private const val ACK_TTL_MS = 65 * 60_000L
        private val CONTENT = listOf("metadata", "title", "full")
        private val SELF_PACKAGES = setOf("app.hermes.hands", "app.hermes.companion", "app.hermes.companion.preview")
        private val BASIC_STATUS = setOf("device.notifications.nls", "device.notifications.nls.status", "device.notifications.status")
        private val NLS_ENABLE = setOf("device.notifications.nls.enable", "device.notifications.enable")
        private val PASSIVE_ACTIONS = setOf("device.notifications.stream", "device.notifications.list", "device.notifications.ack", "device.notifications.actions")
        val META_ACTIONS: Set<String> = BASIC_STATUS + PASSIVE_ACTIONS
        val ACTIONS: Set<String> = META_ACTIONS + NLS_ENABLE + setOf("device.notifications.stream.set", "device.notifications.stream.on",
            "device.notifications.stream.off", "device.notifications.clear", "device.notifications.action", "device.notifications.reply",
            "device.notifications.dismiss", "device.notifications.snooze")
        private fun validPackage(value: String) = value.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
        private fun requireOnly(args: JSONObject, allowed: Set<String>) { require(args.keys().asSequence().all { it in allowed }) { "bad_args" } }
        private fun optionalString(args: JSONObject, name: String): String? = if (!args.has(name)) null else args.opt(name) as? String ?: throw IllegalArgumentException("bad_args")
        private fun integer(args: JSONObject, name: String, fallback: Long, min: Long, max: Long): Long {
            val value = if (!args.has(name)) fallback else {
                val raw = args.opt(name)
                require(raw is Byte || raw is Short || raw is Int || raw is Long) { "bad_args" }
                (raw as Number).toLong()
            }
            require(value in min..max) { "bad_args" }; return value
        }
    }
}

internal data class LiveHandsNotification(val packageName: String, val category: String = "", val channelId: String? = null, val ongoing: Boolean = false)
internal interface HandsNotificationAccess {
    fun current(key: String): LiveHandsNotification?
    fun actions(key: String): Result<List<NotifActions.ActionRow>>
    fun fire(key: String, index: Int): Result<String>
    fun reply(key: String, text: String): Result<String>
    fun dismiss(key: String): Result<Unit>
    fun snooze(key: String, duration: Long): Result<Long>
}
private class AndroidNotificationAccess(private val context: Context) : HandsNotificationAccess {
    override fun current(key: String): LiveHandsNotification? {
        val real = NotifStreamBus.realKey(key) ?: return null
        val listener = NotifStreamBus.listener ?: return null
        val sbn = listener.getActiveNotifications(arrayOf(real))?.firstOrNull() ?: return null
        val n = sbn.notification ?: return null
        return LiveHandsNotification(sbn.packageName, n.category.orEmpty(), n.channelId, n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }
    override fun actions(key: String) = NotifActions.listActions(key)
    override fun fire(key: String, index: Int) = NotifActions.fireAction(context, key, index)
    override fun reply(key: String, text: String) = NotifActions.reply(context, key, text)
    override fun dismiss(key: String) = NotifActions.dismiss(key)
    override fun snooze(key: String, duration: Long) = NotifActions.snooze(key, duration)
}
