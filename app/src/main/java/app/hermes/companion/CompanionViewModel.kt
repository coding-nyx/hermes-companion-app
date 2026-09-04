package app.hermes.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.OutboxStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.local.TranscriptCache
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.device.HandsBridge
import android.util.Base64
import app.hermes.companion.domain.AuthPolicy
import app.hermes.companion.domain.CompactTree
import app.hermes.companion.domain.DeviceArming
import app.hermes.companion.domain.DeviceAudit
import app.hermes.companion.domain.DeviceAuditRow
import app.hermes.companion.domain.DeviceGestures
import app.hermes.companion.domain.DeviceLanePolicy
import app.hermes.companion.domain.GatewayHudMap
import app.hermes.companion.domain.HistoryPaging
import app.hermes.companion.domain.OutboxPolicy
import app.hermes.companion.domain.PairingPolicy
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.RewindPolicy
import app.hermes.companion.domain.RewindSubmit
import app.hermes.companion.domain.SendFate
import app.hermes.companion.domain.WakePing
import app.hermes.companion.domain.WakePolicy
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.DashboardStatus
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.DeviceCommand
import app.hermes.companion.model.DeviceCred
import app.hermes.companion.model.DeviceResult
import app.hermes.companion.model.GatewayHud
import app.hermes.companion.model.HudState
import app.hermes.companion.model.BusFrame
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.GatewayHello
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.OutboxItem
import app.hermes.companion.model.ScreenSafeArea
import app.hermes.companion.model.PairingPhase
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.SessionRef
import app.hermes.companion.model.SnapshotNode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

enum class MainTab { THREADS, PROFILES, GATEWAY, DEVICE }

data class CompanionState(
    val originInput: String = "",
    val origin: String? = null,
    val username: String = "",
    val password: String = "",
    val authRequired: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val profiles: List<ProfileRef> = emptyList(),
    val activeProfileId: String? = null,
    val sessions: List<SessionRef> = emptyList(),
    val tab: MainTab = MainTab.THREADS,
    val openSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val streaming: Boolean = false,
    val approval: ApprovalPrompt? = null,
    val historyHasMore: Boolean = false,
    val historyLoading: Boolean = false,
    val rewindTargetId: String? = null,
    val gatewayHello: GatewayHello? = null,
    val status: DashboardStatus? = null,
    val hud: GatewayHud = GatewayHud(),
    val pairingPhase: PairingPhase = PairingPhase.IDLE,
    val pairingCode: String = "",
    val deviceId: String? = null,
    val deviceProfileId: String? = null,
    val deviceLane: Boolean = false,
    val a11yBound: Boolean = false,
    val overlayGranted: Boolean = false,
    val notifyGranted: Boolean = false,
    val foregroundApp: String = "",
    val lastAudit: String = "",
    val arm: DeviceArm = DeviceArm.DISARMED,
    val ntfyTopic: String = "",
    val stayConnected: Boolean = false,
    val lastWake: WakePing? = null,
) {
    val visibleSessions: List<SessionRef>
        get() = ProfileScope.visibleSessions(sessions, activeProfileId)
    val activeProfile: ProfileRef?
        get() = profiles.find { it.id == activeProfileId }
    val openSession: SessionRef?
        get() = sessions.find { it.id == openSessionId }
}

