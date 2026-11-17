package app.hermes.companion

import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.domain.*
import app.hermes.companion.model.*
import app.hermes.hands.client.HandsClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class DeviceNodeState(
    val externalHands: Boolean = false,
    val externalHandsReachable: Boolean = false,
    val externalHandsArmed: Boolean = false,
    val externalHandsBackend: String = "",
    val externalHandsTarget: String = "",
    val externalHandsA11y: Boolean = false,
    val externalHandsPrivacy: Boolean = false,
    val externalHandsScreenControl: Boolean = false,
    val externalHandsError: String? = null,
    val pairingPhase: PairingPhase = PairingPhase.IDLE,
    val pairingCode: String = "",
    val deviceId: String? = null,
    val deviceLabel: String = "",
    val deviceProfileId: String? = null,
    val laneOpen: Boolean = false,
    val a11yBound: Boolean = false,
    val a11yListed: Boolean? = null,
    val a11yOffRequested: Boolean = false,
    val foregroundApp: String = "",
    val lastAudit: String = "",
    val arm: DeviceArm = DeviceArm.DISARMED,
    val armPending: Boolean = false,
    val protectedCustom: List<String> = emptyList(),
    val protectedError: String? = null,
    /** Hosts this phone is paired with (normalised origins) and the lanes currently open (A8.5). */
    val pairedHosts: List<String> = emptyList(),
    val openLanes: Set<String> = emptySet(),
    val notifStreamEnabled: Boolean = false,
    val notifStreamApps: Set<String> = emptySet(),
    val notifStreamContent: String = "metadata",
    val notifStreamRetentionMinutes: Int = 15,
    val notifStreamCalls: Boolean = true,
    val notifStreamDelivery: String = "Off",
    val notifStreamOrigin: String? = null,
    val notifStreamProfile: String? = null,
    val nlsBound: Boolean = false,
    val notifLastAgeMs: Long? = null,
)

