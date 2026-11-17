package app.hermes.hands

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import app.hermes.companion.device.*
import app.hermes.companion.domain.DeviceLanePolicy
import app.hermes.companion.domain.CompactTree
import app.hermes.companion.domain.SwipeSpec
import app.hermes.companion.device.AccessibilitySettingsToggle
import app.hermes.hands.engine.ShizukuController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock

internal object AccessibilityArmPolicy {
    fun shouldRecover(directOnly: Boolean, target: String, backend: String, shizukuReady: Boolean,
        accessibilityBound: Boolean, explicitlyOff: Boolean, secureGrant: Boolean): Boolean =
        !directOnly && target == "primary" && !accessibilityBound && !explicitlyOff && secureGrant &&
            (backend == "accessibility" || (backend == "hybrid" && !shizukuReady))
}

internal object AccessibilityReadiness {
    fun allows(bound: Boolean, explicitlyOff: Boolean, listed: Boolean?): Boolean =
        bound && !explicitlyOff && listed == true
}

data class HandsState(
    val serving: Boolean = false,
    val consent: Boolean = false,
    val armed: Boolean = false,
    val backend: String = "hybrid",
    val target: String = "agent",
    val displayId: Int = -1,
    val client: String = "No active controller",
    val message: String = "Start the service and choose a screen for control.",
    val protectedPackages: Set<String> = emptySet(),
)

interface AgentScreenHost {
    fun start(width: Int, height: Int, density: Int): Int
    fun closeScreen()
}

