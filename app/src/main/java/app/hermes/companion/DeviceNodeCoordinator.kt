package app.hermes.companion

import android.content.Intent
import android.util.Base64
import androidx.core.content.ContextCompat
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.device.HandsBridge
import app.hermes.companion.device.HandsService
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
    val deviceProfileId: String? = null,
    val laneOpen: Boolean = false,
    val a11yBound: Boolean = false,
    val foregroundApp: String = "",
    val lastAudit: String = "",
    val arm: DeviceArm = DeviceArm.DISARMED,
    val protectedCustom: List<String> = emptyList(),
    val protectedError: String? = null,
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
    private val client: DashboardClient,
    private val deviceCreds: DeviceCredStore,
    private val sticky: StickyStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        DeviceNodeState(protectedCustom = sticky.protectedPackages.sorted())
            .withCred(deviceCreds.load(), null),
    )
    val state: StateFlow<DeviceNodeState> = _state

    /** Pairing failures and the like; the UI surfaces them in its shared error slot. */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    @Volatile
    private var origin: String? = null
    private var laneJob: Job? = null
    private var idleJob: Job? = null
    private var pairJob: Job? = null
    private var lastRefs: Set<String> = emptySet()
    private var lastNodes: List<SnapshotNode> = emptyList()
    private val rateHits = mutableListOf<Long>()
    private var auditRows: List<DeviceAuditRow> = emptyList()

    init {
        // Volume chord, notification tap (DisarmReceiver) and HandsService teardown all land here.
        HandsBridge.onDisarm = { disarm() }
    }

    // ---- operator lifecycle -------------------------------------------------------------

    /** Operator connected to [origin]: restore pairing from the stored credential, open the lane. */
    fun bind(origin: String) {
        this.origin = origin
        _state.update { it.withCred(deviceCreds.load(), origin) }
        startLane()
    }

    /** Operator gone with nothing armed: drop the lane. Arm state is untouched. */
    fun unbind() {
        stopLane()
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

    /** Kill-switch notification follows the arm state, whether or not an Activity is alive. */
    private fun syncHands(armed: Boolean) {
        val intent = Intent(app, HandsService::class.java)
        if (armed) {
            runCatching { ContextCompat.startForegroundService(app, intent) }
        } else {
            runCatching { app.stopService(intent) }
        }
    }

    // ---- protected packages -----------------------------------------------------------------

    fun addProtectedPackage(raw: String) {
        val pkg = DeviceLanePolicy.normalizePackage(raw)
        if (pkg == null) {
            _state.update { it.copy(protectedError = "not a package id") }
            return
        }
        if (!pkg.endsWith("*") && DeviceLanePolicy.isProtected(pkg)) {
            _state.update { it.copy(protectedError = "already built-in") }
            return
        }
        val next = (sticky.protectedPackages + pkg).toSortedSet()
        sticky.protectedPackages = next
        _state.update { it.copy(protectedCustom = next.toList(), protectedError = null) }
    }

    fun removeProtectedPackage(pkg: String) {
        val next = (sticky.protectedPackages - pkg).toSortedSet()
        sticky.protectedPackages = next
        _state.update { it.copy(protectedCustom = next.toList(), protectedError = null) }
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
                var status = client.offerPair(origin, code, profile)
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
                            )
                        }
                        startLane()
                        return@launch
                    }
                    if (PairingPolicy.expired(started)) {
                        _state.update { it.copy(pairingPhase = PairingPhase.IDLE, pairingCode = "") }
                        _errors.tryEmit("code expired")
                        return@launch
                    }
                    delay(PairingPolicy.POLL_MS)
                    status = runCatching { client.pollPair(origin, code) }.getOrNull() ?: status
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(pairingPhase = PairingPhase.IDLE, pairingCode = "") }
                _errors.tryEmit(e.message ?: "pair failed")
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
                runCatching { client.revokeDevice(origin, id) }
            }
            deviceCreds.clear()
            stopLane()
            idleJob?.cancel()
            idleJob = null
            _state.update {
                it.copy(
                    pairingPhase = PairingPhase.IDLE,
                    pairingCode = "",
                    deviceId = null,
                    deviceProfileId = null,
                    laneOpen = false,
                    arm = DeviceArm.DISARMED,
                )
            }
            syncHands(armed = false)
        }
    }

    // ---- device lane ------------------------------------------------------------------------

    private fun startLane() {
        val origin = origin ?: return
        val cred = deviceCreds.load() ?: return
        if (cred.origin.isNotBlank() && cred.origin != origin) return
        laneJob?.cancel()
        laneJob = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    client.openDeviceLane(origin, cred)
                    _state.update { it.copy(laneOpen = true) }
                    backoff = 1_000L
                    client.deviceCommands().collect { handleCommand(it) }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    _state.update { it.copy(laneOpen = false) }
                }
                client.closeDeviceLane()
                _state.update { it.copy(laneOpen = false) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun stopLane() {
        laneJob?.cancel()
        laneJob = null
        client.closeDeviceLane()
        lastRefs = emptySet()
        lastNodes = emptyList()
        _state.update { it.copy(laneOpen = false) }
    }

    private suspend fun handleCommand(command: DeviceCommand) {
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
            extraProtected = st.protectedCustom.toSet(),
        )
        if (code != null) {
            noteAudit(command.action, target ?: fg, false, code)
            client.replyDevice(DeviceLanePolicy.fail(command.commandId, code))
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
        client.replyDevice(result)
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
                val node = lastNodes.find { it.ref == id }
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
    val mismatch = cred != null && cred.origin.isNotBlank() && !origin.isNullOrBlank() && cred.origin != origin
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