class CompanionViewModel(
    private val client: DashboardClient,
    private val sticky: StickyStore,
    private val cache: TranscriptCache,
    private val outbox: OutboxStore,
    private val deviceCreds: DeviceCredStore,
    private val runtime: CompanionApp,
) : ViewModel() {
    private val liveScope: CoroutineScope get() = runtime.appScope
    private val _state = MutableStateFlow(
        CompanionState(
            originInput = sticky.origin.orEmpty(),
            ntfyTopic = sticky.ntfyTopic.orEmpty(),
            stayConnected = sticky.stayConnected,
        ).withCred(deviceCreds.load(), sticky.origin),
    )
    val state: StateFlow<CompanionState> = _state
    private var turnJob: Job? = null
    private var retryJob: Job? = null
    private var pairJob: Job? = null
    private var idleJob: Job? = null
    private var pendingWake: WakePing? = null
    private val outboxMutex = Mutex()
    private var lastRefs: Set<String> = emptySet()
    private var lastNodes: List<SnapshotNode> = emptyList()
    private val rateHits = mutableListOf<Long>()
    private var auditRows: List<DeviceAuditRow> = emptyList()

    init {
        HandsBridge.onDisarm = { disarm() }
    }

    fun onNtfyTopicChange(value: String) {
        _state.update { it.copy(ntfyTopic = value, error = null) }
    }

    fun saveNtfy() {
        sticky.ntfyTopic = _state.value.ntfyTopic.trim().ifBlank { null }
        startWake()
    }

    fun toggleStayConnected() {
        val next = !_state.value.stayConnected
        sticky.stayConnected = next
        _state.update { it.copy(stayConnected = next) }
    }

    fun openWake(profileId: String, sessionId: String) {
        val ping = WakePing(type = "approval.request", sessionId = sessionId, profile = profileId)
        val origin = _state.value.origin
        if (origin.isNullOrBlank()) {
            pendingWake = ping
            return
        }
        viewModelScope.launch {
            if (_state.value.activeProfileId != profileId) {
                sticky.profileId = profileId
                val sessions = runCatching {
                    cache.readSessions(origin, profileId) { client.listSessions(origin, profileId) }
                }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        activeProfileId = profileId,
                        sessions = sessions,
                        tab = MainTab.THREADS,
                    )
                }
                startWatch(origin, profileId)
            }
            val session = _state.value.sessions.find { it.id == sessionId }
                ?: SessionRef(
                    id = sessionId,
                    profileId = profileId,
                    title = sessionId,
                    updatedAtEpochMs = 0L,
                    unread = true,
                )
            openSession(session)
        }
    }

    fun onOriginChange(value: String) {
        _state.update { it.copy(originInput = value, error = null) }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun selectTab(tab: MainTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun onDraftChange(value: String) {
        _state.update { it.copy(draft = value) }
    }

    fun startPair() {
        val origin = _state.value.origin ?: return
        val profile = _state.value.activeProfileId ?: return
        if (_state.value.pairingPhase == PairingPhase.WAITING) return
        val code = PairingPolicy.issue()
        pairJob?.cancel()
        pairJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            _state.update {
                it.copy(pairingPhase = PairingPhase.WAITING, pairingCode = code, error = null)
            }
            try {
                val offered = client.offerPair(origin, code, profile)
                var status = offered
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
                                error = null,
                            )
                        }
                        startDeviceLane()
                        return@launch
                    }
                    if (PairingPolicy.expired(started)) {
                        _state.update {
                            it.copy(pairingPhase = PairingPhase.IDLE, pairingCode = "", error = "code expired")
                        }
                        return@launch
                    }
                    delay(PairingPolicy.POLL_MS)
                    status = runCatching { client.pollPair(origin, code) }.getOrNull() ?: status
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        pairingPhase = PairingPhase.IDLE,
                        pairingCode = "",
                        error = e.message ?: "pair failed",
                    )
                }
            }
        }
    }

    fun cancelPair() {
        pairJob?.cancel()
        pairJob = null
        _state.update { it.copy(pairingPhase = PairingPhase.IDLE, pairingCode = "", error = null) }
    }

    fun revokePair() {
        pairJob?.cancel()
        pairJob = null
        val origin = _state.value.origin
        val id = _state.value.deviceId
        viewModelScope.launch {
            if (!origin.isNullOrBlank() && !id.isNullOrBlank()) {
                runCatching { client.revokeDevice(origin, id) }
            }
            deviceCreds.clear()
            stopDeviceLane()
            _state.update {
                it.copy(
                    pairingPhase = PairingPhase.IDLE,
                    pairingCode = "",
                    deviceId = null,
                    deviceProfileId = null,
                    deviceLane = false,
                    arm = DeviceArm.DISARMED,
                    error = null,
                )
            }
        }
    }

    fun setA11yBound(bound: Boolean) {
        val on = bound || CompanionAccessibilityService.bound
        _state.update { st ->
            val arm = if (!on) DeviceArming.disarm() else st.arm
            st.copy(
                a11yBound = on,
                arm = arm,
                foregroundApp = CompanionAccessibilityService.foregroundApp,
            )
        }
        if (!on) idleJob?.cancel()
    }

    fun setOverlayGranted(granted: Boolean) {
        _state.update { it.copy(overlayGranted = granted) }
    }

    fun setNotifyGranted(granted: Boolean) {
        _state.update { it.copy(notifyGranted = granted) }
    }

    fun arm() {
        val next = DeviceArming.arm(_state.value.arm, _state.value.a11yBound)
        _state.update { it.copy(arm = next) }
        if (next != DeviceArm.DISARMED) bumpIdle()
    }

    fun disarm() {
        idleJob?.cancel()
        _state.update { it.copy(arm = DeviceArming.disarm()) }
    }

    private fun bumpIdle() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(DeviceLanePolicy.IDLE_DISARM_MS)
            disarm()
        }
    }

    private fun startDeviceLane() {
        val origin = _state.value.origin ?: return
        val cred = deviceCreds.load() ?: return
        if (cred.origin.isNotBlank() && origin.isNotBlank() && cred.origin != origin) return
        runtime.deviceLaneJob?.cancel()
        runtime.deviceLaneJob = liveScope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    client.openDeviceLane(origin, cred)
                    _state.update { it.copy(deviceLane = true) }
                    backoff = 1_000L
                    client.deviceCommands().collect { handleDeviceCommand(it) }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    _state.update { it.copy(deviceLane = false) }
                }
                client.closeDeviceLane()
                _state.update { it.copy(deviceLane = false) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun stopDeviceLane() {
        runtime.deviceLaneJob?.cancel()
        runtime.deviceLaneJob = null
        client.closeDeviceLane()
        lastRefs = emptySet()
        lastNodes = emptyList()
    }

    private suspend fun handleDeviceCommand(command: DeviceCommand) {
        val st = _state.value
        val args = command.argumentsJson
        val ref = jsonField(args, "ref") ?: jsonField(args, "from_ref")
        val target = jsonField(args, "package")
        val fg = CompanionAccessibilityService.foregroundApp
        if (!DeviceGestures.allowRate(System.currentTimeMillis(), rateHits)) {
            noteAudit(command.action, fg, false, "rate_limited")
            client.replyDevice(DeviceLanePolicy.fail(command.commandId, "rate_limited"))
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
        )
        if (code != null) {
            noteAudit(command.action, target ?: fg, false, code)
            client.replyDevice(DeviceLanePolicy.fail(command.commandId, code))
            return
        }
        bumpIdle()
        val exec = DeviceArming.beginExec(st.arm)
        if (exec != st.arm) _state.update { it.copy(arm = exec, foregroundApp = fg) }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { executeDevice(command) }.getOrElse { t ->
                DeviceLanePolicy.fail(command.commandId, "timeout", t.message ?: "command failed")
            }
        }
        noteAudit(command.action, target ?: fg, result.ok, result.errorCode)
        client.replyDevice(result)
        _state.update {
            it.copy(
                arm = DeviceArming.endExec(it.arm),
                foregroundApp = CompanionAccessibilityService.foregroundApp,
            )
        }
    }

    private suspend fun executeDevice(command: DeviceCommand): DeviceResult {
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
                val node = lastNodes.find { it.ref == id }
                val xy = jsonXy(args) ?: run {
                    val x = jsonNumber(args, "x")
                    val y = jsonNumber(args, "y")
                    if (x != null && y != null) x to y else null
                }
                val ok = when {
                    node != null -> service?.click(node) == true
                    xy != null -> service?.clickAt(xy.first, xy.second) == true
                    else -> false
                }
                if (ok) DeviceLanePolicy.ok(
                    command.commandId,
                    """{"clicked":${jsonQuote(id ?: "${xy?.first},${xy?.second}")}}""",
                )
                else DeviceLanePolicy.fail(command.commandId, "stale_ref")
            }
            "device.type" -> {
                val text = jsonField(args, "text").orEmpty()
                val ok = service?.type(text) == true
                if (ok) DeviceLanePolicy.ok(command.commandId)
                else DeviceLanePolicy.fail(command.commandId, "a11y_unavailable", "no focused field")
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

    private fun swipeSpec(
        args: String,
        service: CompanionAccessibilityService?,
    ): app.hermes.companion.domain.SwipeSpec? {
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

    fun connect(originOverride: String? = null) {
        val origin = (originOverride ?: _state.value.originInput).trim().trimEnd('/')
        if (origin.isBlank()) {
            _state.update { it.copy(error = "origin required") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, originInput = origin) }
            var gatedHost = _state.value.authRequired
            try {
                val status = client.probe(origin)
                val hud = GatewayHudMap.from(status)
                gatedHost = status.authRequired
                _state.update { it.copy(authRequired = status.authRequired, status = status) }
                if (status.authRequired) {
                    val provider = AuthPolicy.passwordProvider(status.authProviders)
                        ?: throw DashboardException("auth_oidc", AuthPolicy.OIDC_MESSAGE)
                    val user = _state.value.username.trim()
                    val pass = _state.value.password
                    if (user.isBlank() || pass.isBlank()) {
                        _state.update {
                            it.copy(loading = false, error = "username and password required")
                        }
                        return@launch
                    }
                    client.passwordLogin(origin, user, pass, provider)
                    _state.update { it.copy(password = "") }
                } else {
                    client.useLoopback()
                    client.adoptLoopbackToken(origin)
                }
                val profiles = client.listProfiles(origin)
                val active = ProfileScope.resolveActive(profiles, sticky.profileId)
                    ?: throw DashboardException("no_profiles", "no profiles on host")
                val hello = runCatching { client.wsHello(origin, active.id) }.getOrNull()
                sticky.origin = origin
                sticky.profileId = active.id
                val cached = runCatching { cache.sessions(origin, active.id) }.getOrDefault(emptyList())
                if (cached.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            loading = true,
                            origin = origin,
                            profiles = profiles,
                            activeProfileId = active.id,
                            sessions = cached,
                            tab = MainTab.THREADS,
                            gatewayHello = hello,
                            status = status,
                            hud = hud,
                        )
                    }
                }
                val sessions = cache.readSessions(origin, active.id) {
                    client.listSessions(origin, active.id)
                }
                _state.update {
                    it.copy(
                        loading = false,
                        origin = origin,
                        profiles = profiles,
                        activeProfileId = active.id,
                        sessions = sessions,
                        tab = MainTab.THREADS,
                        gatewayHello = hello,
                        status = status,
                        hud = hud,
                    ).withCred(deviceCreds.load(), origin)
                }
                startHud(origin)
                startWatch(origin, active.id)
                startDeviceLane()
                startWake()
                pendingWake?.let { openWake(it.profile, it.sessionId) }
                pendingWake = null
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        origin = null,
                        authRequired = gatedHost,
                        error = t.toMonoError(),
                    )
                }
            }
        }
    }

    fun selectProfile(profileId: String) {
        val origin = _state.value.origin ?: return
        if (profileId == _state.value.activeProfileId) return
        sticky.profileId = profileId
        val keepChat = _state.value.openSession?.takeIf { it.profileId == profileId }
        turnJob?.cancel()
        viewModelScope.launch {
            val cached = runCatching { cache.sessions(origin, profileId) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    activeProfileId = profileId,
                    sessions = cached,
                    openSessionId = keepChat?.id,
                    messages = if (keepChat == null) emptyList() else it.messages,
                    streaming = false,
                    rewindTargetId = null,
                )
            }
            startWatch(origin, profileId)
            try {
                val sessions = cache.readSessions(origin, profileId) {
                    client.listSessions(origin, profileId)
                }
                _state.update { state ->
                    if (state.activeProfileId != profileId) state
                    else state.copy(sessions = sessions, error = null)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.toMonoError()) }
            }
        }
    }

    fun openSession(session: SessionRef) {
        val origin = _state.value.origin ?: return
        val profile = _state.value.activeProfileId ?: return
        val owned = try {
            ProfileScope.requireOwnedSession(session, profile)
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.toMonoError()) }
            return
        }
        viewModelScope.launch {
            val cached = runCatching { cache.messages(origin, profile, owned.id) }.getOrDefault(emptyList())
            val cachedTail = withQueued(origin, profile, owned.id, HistoryPaging.tail(cached))
            _state.update {
                it.copy(
                    openSessionId = owned.id,
                    loading = true,
                    error = null,
                    draft = "",
                    approval = null,
                    rewindTargetId = null,
                    messages = cachedTail,
                    historyHasMore = cached.size > cachedTail.size,
                    historyLoading = false,
                )
            }
            try {
                val page = client.pageMessages(origin, owned.id, profile)
                runCatching { cache.replaceMessages(origin, profile, owned.id, page.messages) }
                val approval = runCatching { client.pendingApproval(origin, owned.id, profile) }.getOrNull()
                val merged = withQueued(origin, profile, owned.id, page.messages)
                _state.update { state ->
                    if (state.openSessionId != owned.id) state
                    else state.copy(
                        loading = false,
                        messages = merged,
                        approval = approval,
                        historyHasMore = page.hasMore,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.toMonoError()) }
            }
        }
    }

    fun closeChat() {
        turnJob?.cancel()
        _state.update {
            it.copy(
                openSessionId = null,
                messages = emptyList(),
                draft = "",
                streaming = false,
                approval = null,
                rewindTargetId = null,
                historyHasMore = false,
                historyLoading = false,
            )
        }
    }

    fun loadOlder() {
        val origin = _state.value.origin ?: return
        val session = _state.value.openSession ?: return
        val profile = _state.value.activeProfileId ?: return
        val st = _state.value
        if (st.historyLoading || !st.historyHasMore || st.messages.isEmpty()) return
        val before = st.messages.first().id
        viewModelScope.launch {
            _state.update { it.copy(historyLoading = true) }
            try {
                val page = client.pageMessages(origin, session.id, profile, beforeId = before)
                val seen = _state.value.messages.map { it.id }.toSet()
                val older = page.messages.filter { it.id !in seen }
                _state.update { state ->
                    if (state.openSessionId != session.id) state
                    else state.copy(
                        historyLoading = false,
                        messages = older + state.messages,
                        historyHasMore = page.hasMore && older.isNotEmpty(),
                    )
                }
                persistOpenMessages()
            } catch (t: Throwable) {
                _state.update { it.copy(historyLoading = false, historyHasMore = false, error = t.toMonoError()) }
            }
        }
    }

    fun respondApproval(decision: String) {
        val origin = _state.value.origin ?: return
        val session = _state.value.openSession ?: return
        val profile = _state.value.activeProfileId ?: return
        val prompt = _state.value.approval ?: return
        viewModelScope.launch {
            try {
                ProfileScope.requireOwnedSession(session, profile)
                client.respondPrompt(origin, session.id, profile, prompt, decision)
                val note = if (decision == "deny") "denied ${prompt.command}" else "allowed ${prompt.command}"
                _state.update {
                    it.copy(
                        approval = null,
                        messages = it.messages + ChatMessage(
                            id = "apr-${prompt.requestId}",
                            role = MessageRole.ASSISTANT,
                            text = note,
                        ),
                    )
                }
                persistOpenMessages()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.toMonoError()) }
            }
        }
    }

    fun newThread() {
        val origin = _state.value.origin ?: return
        val profile = _state.value.activeProfileId ?: return
        viewModelScope.launch {
            try {
                val created = client.createSession(origin, profile)
                runCatching { cache.upsertSession(origin, created) }
                _state.update { it.copy(sessions = listOf(created) + it.sessions, error = null) }
                openSession(created)
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.toMonoError()) }
            }
        }
    }

    fun beginRewind(message: ChatMessage) {
        if (_state.value.streaming) return
        if (!RewindPolicy.canTarget(message)) return
        _state.update {
            it.copy(rewindTargetId = message.id, draft = message.text, error = null)
        }
    }

    fun cancelRewind() {
        _state.update { it.copy(rewindTargetId = null) }
    }

    fun send() {
        val origin = _state.value.origin ?: return
        val session = _state.value.openSession ?: return
        val profile = _state.value.activeProfileId ?: return
        val text = _state.value.draft.trim()
        if (text.isBlank()) return
        try {
            ProfileScope.requireOwnedSession(session, profile)
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.toMonoError()) }
            return
        }
        val rewindId = _state.value.rewindTargetId
        if (rewindId != null) {
            sendRewind(origin, session, profile, rewindId, text)
            return
        }
        val user = ChatMessage(
            id = "u-${UUID.randomUUID()}",
            role = MessageRole.USER,
            text = text,
            queued = true,
        )
        viewModelScope.launch {
            outbox.enqueue(
                OutboxItem(
                    id = user.id,
                    origin = origin,
                    profileId = profile,
                    sessionId = session.id,
                    text = text,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            _state.update {
                it.copy(draft = "", error = null, messages = it.messages + user)
            }
            flushOutbox()
        }
    }

    private fun sendRewind(
        origin: String,
        session: SessionRef,
        profile: String,
        targetId: String,
        text: String,
    ) {
        val rowId = RewindPolicy.durableRowId(targetId)
        if (rowId == null) {
            _state.update { it.copy(error = "rewind needs a durable row", rewindTargetId = null) }
            return
        }
        if (_state.value.streaming) return
        val empty = !_state.value.historyHasMore && RewindPolicy.isFirstUserTurn(_state.value.messages, targetId)
        val spec = RewindSubmit(rowId = rowId, empty = empty)
        val kept = RewindPolicy.dropFrom(_state.value.messages, targetId)
        val user = ChatMessage(
            id = "u-${UUID.randomUUID()}",
            role = MessageRole.USER,
            text = text,
        )
        viewModelScope.launch {
            if (!outboxMutex.tryLock()) {
                _state.update { it.copy(error = "session busy") }
                return@launch
            }
            val assistantId = "a-${UUID.randomUUID()}"
            try {
                _state.update {
                    it.copy(
                        draft = "",
                        error = null,
                        rewindTargetId = null,
                        streaming = true,
                        messages = kept + user,
                    )
                }
                coroutineScope {
                    val job = launch {
                        client.streamTurn(origin, session.id, profile, text, spec).collect { event ->
                            if (_state.value.openSessionId == session.id) {
                                _state.update { applyEvent(it, event, assistantId) }
                            }
                        }
                    }
                    turnJob = job
                    job.join()
                }
                if (_state.value.openSessionId == session.id) {
                    _state.update { finishStream(it, assistantId) }
                    persistOpenMessages()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                runCatching { client.pageMessages(origin, session.id, profile) }.onSuccess { page ->
                    runCatching { cache.replaceMessages(origin, profile, session.id, page.messages) }
                    if (_state.value.openSessionId == session.id) {
                        _state.update {
                            finishStream(it, assistantId).copy(
                                messages = page.messages,
                                historyHasMore = page.hasMore,
                            )
                        }
                    }
                }
                _state.update { it.copy(streaming = false, error = t.toMonoError()) }
            } finally {
                outboxMutex.unlock()
            }
        }
    }

    fun interrupt() {
        turnJob?.cancel()
        val origin = _state.value.origin
        val session = _state.value.openSession
        val profile = _state.value.activeProfileId
        _state.update { it.copy(streaming = false, messages = it.messages.map { m -> m.copy(streaming = false) }) }
        if (origin == null || session == null || profile == null) return
        viewModelScope.launch {
            runCatching { client.interruptTurn(origin, session.id, profile) }
        }
    }

    override fun onCleared() {
        retryJob?.cancel()
        turnJob?.cancel()
        pairJob?.cancel()
        idleJob?.cancel()
        if (sticky.stayConnected || _state.value.arm != DeviceArm.DISARMED) {
            super.onCleared()
            return
        }
        HandsBridge.onDisarm = null
        stopDeviceLane()
        runtime.watchJob?.cancel()
        runtime.watchJob = null
        runtime.hudJob?.cancel()
        runtime.hudJob = null
        runtime.wakeJob?.cancel()
        runtime.wakeJob = null
        client.closeRpc()
        super.onCleared()
    }

    private suspend fun withQueued(
        origin: String,
        profileId: String,
        sessionId: String,
        messages: List<ChatMessage>,
    ): List<ChatMessage> {
        val pending = runCatching { outbox.pending(origin, profileId) }.getOrDefault(emptyList())
            .filter { it.sessionId == sessionId }
        val seen = messages.map { it.id }.toSet()
        return messages + pending.filter { it.id !in seen }.map {
            ChatMessage(id = it.id, role = MessageRole.USER, text = it.text, queued = true)
        }
    }

    private suspend fun flushOutbox() {
        if (!outboxMutex.tryLock()) return
        try {
            val origin = _state.value.origin ?: return
            val profile = _state.value.activeProfileId ?: return
            while (true) {
                val item = outbox.pending(origin, profile).firstOrNull() ?: return
                val open = _state.value.openSessionId == item.sessionId
                val assistantId = "a-${UUID.randomUUID()}"
                if (open) {
                    _state.update { st ->
                        st.copy(
                            streaming = true,
                            error = null,
                            messages = st.messages.map { m ->
                                if (m.id == item.id) m.copy(queued = false) else m
                            },
                        )
                    }
                }
                try {
                    var cancelled = false
                    coroutineScope {
                        val job = launch {
                            client.streamTurn(origin, item.sessionId, profile, item.text).collect { event ->
                                if (_state.value.openSessionId == item.sessionId) {
                                    _state.update { applyEvent(it, event, assistantId) }
                                }
                            }
                        }
                        turnJob = job
                        job.join()
                        cancelled = job.isCancelled
                    }
                    if (cancelled) {
                        if (open) _state.update { finishStream(it, assistantId) }
                        return
                    }
                    outbox.remove(item.id)
                    if (_state.value.openSessionId == item.sessionId) {
                        _state.update { finishStream(it, assistantId) }
                        persistOpenMessages()
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    val code = (t as? DashboardException)?.code.orEmpty()
                    val fate = OutboxPolicy.classify(code, "${t.message.orEmpty()} ${t.javaClass.simpleName}")
                    outbox.markAttempt(item.id, origin, profile, "$code ${t.message}")
                    val queued = _state.value.messages.map { m ->
                        if (m.id == item.id) m.copy(queued = true, streaming = false) else m
                    }
                    when (fate) {
                        SendFate.BUSY -> {
                            if (open) {
                                _state.update {
                                    finishStream(it, assistantId).copy(
                                        error = "session busy",
                                        messages = queued,
                                        streaming = false,
                                    )
                                }
                            }
                            val attempts = item.attempts + 1
                            if (!OutboxPolicy.giveUp(attempts)) {
                                retryJob?.cancel()
                                retryJob = viewModelScope.launch {
                                    delay(OutboxPolicy.backoffMs(attempts))
                                    flushOutbox()
                                }
                            }
                            return
                        }
                        SendFate.OFFLINE -> {
                            if (open) {
                                _state.update {
                                    finishStream(it, assistantId).copy(
                                        error = "queued",
                                        messages = queued,
                                        streaming = false,
                                    )
                                }
                            }
                            return
                        }
                        SendFate.FAILED -> {
                            if (open) {
                                _state.update {
                                    finishStream(it, assistantId).copy(
                                        error = t.toMonoError(),
                                        messages = queued,
                                        streaming = false,
                                    )
                                }
                            }
                            return
                        }
                    }
                }
            }
        } finally {
            outboxMutex.unlock()
        }
    }

    private fun startWake() {
        val sse = WakePolicy.sseUrl(_state.value.ntfyTopic.ifBlank { sticky.ntfyTopic.orEmpty() }) ?: return
        runtime.wakeJob?.cancel()
        runtime.wakeJob = liveScope.launch {
            client.wakeEvents(sse).collect { raw ->
                val ping = WakePolicy.parse(raw) ?: return@collect
                _state.update { it.copy(lastWake = ping) }
                openWake(ping.profile, ping.sessionId)
            }
        }
    }

    private fun startHud(origin: String) {
        runtime.hudJob?.cancel()
        runtime.hudJob = liveScope.launch {
            while (isActive) {
                delay(HUD_MS)
                val snap = runCatching { client.probe(origin) }.getOrNull()
                _state.update { st ->
                    if (st.origin != origin) st
                    else if (snap != null) st.copy(status = snap, hud = GatewayHudMap.from(snap))
                    else st.copy(hud = st.hud.copy(api = HudState.OFF))
                }
            }
        }
    }

    private fun startWatch(origin: String, profileId: String) {
        runtime.watchJob?.cancel()
        runtime.watchJob = liveScope.launch {
            var backoff = 1_000L
            var lastInstance = _state.value.gatewayHello?.instanceId.orEmpty()
            while (isActive) {
                try {
                    val hello = client.wsHello(origin, profileId)
                    if (lastInstance.isNotEmpty() && hello.instanceId.isNotEmpty() &&
                        hello.instanceId != lastInstance
                    ) {
                        client.forgetLiveIds()
                        runCatching {
                            cache.readSessions(origin, profileId) { client.listSessions(origin, profileId) }
                        }.onSuccess { rows ->
                            _state.update { st ->
                                if (st.activeProfileId == profileId) st.copy(sessions = rows) else st
                            }
                        }
                    }
                    lastInstance = hello.instanceId
                    _state.update { it.copy(gatewayHello = hello) }
                    backoff = 1_000L
                    runCatching { flushOutbox() }
                    val openId = _state.value.openSessionId
                    if (openId != null && !_state.value.streaming) {
                        runCatching { client.pageMessages(origin, openId, profileId) }.onSuccess { page ->
                            runCatching { cache.replaceMessages(origin, profileId, openId, page.messages) }
                            val merged = withQueued(origin, profileId, openId, page.messages)
                            _state.update { st ->
                                if (st.openSessionId != openId) st
                                else st.copy(messages = merged, historyHasMore = page.hasMore)
                            }
                        }
                    }
                    coroutineScope {
                        val ping = launch {
                            if (!hello.heartbeat) return@launch
                            while (isActive) {
                                delay(PING_MS)
                                runCatching { client.ping() }.onFailure {
                                    client.closeRpc()
                                }
                            }
                        }
                        try {
                            client.bus(profileId).collect { frame -> onBus(frame, origin, profileId) }
                        } finally {
                            ping.cancel()
                        }
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    _state.update { it.copy(gatewayHello = null) }
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun onBus(frame: BusFrame, origin: String, profileId: String) {
        when (frame) {
            BusFrame.SessionRefetch -> viewModelScope.launch {
                runCatching {
                    cache.readSessions(origin, profileId) { client.listSessions(origin, profileId) }
                }.onSuccess { rows ->
                    _state.update { st ->
                        if (st.activeProfileId == profileId) st.copy(sessions = rows) else st
                    }
                }
            }
            is BusFrame.SessionPatch -> {
                viewModelScope.launch { runCatching { cache.applyChange(origin, profileId, frame.change) } }
                _state.update { st ->
                    if (st.activeProfileId != profileId) st
                    else {
                        val next = ProfileScope.applyChange(st.sessions, frame.change, profileId)
                        val open = st.openSessionId
                        st.copy(
                            sessions = if (open == null) next else next.map { row ->
                                if (row.id == open) row.copy(unread = false) else row
                            },
                        )
                    }
                }
            }
            is BusFrame.Chat -> {
                val ev = frame.event
                val open = _state.value.openSessionId
                val forOpen = frame.sessionId == null || client.matchesSession(open, frame.sessionId)
                when (ev) {
                    is ChatEvent.PromptExpired -> _state.update { st ->
                        if (st.approval?.requestId == ev.requestId) st.copy(approval = null) else st
                    }
                    is ChatEvent.Approval -> {
                        if (forOpen && open != null) {
                            _state.update { it.copy(approval = ev.prompt, streaming = false) }
                        } else if (frame.sessionId != null) {
                            _state.update { st ->
                                st.copy(
                                    sessions = st.sessions.map { row ->
                                        if (client.matchesSession(row.id, frame.sessionId)) {
                                            row.copy(unread = true)
                                        } else row
                                    },
                                )
                            }
                        }
                    }
                    else -> {
                        if (!forOpen || open == null) return
                        if (_state.value.streaming && ev !is ChatEvent.Approval) return
                        val aid = "live-$open"
                        _state.update { applyEvent(it, ev, aid) }
                    }
                }
            }
        }
    }

    private suspend fun persistOpenMessages() {
        val st = _state.value
        val origin = st.origin ?: return
        val profile = st.activeProfileId ?: return
        val sessionId = st.openSessionId ?: return
        runCatching { cache.replaceMessages(origin, profile, sessionId, st.messages) }
    }

    companion object {
        private const val PING_MS = 15_000L
        private const val HUD_MS = 15_000L

        fun factory(app: CompanionApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CompanionViewModel(
                        app.dashboard,
                        app.sticky,
                        app.cache,
                        app.outbox,
                        app.deviceCreds,
                        app,
                    ) as T
                }
            }
    }
}

private fun CompanionState.withCred(cred: DeviceCred?, origin: String?): CompanionState {
    if (cred == null) {
        return copy(
            pairingPhase = PairingPhase.IDLE,
            pairingCode = "",
            deviceId = null,
            deviceProfileId = null,
        )
    }
    if (!cred.origin.isBlank() && !origin.isNullOrBlank() && cred.origin != origin) {
        return copy(
            pairingPhase = PairingPhase.IDLE,
            pairingCode = "",
            deviceId = null,
            deviceProfileId = null,
        )
    }
    return copy(
        pairingPhase = PairingPhase.PAIRED,
        pairingCode = "",
        deviceId = cred.deviceId,
        deviceProfileId = cred.profileId,
    )
}

private fun applyEvent(state: CompanionState, event: ChatEvent, assistantId: String): CompanionState =
    when (event) {
        is ChatEvent.AssistantDelta -> {
            val existing = state.messages.find { it.id == assistantId }
            val next = if (existing == null) {
                state.messages + ChatMessage(
                    id = assistantId,
                    role = MessageRole.ASSISTANT,
                    text = event.text,
                    streaming = true,
                )
            } else {
                state.messages.map {
                    if (it.id == assistantId) it.copy(text = it.text + event.text, streaming = true) else it
                }
            }
            state.copy(messages = next)
        }
        is ChatEvent.ToolStarted -> state.copy(
            messages = state.messages + ChatMessage(
                id = "t-${UUID.randomUUID()}",
                role = MessageRole.TOOL,
                text = event.detail,
                toolName = event.name,
                toolDetail = event.detail,
            ),
        )
        is ChatEvent.Approval -> state.copy(approval = event.prompt, streaming = false)
        is ChatEvent.PromptExpired ->
            if (state.approval?.requestId == event.requestId) state.copy(approval = null) else state
        is ChatEvent.ToolCompleted -> {
            val updated = state.messages.toMutableList()
            val idx = updated.indexOfLast { it.role == MessageRole.TOOL && it.toolName == event.name }
            if (idx >= 0) {
                val dur = if (event.durationMs > 0) " · ${event.durationMs / 1000.0}s" else ""
                updated[idx] = updated[idx].copy(toolDetail = event.detail + dur, text = event.detail + dur)
            }
            state.copy(messages = updated)
        }
        is ChatEvent.Rewound -> state.copy(
            messages = RewindPolicy.rebindUserRowIds(state.messages, event.userRowIds),
        )
        ChatEvent.Completed -> finishStream(state, assistantId)
    }

private fun finishStream(state: CompanionState, assistantId: String): CompanionState =
    state.copy(
        streaming = false,
        messages = state.messages.map { if (it.id == assistantId) it.copy(streaming = false) else it },
    )

private fun Throwable.toMonoError(): String = when (this) {
    is DashboardException -> "$code · $message"
    else -> message ?: javaClass.simpleName
}