/** Agent-neutral policy/executor. No Hermes profile, model or gateway dependency. */
class HandsRuntime(
    private val context: Context,
    val shizuku: ShizukuController,
    private val tlsIdentityFactory: (Context) -> TlsIdentity = { TlsIdentity(it) },
) {
    val tokens = ClientTokens(context)
    val remotePairing = RemotePairing(tokens::issue)
    private val preferences = context.getSharedPreferences("hands.settings", Context.MODE_PRIVATE)
    private val _remoteAccess = MutableStateFlow(RemoteAccessState(
        autoStart = preferences.getBoolean("remote_enabled", true),
    ))
    val remoteAccess: StateFlow<RemoteAccessState> = _remoteAccess
    private val _localAuthorization = MutableStateFlow<String?>(null)
    val localAuthorization: StateFlow<String?> = _localAuthorization
    private val _state = MutableStateFlow(HandsState(
        backend = preferences.getString("backend", "hybrid") ?: "hybrid",
        target = preferences.getString("target", "agent") ?: "agent",
        protectedPackages = preferences.getStringSet("protected", emptySet()).orEmpty().toSet(),
    ))
    val state: StateFlow<HandsState> = _state
    private val lease = ControlLease(SystemClock::elapsedRealtime)
    private val commands = ReentrantLock()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pairingBoundary = LocalPairingBoundary(commands, { revokeControl() },
        remotePairing::openWindow, ::requirePairingReady,
        { _localAuthorization.value != null || remotePairing.state.value.let { it.open || it.pending != null } })
    val secureEntry by lazy { HandsSecureEntryController(context, this) }
    val notifications by lazy { HandsNotifications(context, { state.value.protectedPackages },
        { DevicePrivacy.active || pairingBoundary.isProtected }) }
    private val deviceTools by lazy { HandsDeviceTools(context, protectedPackages = { state.value.protectedPackages }, targetIsAgent = { state.value.target == "agent" }) }
    private val secureSettingsTools by lazy { HandsSecureSettingsTools(context, protectedPackages = { state.value.protectedPackages }) }
    @Volatile private var approvedPermissions: String? = null
    @Volatile var screenHost: AgentScreenHost? = null
    @Volatile private var observation: ScreenObservation? = null
    @Volatile private var controllerLabel = "No active controller"
    @Volatile private var activeClientId: String? = null
    private var server: HandsHttpServer? = null
    private var remoteServer: HandsHttpServer? = null
    private val directOnlyHolders = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val holderClassification = Any()
    private val physicalHold = HandsKeepAwake(context, "hands-armed")
    private val standaloneHold = HandsKeepAwake(context, "hands-standalone")
    @Volatile private var standaloneDeadline = 0L
    @Volatile private var privilegedSession = 0L

    fun configure(backend: String = state.value.backend, target: String = state.value.target) {
        require(backend in setOf("accessibility", "shizuku", "hybrid"))
        require(target in setOf("primary", "agent"))
        disarmControl()
        preferences.edit().putString("backend", backend).putString("target", target).apply()
        _state.value = state.value.copy(backend = backend, target = target)
    }

    fun protect(packages: Set<String>) {
        require(packages.size <= 100 && packages.all { DeviceLanePolicy.normalizePackage(it) == it })
        disarmControl()
        preferences.edit().putStringSet("protected", packages).apply()
        _state.value = state.value.copy(protectedPackages = packages.toSet())
    }

    fun revokeClient(id: String) {
        if (activeClientId == id) disarmControl()
        notifications.onClientRevoked(id)
        tokens.revoke(id)
    }

    fun configureNotifications(enabled: Boolean, clientId: String?, packages: Set<String>, content: String,
        retentionMinutes: Int, includeCalls: Boolean, approvalId: String) {
        check(_localAuthorization.value == approvalId) { "approval_cancelled" }
        requireLocalAuthorizationReady()
        require(clientId == null || tokens.clients().any { it.id == clientId }) { "unknown_client" }
        if (enabled) {
            check(state.value.serving) { "service_required" }
            check(context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) { "service_notifications_required" }
            check(MobilePermissions.states(context).first { it.id == "notifications" }.granted) { "notification_access_required" }
        }
        notifications.configureLocal(enabled, clientId, packages, content, retentionMinutes, includeCalls)
    }

    /** Android callers and manual token dialogs share the same pause/drain boundary. */
    fun beginLocalAuthorization(): String {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Prepare local approval from a worker." }
        val id = java.util.UUID.randomUUID().toString()
        pairingBoundary.open(show = { _localAuthorization.value = id }, ready = ::requireLocalAuthorizationReady)
        return id
    }

    fun endLocalAuthorization(id: String) {
        pairingBoundary.cancelIf({ _localAuthorization.value == id }) { _localAuthorization.value = null }
        notifications.refresh()
    }

    fun closePairingDialog() {
        remotePairing.close()
        pairingBoundary.cancel {
            _localAuthorization.value = null
        }
        notifications.refresh()
    }

    fun issueLocalClient(label: String, approvalId: String, protectedAdditions: Set<String> = emptySet()): Pair<ClientTokens.Client, String> {
        check(_localAuthorization.value == approvalId) { "approval_cancelled" }
        requireLocalAuthorizationReady()
        if (protectedAdditions.isNotEmpty()) {
            val combined = state.value.protectedPackages + protectedAdditions
            require(combined.size <= 100 && combined.all { DeviceLanePolicy.normalizePackage(it) == it })
            preferences.edit().putStringSet("protected", combined).apply()
            _state.value = state.value.copy(protectedPackages = combined)
        }
        val issued = tokens.issue(label)
        if (_localAuthorization.value != approvalId) {
            tokens.revoke(issued.first.id)
            throw IllegalStateException("approval_cancelled")
        }
        return issued
    }

    private fun requireLocalAuthorizationReady() {
        check(isScreenOn() && !isLocked() && !DevicePrivacy.active) { "Unlock the phone before approving a client." }
    }

    /** Local UI only. No command can enable a listener or open a pairing window. */
    @Synchronized fun enableRemote() {
        check(server != null) { "Start the connection service first." }
        check(isScreenOn() && !isLocked() && !DevicePrivacy.active) { "Unlock the phone before enabling remote access." }
        preferences.edit().putBoolean("remote_enabled", true).apply()
        startRemoteInternal()
    }

    internal fun startRemoteInternal() {
        if (remoteServer != null) return
        _remoteAccess.value = RemoteAccessState(message = "Starting encrypted access…", starting = true, autoStart = true)
        try {
            val identity = tlsIdentityFactory(context)
            val next = HandsHttpServer(tokens::authenticate, { status() }, ::command,
                port = 8766, remoteIdentity = identity, pair = remotePairing::request)
            next.start()
            remoteServer = next
            _remoteAccess.value = RemoteAccessState(true, identity.fingerprint, remoteEndpoints(),
                "Encrypted access is available to paired agents. Screen control uses the screen and backend selected in Hands.",
                autoStart = true)
        } catch (failure: Exception) {
            _remoteAccess.value = RemoteAccessState(message = "Could not start encrypted access. ${failure.message ?: "Try again."}",
                autoStart = true)
            throw failure
        }
    }

    @Synchronized fun disableRemote(clearPreference: Boolean = true) {
        if (clearPreference) {
            preferences.edit().putBoolean("remote_enabled", false).apply()
        }
        remotePairing.close()
        val previous = remoteServer
        remoteServer = null
        if (_remoteAccess.value.enabled) disarmControl()
        val auto = preferences.getBoolean("remote_enabled", true)
        try { previous?.close() }
        finally { _remoteAccess.value = RemoteAccessState(autoStart = auto) }
    }

    fun openRemotePairing() {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Open pairing from a worker." }
        requirePairingReady()
        pairingBoundary.open()
    }

    private fun requirePairingReady() {
        check(remoteAccess.value.enabled) { "Enable LAN / Tailscale access first." }
        check(isScreenOn() && !isLocked() && !DevicePrivacy.active) { "Unlock the phone before pairing an agent." }
    }

    fun approve() {
        check(!pairingBoundary.isProtected) { "Close pairing before allowing a control session." }
        check(state.value.serving) { "Start the connection service first." }
        check(!DevicePrivacy.active) { "Finish private entry on this phone first." }
        check(context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) { "Allow Hands notifications so the Stop control stays available." }
        check(android.provider.Settings.canDrawOverlays(context)) { "Allow the floating screen permission before starting control." }
        disarmControl()
        approvedPermissions = MobilePermissions.signature(context)
        lease.approve()
        observation = null
        refresh("Ready for a controller while the service is running.")
    }

    /** Explicit global/local Stop also stops passive sharing. Scoped holder release does not. */
    fun disarm() {
        disarmControl()
        notifications.close()
    }

    /** A control backend failure does not withdraw the separate notification approval. */
    internal fun stopSession() = disarmControl()

    private fun disarmControl() {
        pairingBoundary.cancel {
            _localAuthorization.value = null
            remotePairing.close()
        }
        revokeControl(clearAuthorization = false)
        notifications.refresh()
    }

    private fun revokeControl(clearAuthorization: Boolean = true) {
        if (clearAuthorization) {
            _localAuthorization.value = null
            remotePairing.close()
        }
        synchronized(holderClassification) {
            lease.revoke()
            directOnlyHolders.clear()
        }
        approvedPermissions = null
        secureEntry.cancelPending()
        SpeechToolbox.stop()
        privilegedSession = 0
        shizuku.cancelAsync()
        runCatching { physicalHold.release() }
        observation = null
        activeClientId = null
        controllerLabel = "No active controller"
        // Releasing the independent display also cancels its pending input/capture.
        screenHost?.closeScreen()
        _state.value = state.value.copy(consent = false, armed = false, displayId = -1, client = controllerLabel,
            message = "Control is off.")
    }

    fun screenOpened(id: Int) { _state.value = state.value.copy(displayId = id) }
    fun screenClosed() {
        val hadDisplay = state.value.displayId > 0
        _state.value = state.value.copy(displayId = -1)
        observation = null
        if (hadDisplay && lease.active()) disarmControl()
    }
    fun message(text: String) { _state.value = state.value.copy(message = text) }
    fun screenControlExpected(): Boolean = synchronized(holderClassification) {
        lease.holders().any { it.id !in directOnlyHolders }
    }
    private fun accessibilityReady() = AccessibilityReadiness.allows(CompanionAccessibilityService.bound,
        CompanionAccessibilityService.explicitlyOff(context), CompanionAccessibilityService.listed(context))
    private fun screenReady(): Boolean {
        val st = state.value
        val engine = shizuku.status.value
        val privileged = engine.bound && engine.permissionGranted && engine.running
        return when (st.backend) {
            "accessibility" -> st.target == "primary" && accessibilityReady()
            "shizuku" -> privileged
            else -> privileged || (st.target == "primary" && accessibilityReady())
        }
    }

    /** Local preview uses the same approved session; it never acquires a remote controller lease. */
    fun openPreview(): Int {
        check(state.value.serving && state.value.target == "agent") { "Start the service and select the agent screen first." }
        check(state.value.backend != "accessibility") { "Select Shizuku or Hybrid for the separate agent screen." }
        val epoch = lease.currentEpoch()
        if (privilegedSession == 0L) privilegedSession = shizuku.beginSession()
        check(state.value.serving && lease.currentEpoch() == epoch) { "disarmed" }
        val display = checkNotNull(screenHost).start(720, 1280, 240)
        if (!state.value.serving || lease.currentEpoch() != epoch) { disarmControl(); throw IllegalStateException("disarmed") }
        return display
    }

    @Synchronized fun startServer() {
        if (server != null) return
        val next = HandsHttpServer(tokens::authenticate, { status() }, ::command)
        next.start()
        server = next
        _state.value = state.value.copy(serving = true)
        if (preferences.getBoolean("remote_enabled", true)) {
            runCatching { startRemoteInternal() }
        }
    }
    @Synchronized fun stopServer() {
        _state.value = state.value.copy(serving = false)
        disarmControl()
        notifications.close()
        disableRemote(clearPreference = false)
        standaloneDeadline = 0L
        standaloneHold.release()
        server?.close(); server = null
        _state.value = state.value.copy(serving = false)
    }

    fun refresh(message: String? = null) {
        remotePairing.refresh()
        notifications.refresh()
        notifications.state.value.clientId?.takeIf { id -> tokens.clients().none { it.id == id } }?.let(notifications::onClientRevoked)
        if ((isLocked() || !isScreenOn() || DevicePrivacy.active) && pairingBoundary.isProtected) {
            pairingBoundary.cancel {
                _localAuthorization.value = null
                remotePairing.close()
            }
        }
        if (_remoteAccess.value.enabled) _remoteAccess.value = _remoteAccess.value.copy(endpoints = remoteEndpoints())
        val wasApproved = state.value.consent
        val approved = lease.approved()
        if (wasApproved && !approved) { disarmControl(); return }
        // Do not invalidate a fresh hold between beginSession(), lease acquisition and
        // its first overlay attach. Expiry above still revokes an in-flight command.
        if (!commands.tryLock()) return
        try {
        if (standaloneDeadline != 0L && (standaloneDeadline <= SystemClock.elapsedRealtime() || !standaloneHold.isHeld)) {
            standaloneDeadline = 0L; standaloneHold.release()
        }
        _state.value = state.value.copy(consent = approved, armed = lease.active(), client = controllerLabel,
            message = message ?: state.value.message)
        try {
            // Both targets require an unlocked phone. Keep the visible, bounded hold
            // while an agent reasons, without waking a sleeping or locked device.
            if (state.value.armed && !isLocked() && isScreenOn()) physicalHold.renew((lease.remainingSeconds() * 1000).coerceAtLeast(1))
            else physicalHold.release()
        } catch (_: Exception) {
            disarmControl()
            this.message("Screen hold stopped. Check the floating-screen permission and unlock the phone before retrying.")
        }
        } finally { commands.unlock() }
    }

    fun status(): JSONObject {
        refresh()
        val st = state.value
        val engine = shizuku.status.value
        val screenReady = screenReady()
        return ok(JSONObject().put("service", "hermes-hands").put("protocol_version", 1)
            .put("armed", st.armed).put("consent_approved", st.consent).put("backend", st.backend).put("target", st.target)
            .put("expires_in_seconds", lease.remainingSeconds()).put("a11y_bound", CompanionAccessibilityService.bound)
            .put("screen_control_available", screenReady)
            .put("protected_packages", JSONArray(st.protectedPackages.sorted()))
            .put("keep_awake", keepAwakeStatus())
            .put("privacy_active", DevicePrivacy.active).put("client", st.client)
            .put("capabilities", JSONArray(CAPABILITIES))
            .put("permissions", MobilePermissions.json(context))
            .put("lanes", if (screenReady) "l0+l1" else "l0")
            .put("agent_screen", JSONObject().put("active", st.displayId > 0).put("display_id", st.displayId))
            .put("shizuku", JSONObject().put("installed", engine.installed).put("running", engine.running)
                .put("permission_granted", engine.permissionGranted).put("bound", engine.bound).put("message", engine.message)))
    }

    fun command(client: ClientTokens.Client, request: JSONObject): JSONObject {
        val pairingEpoch = pairingBoundary.epoch
        val privacyEpoch = DevicePrivacy.epoch.get()
        if (tokens.clients().none { it.id == client.id }) return error("unauthorized", "This client has been revoked")
        val action = request.optString("action")
        if (request.has("arguments") && request.optJSONObject("arguments") == null) return error("bad_args", "arguments must be an object")
        val args = request.optJSONObject("arguments") ?: JSONObject()
        val controller = request.optString("controller_id")
        if (!controller.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) return error("bad_args", "A controller_id is required")
        val owner = client.id + ":" + controller
        if (action == "device.status" || action == "device.agent_screen.status") return status()
        if (action == "device.agent_screen.stop") { disarm(); return status() }
        if (action == "device.disarm") {
            if (request.has("lease_id")) try {
                val last = synchronized(holderClassification) {
                    val id = request.getString("lease_id")
                    lease.release(owner, id).also { directOnlyHolders.remove(id) }
                }
                if (last) disarmControl() else refresh()
            } catch (_: Exception) { return error("lease_mismatch", "This controller does not own the current lease") }
            else disarm()
            return status()
        }
        val holdStatusOrStop = action == "device.display.keep_awake" &&
            (args.optString("action", "status") == "status" ||
                (args.optString("action") == "set" && args.opt("enabled") == false))
        if (action in (HandsDeviceTools.STOP_ACTIONS + "device.voice.stop")) {
            disarmControl()
            return try { deviceTools.execute(action, args) {
                check(tokens.clients().any { it.id == client.id }) { "unauthorized" }
            } ?: error("unsupported_action", action) } catch (failure: Exception) { failure(failure) }
        }
        if (pairingBoundary.isProtected && !holdStatusOrStop)
            return error("pairing_active", "Remote access is paused while a client is approved on this phone")
        fun readPermit() {
            check(tokens.clients().any { it.id == client.id }) { "unauthorized" }
            check(pairingBoundary.epoch == pairingEpoch && !pairingBoundary.isProtected) { "pairing_active" }
            check(!DevicePrivacy.active && DevicePrivacy.epoch.get() == privacyEpoch) { "privacy_active" }
        }
        if (action == "device.secure_entry" && args.optString("action") in setOf("status", "cancel"))
            return try { ok(secureEntry.command(owner, args) {}) } catch (failure: Exception) { failure(failure) }
        if (DevicePrivacy.active && !holdStatusOrStop) return error("privacy_active", "Remote access is paused for private entry")
        if (action == "device.display.keep_awake") {
            if (holdStatusOrStop) return try { keepAwake(args) } catch (failure: Exception) { failure(failure) }
            if (!commands.tryLock()) return error("controller_busy", "Another action is still finishing")
            try {
                check(pairingBoundary.epoch == pairingEpoch && !pairingBoundary.isProtected) { "pairing_active" }
                val result = keepAwake(args)
                if (pairingBoundary.epoch != pairingEpoch || pairingBoundary.isProtected) {
                    standaloneDeadline = 0L
                    standaloneHold.release()
                    return error("pairing_active", "The screen hold was cancelled for local approval")
                }
                return result
            } catch (failure: Exception) { return failure(failure) }
            finally { commands.unlock() }
        }
        if (DevicePrivacy.active) return error("privacy_active", "Remote access is paused for private entry")
        if (action == "device.permissions") return ok(MobilePermissions.json(context))
        if (action in HandsNotifications.META_ACTIONS && notifications.isPassiveAllowed(client.id, action, args)) return try {
            val version = notifications.state.value.consentVersion
            val result = notifications.command(client.id, action, args, ::readPermit)
            readPermit()
            check(notifications.state.value.consentVersion == version) { "notification_scope_changed" }
            result ?: error("unsupported_action", action)
        } catch (failure: Exception) { failure(failure) }
        if (action in HandsDeviceTools.META_ACTIONS) return try {
            (deviceTools.execute(action, args, ::readPermit) ?: error("unsupported_action", action)).also { reply ->
                if (action == "device.accessibility.status" && reply.optBoolean("ok")) {
                    reply.getJSONObject("result").put("screen_control_available", screenReady()).put("armed", lease.active())
                }
            }
        } catch (failure: Exception) { failure(failure) }
        if (action in PASSIVE_ACTIONS) return try {
            val result = directCommand(action, args)
            readPermit(); result
        } catch (failure: Exception) { failure(failure) }
        if (action == "device.arm") {
            if (!commands.tryLock()) return error("controller_busy", "Another action is still running")
            var acquired: ControlLease.Lease? = null
            try {
            val selected = state.value
            armSelectionMismatch(args, selected.target, selected.backend)?.let { return it }
            if (approvedPermissions != null && approvedPermissions != MobilePermissions.signature(context)) {
                disarmControl(); error("permissions_changed")
            }
            val starting = !lease.active()
            if (starting) physicalHold.beginSession()
            // It is direct-only while binding: the service monitor must not treat a
            // transient missing accessibility connection as loss of an L1 session.
            acquired = synchronized(holderClassification) {
                check(state.value.serving) { "service_unavailable" }
                if (!lease.approved()) {
                    approvedPermissions = MobilePermissions.signature(context)
                    lease.approve()
                }
                lease.acquire(owner, args.optInt("duration_seconds", 3600)).also { directOnlyHolders.add(it.id) }
            }
            if (starting) physicalHold.renew((lease.remainingSeconds() * 1000).coerceAtLeast(1))
            val engine = shizuku.status.value
            if (AccessibilityArmPolicy.shouldRecover(args.optBoolean("direct_only", false), state.value.target,
                    state.value.backend, engine.bound && engine.running && engine.permissionGranted,
                    CompanionAccessibilityService.bound, CompanionAccessibilityService.explicitlyOff(context),
                    CompanionAccessibilityService.canWriteSecureSettings(context))) {
                val candidate = acquired
                val hadComponent = CompanionAccessibilityService.listed(context) == true
                val toggle = AccessibilitySettingsToggle.forContext(context)
                fun armPermit() {
                    lease.require(owner, candidate.id)
                    check(lease.currentEpoch() == candidate.epoch && tokens.clients().any { it.id == client.id }) { "disarmed" }
                    check(pairingBoundary.epoch == pairingEpoch && !pairingBoundary.isProtected) { "pairing_active" }
                    check(!DevicePrivacy.active && DevicePrivacy.epoch.get() == privacyEpoch) { "privacy_active" }
                    check(!CompanionAccessibilityService.explicitlyOff(context)) { "accessibility_disabled" }
                    if (approvedPermissions != MobilePermissions.signature(context)) { disarmControl(); error("permissions_changed") }
                }
                try {
                    val requested = toggle.setEnabled(true, ::armPermit)
                    armPermit()
                    if (requested) {
                        val deadline = SystemClock.elapsedRealtime() + 5_000
                        // Let a normal asynchronous bind settle first. Android can keep a
                        // crashed service listed forever; re-list only our component if it
                        // remains disconnected, with the same permit checks around each write.
                        val rebindAfter = SystemClock.elapsedRealtime() + 1_500
                        if (hadComponent) {
                            while (!CompanionAccessibilityService.bound && SystemClock.elapsedRealtime() < rebindAfter) {
                                armPermit(); Thread.sleep(50)
                            }
                            armPermit()
                            if (!CompanionAccessibilityService.bound) {
                                toggle.rebindListed(::armPermit)
                                armPermit()
                            }
                        }
                        while (!CompanionAccessibilityService.bound && SystemClock.elapsedRealtime() < deadline) {
                            armPermit(); Thread.sleep(50)
                        }
                        armPermit()
                    }
                } catch (failure: Exception) {
                    if (!hadComponent && !toggle.setEnabled(false)) {
                        CompanionAccessibilityService.setExplicitlyOff(context, true)
                        CompanionAccessibilityService.requestOff(context)
                    }
                    throw failure
                }
            }
            if (!args.optBoolean("direct_only", false) && screenReady()) directOnlyHolders.remove(acquired.id)
            if (starting && shizuku.status.value.bound) {
                val nextSession = shizuku.beginSession()
                lease.require(owner, acquired.id)
                privilegedSession = nextSession
            }
            activeClientId = client.id
            controllerLabel = client.label
            observation = null
            refresh("Controller connected. Stop at any time.")
            check(pairingBoundary.epoch == pairingEpoch && !pairingBoundary.isProtected) { "pairing_active" }
            lease.require(owner, acquired.id)
            check(tokens.clients().any { it.id == client.id }) { "unauthorized" }
            return status().also { it.getJSONObject("result").put("lease_id", acquired.id)
                .put("lanes", if (acquired.id in directOnlyHolders) "l0" else it.getJSONObject("result").getString("lanes"))
                .put("idle_timeout_seconds", ((acquired.deadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)) }
            } catch (failure: Exception) {
                acquired?.let { holder -> runCatching {
                    val last = synchronized(holderClassification) {
                        lease.release(owner, holder.id).also { directOnlyHolders.remove(holder.id) }
                    }
                    if (last) disarmControl()
                } }
                return failure(failure)
            } finally { commands.unlock() }
        }
        if (action == "device.arm.renew") return try {
            lease.renew(owner, request.getString("lease_id"), args.optInt("duration_seconds", 3600))
            check(pairingBoundary.epoch == pairingEpoch && !pairingBoundary.isProtected) { "pairing_active" }
            check(tokens.clients().any { it.id == client.id }) { "unauthorized" }
            status()
        } catch (failure: Exception) { failure(failure) }
        if (!commands.tryLock()) return error("controller_busy", "Another action is still running")
        try {
            val permit = lease.require(owner, request.optString("lease_id"))
            fun executionPermit(allowPrivate: Boolean = false) {
                lease.require(owner, request.optString("lease_id"))
                check(lease.currentEpoch() == permit.epoch && tokens.clients().any { it.id == client.id }) { "disarmed" }
                check(pairingBoundary.epoch == pairingEpoch && !pairingBoundary.isProtected) { "pairing_active" }
                if (!allowPrivate) check(!DevicePrivacy.active && DevicePrivacy.epoch.get() == privacyEpoch) { "privacy_active" }
                if (approvedPermissions != MobilePermissions.signature(context)) { disarmControl(); error("permissions_changed") }
            }
            executionPermit()
            val permissionRequest = LOCAL_PERMISSION_REQUESTS[action]?.takeUnless {
                action == "device.accessibility.enable" && CompanionAccessibilityService.canWriteSecureSettings(context)
            } ?: if (action == "device.settings.open")
                LOCAL_PERMISSION_PAGES[args.optString("page").trim().lowercase()] else null
            if (permissionRequest != null) {
                if (action == "device.settings.open" && state.value.target == "agent" && args.opt("allow_primary_handoff") != true)
                    return error("primary_handoff_required", "This permission setup opens Hands on the physical phone. Explicitly allow a primary handoff first.")
                disarmControl()
                context.startActivity(android.content.Intent(context, MainActivity::class.java)
                    .putExtra("hands_page", "access").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return ok(JSONObject().put("requested", true).put("local_approval_required", true)
                    .put("permission", permissionRequest).put("armed", false))
            }
            if ((action in SCREEN_ACTIONS || action == "device.agent_screen.start" || action == "device.secure_entry") && permit.id in directOnlyHolders)
                return error("a11y_unavailable", "This holder is approved for direct APIs only; screen control is disabled")
            // Token revocation, expiry and DISARM are rechecked before publishing any result.
            check(tokens.clients().any { it.id == client.id }) { "unauthorized" }
            val notificationVersion = notifications.state.value.consentVersion
            val result = when {
                action == "device.agent_screen.start" -> {
                    check(state.value.target == "agent") { "target_mismatch" }
                    val width = args.optInt("width", 720); val height = args.optInt("height", 1280); val density = args.optInt("density", 240)
                    require(width in 320..1600 && height in 320..2400 && density in 120..480 && width.toLong() * height <= 2_560_000)
                    val id = checkNotNull(screenHost) { "service_unavailable" }.start(width, height, density)
                    screenOpened(id)
                    ok(JSONObject().put("display_id", id).put("width", width).put("height", height).put("density", density)
                        .put("target", "agent").put("active", true).put("available", true))
                }
                action == "device.agent_screen.stop" -> { disarm(); return status() }
                action == "device.secure_entry" -> ok(secureEntry.command(owner, args) { executionPermit(allowPrivate = true) })
                action in HandsNotifications.ACTIONS -> checkNotNull(notifications.command(client.id, action, args) { executionPermit() })
                action in HandsSecureSettingsTools.ACTIONS -> checkNotNull(secureSettingsTools.execute(action, args) { executionPermit() })
                action in HandsDeviceTools.ACTIONS -> checkNotNull(deviceTools.execute(action, args) { executionPermit() })
                action in SCREEN_ACTIONS -> screenCommand(action, args) { executionPermit() }
                else -> directCommand(action, args)
            }
            if (state.value.target == "agent" && result.optJSONObject("error")?.optString("code") in setOf("invalid_display", "agent_screen_unavailable", "shizuku_unavailable")) {
                disarmControl()
                return result
            }
            lease.require(owner, request.optString("lease_id"))
            check(lease.currentEpoch() == permit.epoch && pairingBoundary.epoch == pairingEpoch &&
                !pairingBoundary.isProtected && tokens.clients().any { it.id == client.id }) { "disarmed" }
            if (action != "device.secure_entry") readPermit()
            if (action in HandsNotifications.ACTIONS) {
                val expectedVersion = if (result.optBoolean("ok"))
                    result.optJSONObject("result")?.optLong("consent_version", notificationVersion) ?: notificationVersion
                    else notificationVersion
                check(notifications.state.value.consentVersion == expectedVersion) { "notification_scope_changed" }
            }
            refresh()
            return result
        } catch (failure: Exception) { return failure(failure) }
        finally { commands.unlock() }
    }

    internal fun pauseForPrivateEntry() {
        observation = null
        privilegedSession = 0L
        shizuku.cancelAsync()
        notifications.refresh()
        message("Private entry active. Remote access is paused until you finish on this phone.")
    }

    internal fun capturePrivateTarget(pkg: String, ref: String, snapshotId: String): PrivateFieldTarget {
        check(!DeviceLanePolicy.isProtected(pkg, state.value.protectedPackages + context.packageName)) { "protected_package" }
        val st = state.value
        val display = if (st.target == "agent") st.displayId.takeIf { it > 0 } ?: error("agent_screen_unavailable") else 0
        val privileged = display != 0 || st.backend == "shizuku" || (st.backend == "hybrid" && !accessibilityReady())
        if (privileged) {
            val handle = shizuku.captureSecureTarget(display, ref, snapshotId, pkg, privilegedSession)
            return object : PrivateFieldTarget {
                override fun fill(value: String) = shizuku.fillSecureTarget(handle, value)
                override fun close() { cleanup.launch { runCatching { shizuku.releaseSecureTarget(handle) } } }
            }
        }
        check(accessibilityReady()) { "a11y_unavailable" }
        val captured = CompanionAccessibilityService.instance?.captureSecureTarget(ref, pkg, snapshotId) ?: error("stale_ref")
        if (!accessibilityReady()) { captured.close(); error("a11y_unavailable") }
        return object : PrivateFieldTarget {
            private var invalidated = false
            override fun fill(value: String): Boolean {
                if (invalidated || !accessibilityReady()) { invalidated = true; return false }
                val filled = captured.fill(value)
                if (!accessibilityReady()) invalidated = true
                // A successful platform fill may not be retried after a later OFF.
                return filled
            }
            override fun close() = captured.close()
        }
    }

    private fun screenCommand(action: String, args: JSONObject, checkAllowed: () -> Unit): JSONObject {
        val st = state.value
        val agent = st.target == "agent"
        val display = if (agent) st.displayId.takeIf { it > 0 } ?: throw IllegalStateException("agent_screen_unavailable") else 0
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw IllegalStateException("screen_locked")
        val privileged = agent || st.backend == "shizuku" || (st.backend == "hybrid" && !accessibilityReady())
        val target = args.optString("package")
        val protected = st.protectedPackages + context.packageName
        check(!DeviceLanePolicy.isProtected(target, protected)) { "protected_package" }
        if (privileged) {
            check(shizuku.status.value.bound) { "shizuku_unavailable" }
            val foreground = shizuku.foregroundPackage(display)
            if (action != "device.open_app" && action != "device.apps") {
                check(!foreground.isNullOrBlank()) { "screen_unavailable" }
                check(!DeviceLanePolicy.isProtected(foreground, protected)) { "protected_package" }
            }
            checkAllowed()
            val session = privilegedSession
            check(session > 0L) { "disarmed" }
            return shizuku.execute(action, JSONObject(args.toString()).put("_hands_session", session), display)
        }
        check(!agent) { "target_mismatch" }
        fun accessibilityPermit() { checkAllowed(); check(accessibilityReady()) { "a11y_unavailable" } }
        accessibilityPermit()
        val service = checkNotNull(CompanionAccessibilityService.instance) { "a11y_unavailable" }
        val hasRef = args.optString("ref").isNotBlank() || args.optString("from_ref").isNotBlank() || args.optString("to_ref").isNotBlank()
        val current = if (!hasRef && action !in setOf("device.open_app", "device.apps", "device.screenshot")) service.snapshot() else null
        if (current != null) check(!DeviceLanePolicy.isProtected(current.app, protected)) { "protected_package" }
        fun snapshot(fresh: Boolean = false): JSONObject {
            accessibilityPermit()
            val view = if (fresh) service.snapshot() else current ?: service.snapshot()
            accessibilityPermit()
            check(!DeviceLanePolicy.isProtected(view.app, protected)) { "protected_package" }
            observation = view
            val (w, h) = service.displaySize()
            return JSONObject().put("app", view.app).put("size", JSONObject().put("w", w).put("h", h))
                .put("nodes", JSONArray(view.nodes.map { node -> JSONObject().put("ref", node.ref).put("role", node.role)
                    .put("text", node.text).put("clickable", node.clickable).put("bounds", JSONArray(node.bounds)) }))
                .put("captured_at_ms", view.capturedAtMs).put("generation", view.generation).put("window_id", view.windowId)
                .put("snapshot_id", view.snapshotId).put("display_id", 0)
        }
        fun node(ref: String): app.hermes.companion.model.SnapshotNode {
            val old = checkNotNull(observation) { "stale_ref" }
            check(!DeviceLanePolicy.isProtected(old.app, protected)) { "protected_package" }
            check(args.optString("snapshot_id") == old.snapshotId && service.validateObservation(old.windowId, old.generation)) { "stale_ref" }
            return checkNotNull(old.nodes.find { it.ref == ref.removePrefix("@") }) { "stale_ref" }
        }
        fun center(ref: String): Pair<Float, Float> = node(ref).bounds.let { b -> (b[0] + b[2]) / 2f to (b[1] + b[3]) / 2f }
        if (action == "device.snapshot") return ok(snapshot())
        if (action == "device.screenshot") {
            accessibilityPermit()
            observation = null
            val shot = checkNotNull(service.screenshotPng(args.optInt("max_edge", 1080).coerceIn(64, 1080))) { "screen_unavailable" }
            accessibilityPermit()
            check(!DeviceLanePolicy.isProtected(shot.app, protected)) { "protected_package" }
            val (w, h) = service.displaySize()
            return ok(JSONObject().put("mime", "image/png").put("png_b64", Base64.encodeToString(shot.bytes, Base64.NO_WRAP))
                .put("w", shot.width).put("h", shot.height).put("app", shot.app).put("display_id", 0)
                .put("window_bounds", JSONArray(shot.bounds)).put("coordinate_space", shot.coordinateSpace)
                .put("screen_size", JSONObject().put("w", w).put("h", h)))
        }
        if (action == "device.apps") {
            accessibilityPermit()
            val apps = service.apps().map { (pkg, label) -> JSONObject().put("package", pkg).put("label", label) }
            accessibilityPermit()
            return ok(JSONObject().put("apps", JSONArray(apps)))
        }
        accessibilityPermit()
        val success = when (action) {
            "device.click" -> {
                val ref = args.optString("ref")
                if (ref.isNotBlank()) {
                    service.click(node(ref))
                } else {
                    val (w, h) = service.displaySize()
                    val x = coordinate(args, "x", w); val y = coordinate(args, "y", h)
                    service.clickAt(x, y)
                }
            }
            "device.swipe" -> {
                val (w, h) = service.displaySize()
                val from = if (args.has("from_ref")) center(args.getString("from_ref")) else if (args.has("dx") || args.has("dy")) w / 2f to h / 2f
                    else coordinate(args, "x1", w) to coordinate(args, "y1", h)
                val to = if (args.has("to_ref")) center(args.getString("to_ref")) else if (args.has("dx") || args.has("dy"))
                    (from.first + args.optDouble("dx", 0.0).toFloat()).coerceIn(0f, w - 1f) to (from.second + args.optDouble("dy", 0.0).toFloat()).coerceIn(0f, h - 1f)
                    else coordinate(args, "x2", w) to coordinate(args, "y2", h)
                require(listOf(from.first, from.second, to.first, to.second).all { it.isFinite() })
                service.swipe(SwipeSpec(from.first, from.second, to.first, to.second, args.optLong("duration_ms", 300).also { require(it in 50..2000) }))
            }
            "device.scroll" -> {
                val (w, h) = service.displaySize()
                val center = if (args.has("ref")) center(args.getString("ref")) else w / 2f to h / 2f
                val cx = center.first; val cy = center.second
                val dx = w * .2f; val dy = h * .2f
                val points = when (args.getString("direction")) {
                    "down" -> listOf(cx, cy + dy, cx, cy - dy)
                    "up" -> listOf(cx, cy - dy, cx, cy + dy)
                    "left" -> listOf(cx - dx, cy, cx + dx, cy)
                    "right" -> listOf(cx + dx, cy, cx - dx, cy)
                    else -> throw IllegalArgumentException("invalid_direction")
                }
                service.swipe(SwipeSpec(points[0].coerceIn(0f, w - 1f), points[1].coerceIn(0f, h - 1f), points[2].coerceIn(0f, w - 1f), points[3].coerceIn(0f, h - 1f), 350))
            }
            "device.type" -> service.type(args.getString("text").also { require(it.length <= 16000) })
            "device.press" -> service.press(args.getString("key"))
            "device.open_app" -> service.openApp(target.also { require(it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) })
            else -> false
        }
        accessibilityPermit()
        observation = null
        if (!success) return error("action_failed", "Android did not complete the requested action")
        return ok().also { reply ->
            runCatching { reply.getJSONObject("result").put("snapshot", snapshot(fresh = true)) }
                .onFailure { reply.getJSONObject("result").put("observation", JSONObject().put("ok", false).put("error", JSONObject().put("code", "observation_unavailable"))) }
        }
    }

    private fun directCommand(action: String, args: JSONObject): JSONObject {
        val value: JSONObject = when (action) {
            "device.device.info" -> JSONObject(DirectToolbox.deviceInfo(context))
            "device.audio.volume.get" -> JSONObject().put("streams", JSONArray(DirectToolbox.audioVolumeGet(context, args.string("stream"))))
            "device.audio.volume.set" -> JSONObject(checkNotNull(DirectToolbox.audioVolumeSet(context, args.getString("stream"), args.int("level"), args.int("step"))) { "invalid_stream" })
            "device.audio.ringer" -> JSONObject().put("mode", args.string("mode")?.let { DirectToolbox.ringerSet(context, it) } ?: DirectToolbox.ringerGet(context))
            "device.media.sessions" -> JSONObject().put("sessions", JSONArray(DirectToolbox.mediaSessions(context)))
            "device.media.now_playing" -> JSONObject(checkNotNull(DirectToolbox.mediaNowPlaying(context, args.getString("session_id"))) { "no_session" })
            "device.media.control" -> JSONObject(DirectToolbox.mediaControl(context, args.getString("session_id"), args.getString("action"), args.long("position_ms")))
            "device.clipboard.set" -> JSONObject().put("written", DirectToolbox.clipboardSet(context, args.getString("text")))
            "device.clipboard.get" -> when (val clip = DirectToolbox.clipboardGet(context)) {
                is DirectToolbox.ClipRead.Text -> JSONObject().put("text", clip.text)
                is DirectToolbox.ClipRead.Empty -> JSONObject().put("text", "")
                is DirectToolbox.ClipRead.NotText -> return error("clipboard_not_text", "The clipboard does not contain text")
                is DirectToolbox.ClipRead.Blocked -> return error("clipboard_read_blocked", "Android does not permit this background clipboard read")
            }
            "device.notifications.nls" -> JSONObject(DirectToolbox.nlsStatus(context))
            "device.display.brightness.get" -> JSONObject(DisplayToolbox.brightnessGet(context))
            "device.display.brightness.set" -> JSONObject(DisplayToolbox.brightnessSet(context, args.int("level"), if (args.has("auto")) args.getBoolean("auto") else null))
            "device.power.battery", "device.battery" -> JSONObject(SystemToolbox.battery(context))
            "device.power.state" -> JSONObject(SystemToolbox.powerState(context))
            "device.power.thermal", "device.thermal" -> JSONObject(SystemToolbox.thermal(context))
            "device.wait" -> { val ms = args.optLong("ms", 250); require(ms in 0..5000); Thread.sleep(ms); JSONObject().put("ms", ms) }
            else -> return error("unsupported_action", "This Hands version does not expose $action; no fallback was performed")
        }
        return ok(value)
    }

    private fun failure(t: Exception): JSONObject {
        val code = if (t is DeviceObservationException) t.code else when (t) {
            is SecurityException -> "permission_denied"
            is IllegalArgumentException -> if (t.message == "local_consent_required") "local_consent_required" else "bad_args"
            else -> t.message?.takeIf { it.matches(Regex("[a-z_]{3,50}")) } ?: "action_failed"
        }
        val message = when (code) {
            "local_consent_required" -> "Use the screen and backend selected in Hands."
            "disarmed" -> "Start the Hands service and arm a new controller lease."
            "controller_busy" -> "Another controller owns the current session"
            "agent_screen_unavailable" -> "Start the separate agent screen; actions will not fall back to the main screen"
            "screen_locked" -> "Unlock the phone before continuing"
            else -> "The operation was refused ($code). Check Hands on the phone."
        }
        return error(code, message)
    }

    private fun coordinate(args: JSONObject, key: String, bound: Int): Float = args.getDouble(key).also { require(it.isFinite() && it >= 0 && it < bound) }.toFloat()
    private fun isLocked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun isScreenOn() = context.getSystemService(android.os.PowerManager::class.java).isInteractive
    private fun keepAwakeStatus(): JSONObject = JSONObject().put("enabled", physicalHold.isHeld || standaloneHold.isHeld)
        .put("armed_hold", physicalHold.isHeld).put("standalone", standaloneHold.isHeld)
        .put("remaining_seconds", ((standaloneDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000))
        .put("screen_on", isScreenOn()).put("screen_locked", isLocked())
    private fun keepAwake(args: JSONObject): JSONObject {
        require(args.optString("action", "status") in setOf("status", "set"))
        if (args.optString("action", "status") == "set") {
            require(args.has("enabled"))
            if (args.getBoolean("enabled")) {
                check(!isLocked() && isScreenOn()) { "screen_locked" }
                val seconds = args.optInt("duration_seconds", 3600).also { require(it in 30..7200) }
                standaloneHold.beginSession()
                standaloneDeadline = SystemClock.elapsedRealtime() + seconds * 1000L
                standaloneHold.renew(seconds * 1000L)
            } else { standaloneDeadline = 0; standaloneHold.release() }
        }
        return ok(keepAwakeStatus())
    }
    private fun JSONObject.string(key: String): String? = if (has(key)) getString(key) else null
    private fun JSONObject.int(key: String): Int? = if (has(key)) getInt(key) else null
    private fun JSONObject.long(key: String): Long? = if (has(key)) getLong(key) else null

    companion object {
        private val LOCAL_PERMISSION_REQUESTS = mapOf("device.files.enable" to "files",
            "device.notifications.nls.enable" to "notifications", "device.notifications.enable" to "notifications",
            "device.accessibility.enable" to "accessibility")
        private val LOCAL_PERMISSION_PAGES = mapOf("accessibility" to "accessibility", "notification_listener" to "notifications",
            "dnd_access" to "dnd", "usage_access" to "usage", "write_settings" to "brightness",
            "app_details" to "app_settings", "battery_optimization" to "app_settings")
        private val SCREEN_ACTIONS = setOf("device.snapshot", "device.screenshot", "device.click", "device.swipe", "device.scroll", "device.type", "device.press", "device.open_app", "device.apps")
        private val PASSIVE_ACTIONS = setOf("device.display.brightness.get", "device.device.info", "device.audio.volume.get",
            "device.media.sessions", "device.media.now_playing", "device.notifications.nls", "device.power.battery", "device.battery",
            "device.power.state", "device.power.thermal", "device.thermal")
        val CAPABILITIES = SCREEN_ACTIONS + HandsDeviceTools.ACTIONS + HandsNotifications.ACTIONS + HandsSecureSettingsTools.ACTIONS + setOf("device.secure_entry", "device.permissions", "device.status", "device.arm", "device.arm.renew", "device.disarm",
            "device.agent_screen.start", "device.agent_screen.status", "device.agent_screen.stop", "device.device.info",
            "device.audio.volume.get", "device.audio.volume.set", "device.audio.ringer", "device.media.sessions",
            "device.media.now_playing", "device.media.control", "device.clipboard.get", "device.clipboard.set",
            "device.notifications.nls", "device.display.brightness.get", "device.display.brightness.set",
            "device.power.battery", "device.battery", "device.power.state", "device.power.thermal", "device.thermal", "device.wait", "device.display.keep_awake")
    }
}