/** Host transport only. All Android control, permissions and personal data belong to Hands. */
class DeviceNodeCoordinator(
    private val app: CompanionApp,
    private val clients: HostClientPool,
    private val deviceCreds: DeviceCredStore,
    private val sticky: StickyStore,
    private val scope: CoroutineScope,
    private val handsClient: HandsClient = app.handsClient,
) {
    private val _state = MutableStateFlow(DeviceNodeState(
        pairedHosts = deviceCreds.loadAll().map { it.origin },
        notifStreamEnabled = sticky.notifStreamEnabled,
        notifStreamOrigin = sticky.notifStreamTargetOrigin(),
        notifStreamProfile = sticky.notifStreamTargetOrigin()?.let(sticky::notifStreamProfileFor)))
    val state: StateFlow<DeviceNodeState> = _state
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors
    @Volatile private var origin: String? = null
    private val lanes = mutableMapOf<String, Job>()
    private val owners = mutableMapOf<String, MutableSet<String>>()
    private var pairJob: Job? = null
    private var idleJob: Job? = null
    private fun client(origin: String): DashboardClient = clients.forOrigin(origin)
    private val notifications = HandsNotificationRelay(app, handsClient, clients, deviceCreds, sticky, scope) { status ->
        _state.update { it.copy(notifStreamApps = status.apps, notifStreamContent = status.content,
            notifStreamRetentionMinutes = status.retentionMinutes, notifStreamCalls = status.includeCalls,
            nlsBound = status.nlsBound, notifStreamDelivery = status.message, notifLastAgeMs = status.lastAgeMs) }
    }
    init {
        scope.launch {
            handsClient.state.collect { hands ->
                val old = _state.value
                _state.update { it.copy(externalHands = hands.configured, externalHandsReachable = hands.reachable,
                    externalHandsArmed = hands.armed, externalHandsBackend = hands.backend,
                    externalHandsTarget = hands.target, externalHandsA11y = hands.a11yBound,
                    externalHandsPrivacy = hands.privacyActive, externalHandsError = hands.error,
                    externalHandsScreenControl = hands.screenControlAvailable,
                    a11yBound = hands.a11yBound, arm = if (hands.armed) DeviceArm.ARMED else DeviceArm.DISARMED,
                    protectedCustom = hands.protectedPackages.sorted()) }
                if (old.externalHandsArmed != hands.armed || old.externalHandsPrivacy != hands.privacyActive ||
                    old.externalHandsReachable != hands.reachable || old.a11yBound != hands.a11yBound ||
                    old.externalHandsScreenControl != hands.screenControlAvailable ||
                    old.protectedCustom.toSet() != hands.protectedPackages) publishPrivacyStatus()
                if (old.externalHands != hands.configured) refreshLaneRegistrations()
            }
        }
        notifications.start()
        if (sticky.stayConnected || sticky.notifStreamEnabled) startAllLanes()
    }

    suspend fun connectExternalHands(token: String, endpoint: String) {
        runCatching { handsClient.connect(token, endpoint) }.onFailure {
            _errors.tryEmit("Open Hands and approve Companion again.")
        }.onSuccess { connected ->
            if (!connected) _errors.tryEmit("Stop the active controller in Hands before reconnecting.")
            else refreshLaneRegistrations()
        }
    }
    fun disconnectExternalHands() { scope.launch {
        if (!handsClient.stopAll() || !handsClient.disconnect()) _errors.tryEmit("Open Hands and stop control before disconnecting.")
        else { notifications.invalidateRoute(); refreshLaneRegistrations() }
    } }
    fun refreshExternalHands() { if (handsClient.state.value.configured) scope.launch { handsClient.refresh() } }

    fun arm(directOnly: Boolean = false, durationSeconds: Long = 3600, leaseId: String = java.util.UUID.randomUUID().toString()) {
        _errors.tryEmit("Open Hands to allow a session. The agent then acquires its own holder.")
    }
    fun disarm() { scope.launch {
        if (handsClient.state.value.configured && !handsClient.stopAll()) _errors.tryEmit("Open Hands to stop control.")
    } }
    fun recoverAccessibility() = refreshExternalHands()
    fun refreshAccessibilityState() = refreshExternalHands()
    fun setA11yBound(bound: Boolean) = refreshExternalHands()
    fun turnAccessibilityOn(): Boolean { openHands(); return false }
    fun turnAccessibilityOff(): Boolean { disarm(); openHands(); return false }
    fun pauseForPrivateEntry() = disarm()
    fun addProtectedPackage(raw: String) = openHands()
    fun removeProtectedPackage(pkg: String) = openHands()
    fun setKeepAwake(enabled: Boolean, durationSeconds: Long = 3600): JSONObject =
        throw IllegalStateException("hands_required")
    fun keepAwakeStatus(): JSONObject = JSONObject(handsClient.state.value.keepAwakeJson)
    fun renewArm(leaseId: String): JSONObject = throw IllegalStateException("hands_required")
    fun releaseArmLease(leaseId: String): JSONObject = throw IllegalStateException("hands_required")
    fun deviceStatus(): JSONObject = JSONObject().put("owner", "hands")
        .put("available", handsClient.state.value.reachable).put("armed", handsClient.state.value.armed)
        .put("privacy_active", handsClient.state.value.privacyActive)
    private fun openHands() {
        runCatching { app.startActivity(Intent().setClassName("app.hermes.hands", "app.hermes.hands.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { _errors.tryEmit("Install and open Hands.") }
    }
    private fun syncHands(armed: Boolean) = Unit
    fun publishPrivacyStatus() {
        val fields = deviceReadinessFields()
        deviceCreds.loadAll().forEach { cred -> clients.existing(cred.origin)?.sendDeviceStatus(cred.deviceId, fields) }
    }
    private fun deviceReadinessFields(): String {
        val h = handsClient.state.value
        return JSONObject().put("armed", h.armed).put("a11y_bound", h.a11yBound)
            .put("screen_control_available", h.screenControlAvailable)
            .put("privacy_active", h.privacyActive).put("control_owner", "hands")
            .put("hands_reachable", h.reachable).put("protected_packages", org.json.JSONArray(h.protectedPackages.sorted()))
            .toString().removePrefix("{").removeSuffix("}").let { "," + it }
    }
    private fun laneCapabilities(): List<String> = handsClient.state.value.capabilities.toList()
    private fun releaseLaneHolders(key: String) {
        val ids = synchronized(owners) { owners.remove(key)?.toList().orEmpty() }
        ids.forEach { id -> scope.launch { handsClient.releaseController(id) } }
    }

    fun setNotifStreamEnabled(enabled: Boolean, deferLaneRefresh: Boolean = false) {
        notifications.invalidateRoute()
        if (enabled && (sticky.notifStreamTargetOrigin() == null || sticky.notifStreamTargetOrigin()?.let(sticky::notifStreamProfileFor).isNullOrBlank())) {
            _errors.tryEmit("Choose a notification gateway and profile first."); return
        }
        sticky.notifStreamConsentVersion++
        sticky.notifStreamEnabled = enabled
        if (enabled) notifications.start()
        _state.update { it.copy(notifStreamEnabled = enabled) }
        syncStayForStream()
        if (!deferLaneRefresh) refreshLaneRegistrations()
    }
    fun setNotifStreamTarget(origin: String?, profileId: String?, deferLaneRefresh: Boolean = false) {
        val selected = origin?.trim()?.takeIf { value -> deviceCreds.loadAll().any { deviceLaneKey(it.origin) == deviceLaneKey(value) } }
        notifications.invalidateRoute()
        sticky.notifStreamEnabled = false
        sticky.notifStreamConsentVersion++
        sticky.notifStreamOrigin = selected
        if (selected != null) sticky.setNotifStreamProfile(selected, profileId?.trim()?.takeIf { it.isNotBlank() })
        _state.update { it.copy(notifStreamEnabled = false, notifStreamOrigin = selected,
            notifStreamProfile = selected?.let(sticky::notifStreamProfileFor)) }
        syncStayForStream()
        if (!deferLaneRefresh) refreshLaneRegistrations()
    }
    fun setNotifStreamApp(pkg: String, selected: Boolean) = openHands()
    fun setNotifStreamContent(mode: String) = openHands()
    fun setNotifStreamRetention(minutes: Int) = openHands()
    fun toggleNotifStreamCalls() = openHands()
    fun pauseForBackgroundLimit() { notifications.pause(); setNotifStreamEnabled(false) }
    private fun syncStayForStream() {
        if (sticky.notifStreamEnabled || sticky.stayConnected) runCatching {
            ContextCompat.startForegroundService(app, Intent(app, StayConnectedService::class.java))
        } else runCatching { app.stopService(Intent(app, StayConnectedService::class.java)) }
    }
    private fun pushStreamStatus() = publishPrivacyStatus()
    private fun flushPending(origin: String, deviceId: String, profile: String) = Unit

    fun startAllLanes() {
        deviceCreds.loadAll().forEach { startLane(it.origin) }
    }

    /** Bounce open lanes so register re-sends metadata / custom denylist. */
    private fun refreshLaneRegistrations() {
        val open = _state.value.openLanes.toList()
        val byKey = deviceCreds.loadAll().associateBy { deviceLaneKey(it.origin) }
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
                laneOpen = deviceLaneKey(origin) in it.openLanes,
            )
        }
        startAllLanes()
    }

    /** Chat UI lifetime must not stop the explicitly consented background notification lane. */
    fun unbind() {
        if (sticky.stayConnected) { origin = null; return }
        val streamTarget = if (sticky.notifStreamEnabled) sticky.notifStreamTargetOrigin() else null
        lanes.keys.toList().filter { it != streamTarget?.let(::deviceLaneKey) }.forEach { stopLane(it) }
        if (streamTarget != null) startLane(streamTarget)
        origin = null
    }

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

    fun pairWithCode(targetOrigin: String, targetCode: String, profile: String = "default") {
        val cleanOrigin = targetOrigin.trim().trimEnd('/')
        val cleanCode = targetCode.trim().uppercase()
        if (cleanOrigin.isBlank() || cleanCode.isBlank()) return
        if (!OriginPolicy.cleartextAllowed(cleanOrigin)) {
            _errors.tryEmit(OriginPolicy.CLEARTEXT_DENIED)
            return
        }
        // A failed link pair must not leave the node pointed at a host it never paired with.
        val previousOrigin = this.origin
        this.origin = cleanOrigin
        pairJob?.cancel()
        pairJob = scope.launch {
            val started = System.currentTimeMillis()
            _state.update {
                it.copy(
                    pairingPhase = PairingPhase.WAITING,
                    pairingCode = cleanCode,
                    deviceProfileId = profile,
                )
            }
            try {
                // `hermes companion qr` issues the code on the host (pending, or approved without
                // --no-approve). Offer only a code the host doesn't know: re-offering a pending one
                // is code_in_use.
                var status = runCatching { client(cleanOrigin).pollPair(cleanOrigin, cleanCode) }.getOrNull()
                if (status == null) {
                    status = client(cleanOrigin).offerPair(cleanOrigin, cleanCode, profile)
                }
                if (!status.approved) {
                    val auto = runCatching { client(cleanOrigin).approvePair(cleanOrigin, cleanCode) }.getOrNull()
                    if (auto != null && auto.approved) {
                        status = auto
                    }
                }
                while (isActive) {
                    if (status != null && status.approved) {
                        deviceCreds.save(
                            DeviceCred(
                                deviceId = status.deviceId,
                                profileId = status.profileId.ifBlank { profile },
                                credential = status.credential,
                                origin = cleanOrigin,
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
                        startLane(cleanOrigin)
                        return@launch
                    }
                    if (PairingPolicy.expired(started)) {
                        _state.update { it.copy(pairingPhase = PairingPhase.IDLE, pairingCode = "") }
                        if (this@DeviceNodeCoordinator.origin == cleanOrigin) this@DeviceNodeCoordinator.origin = previousOrigin
                        _errors.tryEmit("code expired")
                        return@launch
                    }
                    delay(PairingPolicy.POLL_MS)
                    status = runCatching { client(cleanOrigin).pollPair(cleanOrigin, cleanCode) }.getOrNull() ?: status
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(pairingPhase = PairingPhase.IDLE, pairingCode = "") }
                if (this@DeviceNodeCoordinator.origin == cleanOrigin) this@DeviceNodeCoordinator.origin = previousOrigin
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
            if (!stillPaired) disarm() else if (_state.value.arm != DeviceArm.DISARMED) syncHands(true)
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
        val key = deviceLaneKey(laneOrigin)
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
                        protectedPackages = _state.value.protectedCustom.toSet(),
                        capabilities = laneCapabilities(),
                        // Room wake (A22.5): the relay pings this topic when a room needs the operator.
                        wakeTopic = sticky.ntfyTopicFor(laneOrigin).orEmpty(),
                    )
                    client(laneOrigin).sendDeviceStatus(cred.deviceId, deviceReadinessFields())
                    // After lane is up, push stream status + flush buffered notifs for this target.
                    if (WakePolicy.topicOwnerKey(laneOrigin) == WakePolicy.topicOwnerKey(sticky.notifStreamTargetOrigin().orEmpty())) {
                        pushStreamStatus()
                        sticky.notifStreamProfileFor(laneOrigin)?.let { flushPending(laneOrigin, cred.deviceId, it) }
                    }
                    if (_state.value.deviceLabel.isBlank()) {
                        _state.update { it.copy(deviceLabel = label) }
                    }
                    markLane(key, open = true)
                    backoff = 1_000L
                    dispatchArmCommands(
                        client(laneOrigin).deviceCommands(),
                        armBusy = { command -> client(laneOrigin).replyDevice(DeviceLanePolicy.fail(command.commandId, "arm_pending")) },
                        filesBusy = { command -> client(laneOrigin).replyDevice(DeviceLanePolicy.fail(command.commandId, "file_busy")) },
                        handle = { handleCommand(laneOrigin, it) },
                    )
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
        if (!open) releaseLaneHolders(key)
        _state.update {
            val lanesNow = if (open) it.openLanes + key else it.openLanes - key
            it.copy(
                openLanes = lanesNow,
                laneOpen = origin?.let { o -> deviceLaneKey(o) in lanesNow } ?: false,
            )
        }
        // The kill-switch notification names live lanes; keep it current while armed.
        if (_state.value.arm != DeviceArm.DISARMED) syncHands(armed = true)
        if (sticky.notifStreamEnabled || sticky.stayConnected) StayConnectedService.refresh(app)
    }

    private fun stopLane(laneOrigin: String) {
        val key = deviceLaneKey(laneOrigin)
        lanes.remove(key)?.cancel()
        clients.existing(laneOrigin)?.closeDeviceLane()
        markLane(key, open = false)
    }

    internal suspend fun forwardExternalCommand(laneOrigin: String, command: DeviceCommand): DeviceResult? {
        val cred = deviceCreds.load(laneOrigin)
        if (cred == null || (command.deviceId.isNotBlank() && command.deviceId != cred.deviceId))
            return DeviceLanePolicy.fail(command.commandId, "unauthorized")
        if (!handsClient.state.value.configured)
            return DeviceLanePolicy.fail(command.commandId, "hands_required", "Connect Hands in the Companion Device tab.")
        val profile = command.profile.ifBlank { cred.profileId }
        val args = runCatching { JSONObject(command.argumentsJson) }.getOrElse {
            return DeviceLanePolicy.fail(command.commandId, "bad_args")
        }
        val notificationCommand = command.action.startsWith("device.notifications.") && command.action !in setOf(
            "device.notifications.nls", "device.notifications.nls.status", "device.notifications.nls.enable",
            "device.notifications.enable", "device.notifications.status")
        val notificationConsent = sticky.notifStreamConsentVersion
        fun notificationRouteApproved(): Boolean {
            val target = sticky.notifStreamTargetOrigin()
            return sticky.notifStreamEnabled && target != null && deviceLaneKey(target) == deviceLaneKey(laneOrigin) &&
                sticky.notifStreamProfileFor(target) == profile
        }
        if (notificationCommand) {
            val target = sticky.notifStreamTargetOrigin()
                ?: return DeviceLanePolicy.fail(command.commandId, "notification_scope_mismatch")
            val sink = sticky.notifStreamProfileFor(target)
            if (!notificationRouteApproved())
                return DeviceLanePolicy.fail(command.commandId, "notification_scope_mismatch", "Enable notification forwarding for this gateway/profile in Companion.")
            if ((args.has("origin") && deviceLaneKey(args.optString("origin")) != deviceLaneKey(target)) ||
                (args.has("profile") && args.optString("profile") != sink))
                return DeviceLanePolicy.fail(command.commandId, "notification_scope_mismatch")
            args.remove("origin"); args.remove("profile")
        }
        val owner = HandsClient.controllerId(deviceLaneKey(laneOrigin), profile)
        synchronized(owners) { owners.getOrPut(deviceLaneKey(laneOrigin)) { linkedSetOf() }.add(owner) }
        val reply = handsClient.command(command.action, args.toString(), owner)
        if (notificationCommand && (sticky.notifStreamConsentVersion != notificationConsent || !notificationRouteApproved()))
            return DeviceLanePolicy.fail(command.commandId, "notification_scope_mismatch", "Notification forwarding approval changed before delivery.")
        return DeviceResult(command.commandId, reply.ok, reply.resultJson, reply.errorCode, reply.message)
    }
    private suspend fun handleCommand(laneOrigin: String, command: DeviceCommand) {
        val result = checkNotNull(forwardExternalCommand(laneOrigin, command))
        client(laneOrigin).replyDevice(result)
        _state.update { it.copy(lastAudit = "${command.action}: ${if (result.ok) "ok" else result.errorCode}") }
    }
}

/** Pairing phase derives from the stored credential, but only for the origin it was issued by. */
private fun DeviceNodeState.withCred(cred: DeviceCred?, origin: String?): DeviceNodeState {
    val mismatch = cred != null && cred.origin.isNotBlank() && !origin.isNullOrBlank() &&
        deviceLaneKey(cred.origin) != deviceLaneKey(origin)
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

/** Preserve proxy prefixes when identifying a paired device lane. */
internal fun deviceLaneKey(origin: String): String = WakePolicy.topicOwnerKey(origin).orEmpty()
