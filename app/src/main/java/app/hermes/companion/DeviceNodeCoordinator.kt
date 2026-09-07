package app.hermes.companion

import android.os.Build

import android.content.Intent
import android.util.Base64
import androidx.core.content.ContextCompat
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.device.HandsBridge
import app.hermes.companion.device.HandsService
import app.hermes.companion.device.NotifStreamBus
import app.hermes.companion.device.NotifStreamEvent
import app.hermes.companion.device.StopStreamReceiver
import app.hermes.companion.domain.CompactTree
import app.hermes.companion.domain.DeviceArming
import app.hermes.companion.domain.DeviceAudit
import app.hermes.companion.domain.DeviceAuditRow
import app.hermes.companion.domain.DeviceGestures
import app.hermes.companion.domain.DeviceLanePolicy
import app.hermes.companion.domain.PairingPolicy
import app.hermes.companion.domain.SwipeSpec
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.DeviceCommand
import app.hermes.companion.model.DeviceCred
import app.hermes.companion.model.DeviceResult
import app.hermes.companion.model.PairingPhase
import app.hermes.companion.model.ScreenSafeArea
import app.hermes.companion.model.SnapshotNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the phone knows about itself as a device node. Mirrored into the UI by the ViewModel. */
data class DeviceNodeState(
    val pairingPhase: PairingPhase = PairingPhase.IDLE,
    val pairingCode: String = "",
    val deviceId: String? = null,
    val deviceLabel: String = "",
    val deviceProfileId: String? = null,
    val laneOpen: Boolean = false,
    val a11yBound: Boolean = false,
    val foregroundApp: String = "",
    val lastAudit: String = "",
    val arm: DeviceArm = DeviceArm.DISARMED,
    val protectedCustom: List<String> = emptyList(),
    val protectedError: String? = null,
    /** Hosts this phone is paired with (normalised origins) and the lanes currently open (A8.5). */
    val pairedHosts: List<String> = emptyList(),
    val openLanes: Set<String> = emptySet(),
    val notifStreamEnabled: Boolean = false,
    val notifStreamOrigin: String? = null,
    val notifStreamProfile: String? = null,
    val nlsBound: Boolean = false,
    val notifLastAgeMs: Long? = null,
)

/**
 * Application-scoped device node (A7.4).
 *
 * Owns arming, the idle auto-disarm timer, the kill-switch foreground service, the device WSS
 * lane, command policy + execution, the audit line and pairing. All of it runs in the app scope,
 * so an armed phone still auto-disarms after [DeviceLanePolicy.IDLE_DISARM_MS] when MainActivity
 * and its ViewModel are long gone. The phone is the source of truth: Hermes can never re-arm.
 */
class DeviceNodeCoordinator(
    private val app: CompanionApp,
    private val clients: HostClientPool,
    private val deviceCreds: DeviceCredStore,
    private val sticky: StickyStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        DeviceNodeState(
            protectedCustom = sticky.protectedPackages.sorted(),
            pairedHosts = deviceCreds.loadAll().map { it.origin },
            notifStreamEnabled = sticky.notifStreamEnabled,
            notifStreamOrigin = sticky.notifStreamTargetOrigin(),
            notifStreamProfile = sticky.notifStreamTargetOrigin()?.let { sticky.notifStreamProfileFor(it) },
            nlsBound = NotifStreamBus.listenerBound,
        ),
    )
    val state: StateFlow<DeviceNodeState> = _state

    /** Pairing failures and the like; the UI surfaces them in its shared error slot. */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    @Volatile
    private var origin: String? = null
    private val lanes = mutableMapOf<String, Job>()
    private fun client(origin: String): DashboardClient = clients.forOrigin(origin)
    private var idleJob: Job? = null
    private var pairJob: Job? = null
    private var lastRefs: Set<String> = emptySet()
    private var lastNodes: List<SnapshotNode> = emptyList()
    private val rateHits = mutableListOf<Long>()
    private var auditRows: List<DeviceAuditRow> = emptyList()
    private val pendingNotifs = ArrayDeque<NotifStreamEvent>()
    private val pendingNotifCap = 32
    private var lastNotifAtMs: Long? = null

    init {
        // Volume chord, notification tap (DisarmReceiver) and HandsService teardown all land here.
        HandsBridge.onDisarm = { disarm() }
        syncNotifBusConfig()
        StopStreamReceiver.onStopStream = { setNotifStreamEnabled(false) }
        scope.launch {
            NotifStreamBus.events.collect { ev ->
                forwardNotif(ev)
            }
        }
        scope.launch {
            while (isActive) {
                delay(2_000)
                val bound = NotifStreamBus.listenerBound
                if (bound != _state.value.nlsBound) {
                    _state.update { it.copy(nlsBound = bound) }
                    pushStreamStatus()
                }
            }
        }
    }

    /** Every paired host keeps its lane, so Hermes on lab can still move the phone while hub is active. */
    fun startAllLanes() {
        deviceCreds.loadAll().forEach { startLane(it.origin) }
    }

    /** Bounce open lanes so register re-sends metadata / custom denylist. */
    private fun refreshLaneRegistrations() {
        val open = _state.value.openLanes.toList()
        val byKey = deviceCreds.loadAll().associateBy { HostClientPool.key(it.origin) }
        for (key in open) {
            val cred = byKey[key] ?: continue
            stopLane(cred.origin)
            startLane(cred.origin)
        }
    }

    // ---- operator lifecycle -------------------------------------------------------------

    /** Operator connected to [origin]: restore pairing from the stored credential, open the lane. */
    fun bind(origin: String) {
        this.origin = origin
        // A pre-A8.5 pairing was stored without an origin; it belongs to the host it was made on.
        if (deviceCreds.load(origin) == null) deviceCreds.adoptLegacy(origin)
        _state.update {
            it.withCred(deviceCreds.load(origin), origin).copy(
                pairedHosts = deviceCreds.loadAll().map { c -> c.origin },
                laneOpen = HostClientPool.key(origin) in it.openLanes,
            )
        }
        startAllLanes()
        if (_state.value.arm != DeviceArm.DISARMED) syncHands(armed = true)
    }

    /** Operator gone with nothing armed: drop every lane. Arm state is untouched. */
    fun unbind() {
        lanes.keys.toList().forEach { stopLane(it) }
        origin = null
    }

    // ---- arming -----------------------------------------------------------------------------

    fun arm() {
        val next = DeviceArming.arm(_state.value.arm, _state.value.a11yBound)
        _state.update { it.copy(arm = next) }
        if (next != DeviceArm.DISARMED) {
            bumpIdle()
            syncHands(armed = true)
        }
    }

    fun disarm() {
        idleJob?.cancel()
        idleJob = null
        _state.update { it.copy(arm = DeviceArming.disarm()) }
        syncHands(armed = false)
    }

    fun setA11yBound(bound: Boolean) {
        val on = bound || CompanionAccessibilityService.bound
        _state.update { st ->
            st.copy(
                a11yBound = on,
                arm = if (!on) DeviceArming.disarm() else st.arm,
                foregroundApp = CompanionAccessibilityService.foregroundApp,
            )
        }
        if (!on) {
            idleJob?.cancel()
            idleJob = null
            syncHands(armed = false)
        }
    }

    private fun bumpIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(DeviceLanePolicy.IDLE_DISARM_MS)
            disarm()
        }
    }

    /**
     * Kill-switch notification follows the arm state, whether or not an Activity is alive, and names
     * the hosts whose lanes are live (falling back to the paired hosts) — A8.5.
     */
    private fun syncHands(armed: Boolean) {
        if (armed) {
            val st = _state.value
            val hosts = (st.openLanes.ifEmpty { st.pairedHosts.toSet() }).map { app.hostName(it) }.filter { it.isNotBlank() }
            runCatching { ContextCompat.startForegroundService(app, HandsService.start(app, hosts)) }
        } else {
            runCatching { app.stopService(Intent(app, HandsService::class.java)) }
        }
    }

    // ---- protected packages -----------------------------------------------------------------

    fun addProtectedPackage(raw: String) {
        val pkg = DeviceLanePolicy.normalizePackage(raw)
        if (pkg == null) {
            _state.update { it.copy(protectedError = "not a package id") }
            return
        }
        if (sticky.protectedPackages.contains(pkg)) {
            _state.update { it.copy(protectedError = "already in blocklist") }
            return
        }
        val next = (sticky.protectedPackages + pkg).toSortedSet()
        sticky.protectedPackages = next
        _state.update { it.copy(protectedCustom = next.toList(), protectedError = null) }
        syncNotifBusConfig()
        refreshLaneRegistrations()
    }

    fun removeProtectedPackage(pkg: String) {
        val next = (sticky.protectedPackages - pkg).toSortedSet()
        sticky.protectedPackages = next
        _state.update { it.copy(protectedCustom = next.toList(), protectedError = null) }
        refreshLaneRegistrations()
    }


    // ---- notification stream (A13.2) --------------------------------------------------------

    fun setNotifStreamEnabled(enabled: Boolean) {
        sticky.notifStreamEnabled = enabled
        syncNotifBusConfig()
        val origin = sticky.notifStreamTargetOrigin()
        _state.update {
            it.copy(
                notifStreamEnabled = enabled,
                notifStreamOrigin = origin,
                notifStreamProfile = origin?.let { o -> sticky.notifStreamProfileFor(o) },
                nlsBound = NotifStreamBus.listenerBound,
            )
        }
        syncStayForStream()
        refreshLaneRegistrations()
        pushStreamStatus()
        StayConnectedService.refresh(app)
    }

    fun setNotifStreamTarget(origin: String?, profileId: String?) {
        val cleanOrigin = origin?.trim()?.takeIf { it.isNotBlank() }
        sticky.notifStreamOrigin = cleanOrigin
        val target = sticky.notifStreamTargetOrigin()
        if (target != null && !profileId.isNullOrBlank()) {
            sticky.setNotifStreamProfile(target, profileId)
        }
        _state.update {
            it.copy(
                notifStreamOrigin = target,
                notifStreamProfile = target?.let { o -> sticky.notifStreamProfileFor(o) },
            )
        }
        syncStayForStream()
        refreshLaneRegistrations()
        pushStreamStatus()
        StayConnectedService.refresh(app)
    }


    private fun syncNotifBusConfig() {
        NotifStreamBus.configure(
            enabled = sticky.notifStreamEnabled,
            protectedPackages = sticky.protectedPackages,
            selfPackageName = app.packageName,
        )
    }

    /** Keep an FGS alive while stream is on even if stay-connected is off. */
    private fun syncStayForStream() {
        val need = sticky.notifStreamEnabled || sticky.stayConnected
        val intent = Intent(app, StayConnectedService::class.java)
        if (need) {
            runCatching { ContextCompat.startForegroundService(app, intent) }
        } else if (!sticky.stayConnected) {
            runCatching { app.stopService(intent) }
        }
    }

    private fun laneCapabilities(): List<String> {
        val base = DeviceLanePolicy.CAPABILITIES.toMutableList()
        if (!sticky.notifStreamEnabled) {
            base.remove("device.notifications")
        } else if ("device.notifications" !in base) {
            base.add("device.notifications")
        }
        return base
    }

    private fun forwardNotif(ev: NotifStreamEvent) {
        if (!sticky.notifStreamEnabled) return
        lastNotifAtMs = System.currentTimeMillis()
        _state.update {
            it.copy(notifLastAgeMs = 0L, nlsBound = NotifStreamBus.listenerBound)
        }
        val target = sticky.notifStreamTargetOrigin() ?: run {
            bufferNotif(ev)
            return
        }
        val cred = deviceCreds.load(target) ?: run {
            bufferNotif(ev)
            return
        }
        val profile = sticky.notifStreamProfileFor(target) ?: cred.profileId
        val client = clients.existing(target)
        if (client == null || !client.deviceLaneOpen()) {
            bufferNotif(ev)
            return
        }
        val notifJson = buildNotifJson(ev)
        val ok = client.sendDeviceEvent(
            event = "notification",
            deviceId = cred.deviceId,
            profile = profile,
            tsMs = ev.postTimeMs,
            notificationJson = notifJson,
        )
        if (!ok) bufferNotif(ev)
        else flushPending(target, cred.deviceId, profile)
    }

    private fun bufferNotif(ev: NotifStreamEvent) {
        while (pendingNotifs.size >= pendingNotifCap) pendingNotifs.removeFirst()
        pendingNotifs.addLast(ev)
    }

    private fun flushPending(origin: String, deviceId: String, profile: String) {
        val client = clients.existing(origin) ?: return
        while (pendingNotifs.isNotEmpty() && client.deviceLaneOpen()) {
            val ev = pendingNotifs.removeFirst()
            val ok = client.sendDeviceEvent(
                event = "notification",
                deviceId = deviceId,
                profile = profile,
                tsMs = ev.postTimeMs,
                notificationJson = buildNotifJson(ev),
            )
            if (!ok) {
                pendingNotifs.addFirst(ev)
                break
            }
        }
    }

    private fun buildNotifJson(ev: NotifStreamEvent): String =
        """{"key":${jsonQuote(ev.key)},"package":${jsonQuote(ev.packageName)},"title":${jsonQuote(ev.title)},"text":${jsonQuote(ev.text)},"category":${jsonQuote(ev.category)},"ongoing":${ev.ongoing},"clearable":${ev.clearable}}"""

    private fun pushStreamStatus() {
        val target = sticky.notifStreamTargetOrigin() ?: return
        val cred = deviceCreds.load(target) ?: return
        val client = clients.existing(target) ?: return
        if (!client.deviceLaneOpen()) return
        val fields =
            ""","notifications_stream":${sticky.notifStreamEnabled},"notifications_listener_bound":${NotifStreamBus.listenerBound}"""
        client.sendDeviceStatus(cred.deviceId, fields)
    }


    /** Friendly label for this phone on the bound host (A6.7). */
    fun renameDeviceLabel(name: String) {
        val origin = origin ?: return
        val id = _state.value.deviceId ?: return
        val clean = name.trim()
        if (clean.isBlank()) {
            _state.update { it.copy(protectedError = "name required") }
            return
        }
        scope.launch {
            runCatching { client(origin).renameDevice(origin, id, clean) }
                .onSuccess {
                    _state.update { it.copy(deviceLabel = clean, protectedError = null) }
                }
                .onFailure { e ->
                    _errors.tryEmit(e.message ?: "rename failed")
                }
        }
    }

    // ---- pairing ------------------------------------------------------------------------------

    fun startPair(profile: String) {
        val origin = origin ?: return
        if (_state.value.pairingPhase == PairingPhase.WAITING) return
        val code = PairingPolicy.issue()
        pairJob?.cancel()
        pairJob = scope.launch {
            val started = System.currentTimeMillis()
            _state.update { it.copy(pairingPhase = PairingPhase.WAITING, pairingCode = code) }
            try {
                var status = client(origin).offerPair(origin, code, profile)
                val auto = runCatching { client(origin).approvePair(origin, code) }.getOrNull()
                if (auto != null && auto.approved) {
                    status = auto
                }
                while (isActive) {
                    if (status.approved) {
                        deviceCreds.save(
                            DeviceCred(
                                deviceId = status.deviceId,
                                profileId = status.profileId.ifBlank { profile },
                                credential = status.credential,
                                origin = origin,
                            ),
                        )
                        _state.update {
                            it.copy(
                                pairingPhase = PairingPhase.PAIRED,
                                pairingCode = "",
                                deviceId = status.deviceId,
                                deviceProfileId = status.profileId.ifBlank { profile },
                                pairedHosts = deviceCreds.loadAll().map { c -> c.origin },
                            )
                        }
                        startLane(origin)
                        return@launch
                    }
                    if (PairingPolicy.expired(started)) {
                        _state.update { it.copy(pairingPhase = PairingPhase.IDLE, pairingCode = "") }
                        _errors.tryEmit("code expired")
                        return@launch
                    }
                    delay(PairingPolicy.POLL_MS)
                    status = runCatching { client(origin).pollPair(origin, code) }.getOrNull() ?: status
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(pairingPhase = PairingPhase.IDLE, pairingCode = "") }
                _errors.tryEmit(e.message ?: "pair failed")
            }
        }
    }

    fun approvePair() {
        val origin = origin ?: return
        val code = _state.value.pairingCode.ifBlank { return }
        scope.launch {
            try {
                val status = client(origin).approvePair(origin, code)
                if (status.approved) {
                    val profile = _state.value.deviceProfileId ?: "default"
                    deviceCreds.save(
                        DeviceCred(
                            deviceId = status.deviceId,
                            profileId = status.profileId.ifBlank { profile },
                            credential = status.credential,
                            origin = origin,
                        ),
                    )
                    _state.update {
                        it.copy(
                            pairingPhase = PairingPhase.PAIRED,
                            pairingCode = "",
                            deviceId = status.deviceId,
                            deviceProfileId = status.profileId.ifBlank { profile },
                            pairedHosts = deviceCreds.loadAll().map { c -> c.origin },
                        )
                    }
                    startLane(origin)
                }
            } catch (e: Exception) {
                _errors.tryEmit(e.message ?: "approve failed")
            }
        }
    }

    fun cancelPair() {
        pairJob?.cancel()
        pairJob = null
        _state.update { it.copy(pairingPhase = PairingPhase.IDLE, pairingCode = "") }
    }

    fun revokePair() {
        pairJob?.cancel()
        pairJob = null
        val origin = origin
        val id = _state.value.deviceId
        scope.launch {
            if (!origin.isNullOrBlank() && !id.isNullOrBlank()) {
                runCatching { client(origin).revokeDevice(origin, id) }
            }
            // Only this host's pairing goes; other hosts keep their lanes.
            if (!origin.isNullOrBlank()) {
                deviceCreds.clear(origin)
                stopLane(origin)
            }
            val remaining = deviceCreds.loadAll().map { it.origin }
            val stillPaired = remaining.isNotEmpty()
            if (!stillPaired) {
                idleJob?.cancel()
                idleJob = null
            }
            _state.update {
                it.copy(
                    pairingPhase = PairingPhase.IDLE,
                    pairingCode = "",
                    deviceId = null,
                    deviceProfileId = null,
                    laneOpen = false,
                    pairedHosts = remaining,
                    arm = if (stillPaired) it.arm else DeviceArm.DISARMED,
                )
            }
            if (!stillPaired) syncHands(armed = false) else if (_state.value.arm != DeviceArm.DISARMED) syncHands(true)
        }
    }

    fun repair(profile: String? = null) {
        val orig = origin ?: return
        pairJob?.cancel()
        pairJob = null
        stopLane(orig)
        deviceCreds.clear(orig)
        val remaining = deviceCreds.loadAll().map { it.origin }
        _state.update {
            it.copy(
                pairingPhase = PairingPhase.IDLE,
                pairingCode = "",
                deviceId = null,
                deviceProfileId = null,
                laneOpen = false,
                pairedHosts = remaining,
            )
        }
        startPair(profile ?: _state.value.deviceProfileId ?: "default")
    }

    // ---- device lane ------------------------------------------------------------------------

    private fun startLane(laneOrigin: String) {
        val key = HostClientPool.key(laneOrigin)
        if (key.isBlank()) return
        val cred = deviceCreds.load(laneOrigin) ?: return
        if (lanes[key]?.isActive == true) return
        lanes[key] = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    val label = android.provider.Settings.Global.getString(
                        app.contentResolver,
                        "device_name",
                    ).orEmpty().ifBlank {
                        Build.MODEL.orEmpty().ifBlank { "Android" }
                    }
                    client(laneOrigin).openDeviceLane(
                        laneOrigin,
                        cred,
                        deviceName = _state.value.deviceLabel.ifBlank { label },
                        model = Build.MODEL.orEmpty(),
                        manufacturer = Build.MANUFACTURER.orEmpty(),
                        osVersion = "Android ${Build.VERSION.RELEASE}",
                        protectedPackages = sticky.protectedPackages,
                        capabilities = laneCapabilities(),
                    )
                    // After lane is up, push stream status + flush buffered notifs for this target.
                    if (HostClientPool.key(laneOrigin) == HostClientPool.key(sticky.notifStreamTargetOrigin().orEmpty())) {
                        pushStreamStatus()
                        flushPending(laneOrigin, cred.deviceId, sticky.notifStreamProfileFor(laneOrigin) ?: cred.profileId)
                    }
                    if (_state.value.deviceLabel.isBlank()) {
                        _state.update { it.copy(deviceLabel = label) }
                    }
                    markLane(key, open = true)
                    backoff = 1_000L
                    client(laneOrigin).deviceCommands().collect { handleCommand(laneOrigin, it) }
                } catch (c: CancellationException) {
                    throw c
                } catch (d: DashboardException) {
                    markLane(key, open = false)
                    if (d.code == "http_401" || d.code == "device_ticket") {
                        deviceCreds.clear(laneOrigin)
                        if (laneOrigin == origin) {
                            _state.update {
                                it.copy(
                                    deviceId = null,
                                    deviceProfileId = null,
                                    laneOpen = false,
                                    pairingPhase = PairingPhase.IDLE,
                                    pairingCode = "",
                                )
                            }
                            _errors.tryEmit("host rejected device (unauthorized) · please tap PAIR")
                        }
                        client(laneOrigin).closeDeviceLane()
                        return@launch
                    }
                } catch (_: Throwable) {
                    markLane(key, open = false)
                }
                client(laneOrigin).closeDeviceLane()
                markLane(key, open = false)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun markLane(key: String, open: Boolean) {
        _state.update {
            val lanesNow = if (open) it.openLanes + key else it.openLanes - key
            it.copy(
                openLanes = lanesNow,
                laneOpen = origin?.let { o -> HostClientPool.key(o) in lanesNow } ?: false,
            )
        }
        // The kill-switch notification names live lanes; keep it current while armed.
        if (_state.value.arm != DeviceArm.DISARMED) syncHands(armed = true)
        if (sticky.notifStreamEnabled || sticky.stayConnected) StayConnectedService.refresh(app)
    }

    private fun stopLane(laneOrigin: String) {
        val key = HostClientPool.key(laneOrigin)
        lanes.remove(key)?.cancel()
        clients.existing(laneOrigin)?.closeDeviceLane()
        lastRefs = emptySet()
        lastNodes = emptyList()
        markLane(key, open = false)
    }

    private suspend fun handleCommand(laneOrigin: String, command: DeviceCommand) {
        val st = _state.value
        val args = command.argumentsJson
        val ref = jsonField(args, "ref") ?: jsonField(args, "from_ref")
        val target = jsonField(args, "package")
        val fg = CompanionAccessibilityService.foregroundApp
        if (!DeviceGestures.allowRate(System.currentTimeMillis(), rateHits)) {
            noteAudit(command.action, fg, false, "rate_limited")
            client(laneOrigin).replyDevice(DeviceLanePolicy.fail(command.commandId, "rate_limited"))
            return
        }
        val code = DeviceLanePolicy.reject(
            arm = st.arm,
            a11yBound = st.a11yBound,
            action = command.action,
            foregroundApp = fg,
            ref = ref,
            lastRefs = lastRefs,
            targetPackage = target,
            extraProtected = st.protectedCustom.toSet(),
        )
        if (code != null) {
            noteAudit(command.action, target ?: fg, false, code)
            client(laneOrigin).replyDevice(DeviceLanePolicy.fail(command.commandId, code))
            return
        }
        bumpIdle()
        val exec = DeviceArming.beginExec(st.arm)
        if (exec != st.arm) _state.update { it.copy(arm = exec, foregroundApp = fg) }
        val result = withContext(Dispatchers.Default) {
            runCatching { execute(command) }.getOrElse { t ->
                DeviceLanePolicy.fail(command.commandId, "timeout", t.message ?: "command failed")
            }
        }
        noteAudit(command.action, target ?: fg, result.ok, result.errorCode)
        client(laneOrigin).replyDevice(result)
        _state.update {
            it.copy(
                arm = DeviceArming.endExec(it.arm),
                foregroundApp = CompanionAccessibilityService.foregroundApp,
            )
        }
    }

    private suspend fun execute(command: DeviceCommand): DeviceResult {
        val service = CompanionAccessibilityService.instance
        val args = command.argumentsJson
        return when (command.action) {
            "device.noop" -> DeviceLanePolicy.ok(command.commandId)
            "device.snapshot" -> {
                val (app, nodes) = service?.snapshot() ?: ("" to emptyList())
                lastNodes = CompactTree.clip(nodes)
                lastRefs = CompactTree.refsOf(lastNodes)
                val (w, h) = service?.displaySize() ?: (0 to 0)
                val safeArea = service?.safeArea() ?: ScreenSafeArea()
                DeviceLanePolicy.ok(
                    command.commandId,
                    CompactTree.json(lastNodes, app = app, width = w, height = h, safeArea = safeArea),
                )
            }
            "device.click" -> {
                val id = jsonField(args, "ref")
                val query = jsonField(args, "text")
                if (lastNodes.isEmpty() && (!id.isNullOrBlank() || !query.isNullOrBlank())) {
                    val (_, nodes) = service?.snapshot() ?: ("" to emptyList())
                    lastNodes = CompactTree.clip(nodes)
                    lastRefs = CompactTree.refsOf(lastNodes)
                }
                val node = when {
                    !id.isNullOrBlank() -> lastNodes.find { it.ref == id }
                    !query.isNullOrBlank() -> CompactTree.findClickable(lastNodes, query)
                    else -> null
                }
                val xy = jsonXy(args) ?: run {
                    val x = jsonNumber(args, "x")
                    val y = jsonNumber(args, "y")
                    if (x != null && y != null) x to y else null
                }
                val safeArea = service?.safeArea() ?: ScreenSafeArea()
                val (w, h) = service?.displaySize() ?: (0 to 0)
                if (xy != null) {
                    val (x, y) = xy
                    if ((safeArea.top > 0 && y < safeArea.top) ||
                        (safeArea.bottom > 0 && h > 0 && y > (h - safeArea.bottom)) ||
                        (safeArea.left > 0 && x < safeArea.left) ||
                        (safeArea.right > 0 && w > 0 && x > (w - safeArea.right))
                    ) {
                        return DeviceLanePolicy.fail(command.commandId, "safe_area_violation", "click within system safe area")
                    }
                }
                val ok = when {
                    node != null -> service?.click(node) == true
                    xy != null -> service?.clickAt(xy.first, xy.second) == true
                    else -> false
                }
                if (ok) DeviceLanePolicy.ok(
                    command.commandId,
                    """{"clicked":${jsonQuote(node?.ref ?: id ?: "${xy?.first},${xy?.second}")}}""",
                )
                else {
                    val code = when {
                        !id.isNullOrBlank() -> "stale_ref"
                        !query.isNullOrBlank() -> "no_match"
                        xy != null -> "click_failed"
                        else -> "stale_ref"
                    }
                    val message = when (code) {
                        "stale_ref" -> "ref is not from the last snapshot — snapshot again"
                        "no_match" -> "no unique clickable node matching '$query'"
                        else -> "click did not land"
                    }
                    DeviceLanePolicy.fail(command.commandId, code, message)
                }
            }
            "device.type" -> {
                val text = jsonField(args, "text").orEmpty()
                val ok = service?.type(text) == true
                if (ok) DeviceLanePolicy.ok(command.commandId)
                else DeviceLanePolicy.fail(
                    command.commandId,
                    "no_focus",
                    "no focused field — click an input, then mobile_type",
                )
            }
            "device.press" -> {
                val key = jsonField(args, "key").orEmpty()
                val ok = service?.press(key) == true
                if (ok) DeviceLanePolicy.ok(command.commandId, """{"key":${jsonQuote(key)}}""")
                else DeviceLanePolicy.fail(command.commandId, "a11y_unavailable")
            }
            "device.swipe" -> {
                val spec = swipeSpec(args, service) ?: return DeviceLanePolicy.fail(command.commandId, "stale_ref")
                val ok = service?.swipe(spec) == true
                if (ok) DeviceLanePolicy.ok(command.commandId)
                else DeviceLanePolicy.fail(command.commandId, "a11y_unavailable")
            }
            "device.scroll" -> {
                val direction = jsonField(args, "direction").orEmpty()
                val id = jsonField(args, "ref")
                val node = lastNodes.find { it.ref == id }
                val (cx, cy) = if (node != null && node.bounds.size >= 4) {
                    (node.bounds[0] + node.bounds[2]) / 2f to (node.bounds[1] + node.bounds[3]) / 2f
                } else {
                    val (w, h) = service?.displaySize() ?: (1080 to 2400)
                    w / 2f to h / 2f
                }
                val spec = DeviceGestures.scroll(direction, cx, cy)
                    ?: return DeviceLanePolicy.fail(command.commandId, "stale_ref")
                val ok = service?.swipe(spec) == true
                if (ok) DeviceLanePolicy.ok(command.commandId, """{"direction":${jsonQuote(direction)}}""")
                else DeviceLanePolicy.fail(command.commandId, "a11y_unavailable")
            }
            "device.open_app" -> {
                val pkg = jsonField(args, "package").orEmpty()
                val ok = pkg.isNotBlank() && service?.openApp(pkg) == true
                if (ok) DeviceLanePolicy.ok(command.commandId, """{"package":${jsonQuote(pkg)}}""")
                else DeviceLanePolicy.fail(command.commandId, "stale_ref", "app not launchable")
            }
            "device.apps" -> {
                val rows = service?.apps().orEmpty().joinToString(",") { (pkg, label) ->
                    """{"package":${jsonQuote(pkg)},"label":${jsonQuote(label)}}"""
                }
                DeviceLanePolicy.ok(command.commandId, """{"apps":[$rows]}""")
            }
            "device.wait" -> {
                val ms = DeviceGestures.waitMs(jsonNumber(args, "ms")?.toLong() ?: 0L)
                delay(ms)
                DeviceLanePolicy.ok(command.commandId, """{"ms":$ms}""")
            }
            "device.arm" -> {
                arm()
                val armed = _state.value.arm != DeviceArm.DISARMED
                if (!armed) DeviceLanePolicy.fail(command.commandId, "a11y_unavailable")
                else DeviceLanePolicy.ok(command.commandId, """{"armed":true}""")
            }
            "device.disarm" -> {
                disarm()
                DeviceLanePolicy.ok(command.commandId, """{"armed":false}""")
            }
            "device.screenshot" -> {
                val maxEdge = (jsonNumber(args, "max_edge")?.toInt() ?: DeviceGestures.SCREENSHOT_MAX_PX)
                    .coerceIn(64, DeviceGestures.SCREENSHOT_MAX_PX)
                val shot = service?.screenshotPng(maxEdge)
                    ?: return DeviceLanePolicy.fail(command.commandId, "timeout", "screenshot failed")
                val b64 = Base64.encodeToString(shot.first, Base64.NO_WRAP)
                DeviceLanePolicy.ok(
                    command.commandId,
                    """{"mime":"image/png","w":${shot.second},"h":${shot.third},"png_b64":"$b64"}""",
                )
            }
            else -> DeviceLanePolicy.fail(command.commandId, "capability_denied")
        }
    }

    private fun swipeSpec(args: String, service: CompanionAccessibilityService?): SwipeSpec? {
        val x1 = jsonNumber(args, "x1")
        val y1 = jsonNumber(args, "y1")
        val x2 = jsonNumber(args, "x2")
        val y2 = jsonNumber(args, "y2")
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return DeviceGestures.swipe(x1, y1, x2, y2)
        }
        val from = lastNodes.find { it.ref == jsonField(args, "from_ref") }
        val to = lastNodes.find { it.ref == jsonField(args, "to_ref") }
        if (from != null && to != null && from.bounds.size >= 4 && to.bounds.size >= 4) {
            return DeviceGestures.swipe(
                (from.bounds[0] + from.bounds[2]) / 2f,
                (from.bounds[1] + from.bounds[3]) / 2f,
                (to.bounds[0] + to.bounds[2]) / 2f,
                (to.bounds[1] + to.bounds[3]) / 2f,
            )
        }
        val dx = jsonNumber(args, "dx")
        val dy = jsonNumber(args, "dy")
        if (dx != null && dy != null) {
            val (w, h) = service?.displaySize() ?: (1080 to 2400)
            val cx = w / 2f
            val cy = h / 2f
            return DeviceGestures.swipe(cx, cy, cx + dx, cy + dy)
        }
        return null
    }

    private fun noteAudit(action: String, app: String, ok: Boolean, code: String) {
        val row = DeviceAudit.row(action = action, app = app, ok = ok, code = code)
        auditRows = DeviceAudit.append(auditRows, row)
        val line = "${row.action} ${if (row.ok) "ok" else row.code} ${row.app}".trim()
        _state.update { it.copy(lastAudit = line, foregroundApp = CompanionAccessibilityService.foregroundApp) }
    }

    private fun jsonField(raw: String, key: String): String? {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(raw)
        return match?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    }

    private fun jsonNumber(raw: String, key: String): Float? {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(raw)
        return match?.groupValues?.getOrNull(1)?.toFloatOrNull()
    }

    private fun jsonXy(raw: String): Pair<Float, Float>? {
        val match = Regex("\"xy\"\\s*:\\s*\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)").find(raw)
        val x = match?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return null
        val y = match.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null
        return x to y
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }
}

/** Pairing phase derives from the stored credential, but only for the origin it was issued by. */
private fun DeviceNodeState.withCred(cred: DeviceCred?, origin: String?): DeviceNodeState {
    val mismatch = cred != null && cred.origin.isNotBlank() && !origin.isNullOrBlank() &&
        HostClientPool.key(cred.origin) != HostClientPool.key(origin)
    if (cred == null || mismatch) {
        return copy(pairingPhase = PairingPhase.IDLE, pairingCode = "", deviceId = null, deviceProfileId = null)
    }
    return copy(
        pairingPhase = PairingPhase.PAIRED,
        pairingCode = "",
        deviceId = cred.deviceId,
        deviceProfileId = cred.profileId,
    )
}
