package app.hermes.hands.client

import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class HandsClientState(
    val configured: Boolean = false,
    val reachable: Boolean = false,
    val armed: Boolean = false,
    val backend: String = "",
    val target: String = "",
    val a11yBound: Boolean = false,
    val screenControlAvailable: Boolean = false,
    val privacyActive: Boolean = false,
    val agentScreen: Boolean = false,
    val capabilities: Set<String> = emptySet(),
    val protectedPackages: Set<String> = emptySet(),
    val keepAwakeJson: String = """{"enabled":false,"available":false}""",
    val error: String? = null,
)

data class HandsReply(val ok: Boolean, val resultJson: String = "{}", val errorCode: String = "", val message: String = "")

/** The selected external owner never falls back to Companion's local control implementation. */
class HandsClient(
    private val store: HandsConnectionStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(125, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val endpointAllowed: (String) -> Boolean = ::isHandsEndpoint,
) {
    private val mutable = MutableStateFlow(HandsClientState(configured = store.isSelected()))
    val state: StateFlow<HandsClientState> = mutable
    private val leases = mutableMapOf<String, LinkedHashSet<String>>()
    private val snapshots = mutableMapOf<String, String>()
    private val observationEpochs = mutableMapOf<String, Long>()
    private val controllerEpochs = mutableMapOf<String, Long>()
    private var inFlight = 0
    private var controlEpoch = 0L
    private val lock = Mutex()
    private data class Prepared(val connection: HandsConnection, val controlEpoch: Long,
        val observationEpoch: Long, val controllerEpoch: Long)

    suspend fun connect(token: String, endpoint: String): Boolean = lock.withLock {
        require(token.isNotBlank() && token.length <= 4096 && !token.any { it.isISOControl() }) { "Invalid Hands token" }
        require(endpointAllowed(endpoint)) { "Hands must use the approved loopback endpoint" }
        val connection = HandsConnection(endpoint.trimEnd('/'), token)
        if (store.isSelected() && !releaseAll()) {
            // A fresh local approval can repair unreadable credentials only after proving control is off.
            if (store.load() != null) return@withLock false
            val status = request(connection, "/v1/status")
            if (!status.ok || JSONObject(status.resultJson).optBoolean("armed") || JSONObject(status.resultJson).optBoolean("privacy_active")) return@withLock false
        }
        store.save(connection)
        controlEpoch++
        snapshots.clear()
        observationEpochs.clear()
        mutable.value = HandsClientState(configured = true)
        // Keep the approved owner selected even if its foreground server has stopped.
        refreshUnlocked()
        true
    }

    suspend fun refresh(): HandsClientState = lock.withLock { refreshUnlocked(); mutable.value }

    suspend fun command(action: String, argumentsJson: String, controllerId: String): HandsReply {
        if (controllerId.isBlank()) return HandsReply(false, errorCode = "invalid_controller")
        val arguments = try { JSONObject(argumentsJson) } catch (_: Exception) {
            return HandsReply(false, errorCode = "bad_args", message = "Arguments must be an object")
        }
        val requestedLease = arguments.optString("lease_id").takeIf(String::isNotBlank)
        // A host must never borrow a lease supplied by another host/profile or in status metadata.
        arguments.remove("lease_id")
        try {
            arguments.optJSONArray("xy")?.takeIf { it.length() == 2 }?.let {
                if (!arguments.has("x")) arguments.put("x", it.getDouble(0))
                if (!arguments.has("y")) arguments.put("y", it.getDouble(1))
            }
        } catch (_: Exception) { return HandsReply(false, errorCode = "bad_args", message = "Click coordinates must be numeric") }
        val body = JSONObject().put("action", action).put("arguments", arguments).put("controller_id", controllerId)
        val prepared = lock.withLock {
            if (action in setOf("device.disarm", "device.arm.renew") &&
                (requestedLease == null || requestedLease !in leases[controllerId].orEmpty())) {
                return HandsReply(false, errorCode = "lease_mismatch", message = "This controller does not own the requested Hands lease")
            }
            val holder = if (action in setOf("device.disarm", "device.arm.renew")) requestedLease
                else leases[controllerId]?.lastOrNull()
            holder?.let { body.put("lease_id", it) }
            if (action in setOf("device.click", "device.swipe", "device.scroll") &&
                listOf("ref", "from_ref", "to_ref").any { arguments.optString(it).isNotBlank() } && !arguments.has("snapshot_id")) {
                val snapshot = snapshots[controllerId] ?: return HandsReply(false, errorCode = "stale_ref", message = "Obtain a fresh snapshot for this controller")
                arguments.put("snapshot_id", snapshot)
            }
            if (action in INVALIDATE_OBSERVATION) {
                snapshots.remove(controllerId)
                observationEpochs[controllerId] = (observationEpochs[controllerId] ?: 0L) + 1L
            }
            store.load()?.also { inFlight++ }?.let { Prepared(it, controlEpoch,
                observationEpochs[controllerId] ?: 0L, controllerEpochs[controllerId] ?: 0L) }
        } ?: return HandsReply(false, errorCode = if (store.isSelected()) "hands_credentials_unavailable" else "hands_not_connected")
        val (connection, requestEpoch, observationEpoch, controllerEpoch) = prepared
        try {
            val reply = request(connection, "/v1/commands", body)
            return lock.withLock {
                if (store.load() != connection) return@withLock HandsReply(false, errorCode = "hands_owner_changed")
                if (requestEpoch != controlEpoch) return@withLock HandsReply(false, errorCode = "disarmed", message = "Control stopped while the command was running")
                if (controllerEpoch != (controllerEpochs[controllerId] ?: 0L)) {
                    // A disconnected lane must not acquire a holder from a late ARM reply.
                    // This is that controller's own issued holder, never a status lease.
                    if (action == "device.arm" && reply.ok) {
                        val issued = runCatching { JSONObject(reply.resultJson).optString("lease_id") }.getOrNull()
                            ?.takeIf(String::isNotBlank)
                        if (issued != null) {
                            leases.getOrPut(controllerId) { linkedSetOf() }.add(issued)
                            releaseHolder(connection, controllerId, issued)
                        }
                    }
                    return@withLock HandsReply(false, errorCode = "disarmed", message = "This controller lane closed while the command was running")
                }
                if (reply.ok) {
                    val result = runCatching { JSONObject(reply.resultJson) }.getOrDefault(JSONObject())
                    val controlStopped = action == "device.agent_screen.stop" ||
                        result.opt("local_approval_required") == true || result.opt("armed") == false ||
                        result.opt("privacy_active") == true
                    if (controlStopped) {
                        controlEpoch++
                        leases.clear()
                        snapshots.clear()
                        observationEpochs.clear()
                        mutable.value = mutable.value.copy(keepAwakeJson = """{"enabled":false,"available":false}""")
                    }
                    if (!controlStopped && action == "device.arm") result.optString("lease_id").takeIf(String::isNotBlank)?.let {
                        leases.getOrPut(controllerId) { linkedSetOf() }.add(it)
                    }
                    if (action == "device.disarm") leases[controllerId]?.remove(requestedLease)
                    if (!controlStopped && (observationEpochs[controllerId] ?: 0L) == observationEpoch) {
                        val snapshot = if (action == "device.snapshot") result else result.optJSONObject("snapshot")
                        if (snapshot?.optJSONArray("nodes") != null) snapshot.optString("snapshot_id").takeIf(String::isNotBlank)?.let {
                            snapshots[controllerId] = it
                        }
                    }
                    if (result.has("lease_id") && result.optString("lease_id") !in leases[controllerId].orEmpty()) result.remove("lease_id")
                    updateState(if (controlStopped) JSONObject(result.toString()).put("armed", false) else result,
                        statusMetadata = action in setOf("device.status", "device.agent_screen.status"))
                    return@withLock reply.copy(resultJson = result.toString())
                }
                when (reply.errorCode) {
                    "lease_mismatch", "lease_expired" -> leases[controllerId]?.remove(body.optString("lease_id"))
                    "disarmed", "privacy_active" -> { leases.remove(controllerId); snapshots.remove(controllerId); observationEpochs.remove(controllerId) }
                }
                reply
            }
        } finally {
            withContext(NonCancellable) { lock.withLock { inFlight-- } }
        }
    }

    /** Explicit operator stop. Relay callers use command(), which always scopes release to their lease. */
    suspend fun stopAll(): Boolean = lock.withLock {
        val connection = store.load() ?: return@withLock !store.isSelected()
        val reply = request(connection, "/v1/commands", JSONObject().put("action", "device.disarm")
            .put("arguments", JSONObject()).put("controller_id", "companion-local-ui"))
        if (!reply.ok) return@withLock false
        controlEpoch++
        leases.clear()
        snapshots.clear()
        observationEpochs.clear()
        updateState(JSONObject(reply.resultJson).put("armed", false))
        true
    }

    /** An uncertain release keeps external mode selected; the operator can stop it in Hands. */
    suspend fun disconnect(): Boolean = lock.withLock {
        if (!releaseAll()) return@withLock false
        store.clear()
        controlEpoch++
        snapshots.clear()
        observationEpochs.clear()
        mutable.value = HandsClientState()
        true
    }

    /** Release only holders issued to this controller; lane cleanup is never a global stop. */
    suspend fun releaseController(controllerId: String): Boolean = lock.withLock {
        if (controllerId.isBlank()) return@withLock false
        controllerEpochs[controllerId] = (controllerEpochs[controllerId] ?: 0L) + 1L
        snapshots.remove(controllerId)
        observationEpochs.remove(controllerId)
        val owned = leases[controllerId]?.toList().orEmpty()
        if (owned.isEmpty()) return@withLock true
        val connection = store.load() ?: run {
            mutable.value = mutable.value.copy(error = "hands_credentials_unavailable")
            return@withLock false
        }
        var released = true
        for (holder in owned) if (!releaseHolder(connection, controllerId, holder)) released = false
        if (leases[controllerId]?.isEmpty() == true) leases.remove(controllerId)
        released
    }

    /** Called only under [lock], with an exact holder learned from this controller's ARM. */
    private suspend fun releaseHolder(connection: HandsConnection, controllerId: String, holder: String): Boolean {
        val reply = request(connection, "/v1/commands", JSONObject().put("action", "device.disarm")
            .put("arguments", JSONObject()).put("controller_id", controllerId).put("lease_id", holder))
        if (reply.ok || reply.errorCode in setOf("disarmed", "lease_mismatch", "lease_expired")) {
            leases[controllerId]?.remove(holder)
            if (reply.ok) updateState(JSONObject(reply.resultJson))
            return true
        }
        mutable.value = mutable.value.copy(error = "hands_release_uncertain")
        return false
    }

    private suspend fun releaseAll(): Boolean {
        val connection = store.load() ?: return !store.isSelected()
        if (inFlight > 0) {
            mutable.value = mutable.value.copy(error = "hands_command_in_progress")
            return false
        }
        for ((controller, holders) in leases.mapValues { it.value.toList() }) {
            for (holder in holders) {
                val result = request(connection, "/v1/commands", JSONObject().put("action", "device.disarm")
                    .put("arguments", JSONObject()).put("controller_id", controller).put("lease_id", holder))
                if (!result.ok && result.errorCode !in setOf("disarmed", "lease_mismatch", "lease_expired")) return false
                leases[controller]?.remove(holder)
            }
            leases.remove(controller)
        }
        val status = request(connection, "/v1/status")
        if (!status.ok) return false
        updateState(JSONObject(status.resultJson), statusMetadata = true)
        if (mutable.value.armed || mutable.value.privacyActive) {
            mutable.value = mutable.value.copy(error = "hands_owner_active")
            return false
        }
        return true
    }

    private suspend fun refreshUnlocked() {
        val connection = store.load() ?: run {
            if (store.isSelected()) mutable.value = mutable.value.copy(reachable = false, armed = false,
                screenControlAvailable = false,
                keepAwakeJson = """{"enabled":false,"available":false}""", error = "hands_credentials_unavailable")
            return
        }
        val reply = request(connection, "/v1/status")
        if (reply.ok) updateState(JSONObject(reply.resultJson), statusMetadata = true)
    }

    private fun updateState(result: JSONObject, statusMetadata: Boolean = false) {
        val old = mutable.value
        val protected = if (statusMetadata) result.optJSONArray("protected_packages")?.let { rows ->
            (0 until minOf(rows.length(), 100)).mapNotNull { index ->
                (rows.opt(index) as? String)?.takeIf { it.length in 3..255 && PROTECTED_RULE.matches(it) }
            }.toSet()
        }.orEmpty() else old.protectedPackages
        val keepAwake = if (statusMetadata) result.optJSONObject("keep_awake")
            ?.takeIf { it.opt("enabled") is Boolean }?.let { JSONObject(it.toString()).put("available", true).toString() }
            ?: """{"enabled":false,"available":false}""" else old.keepAwakeJson
        val screenControlAvailable = if (statusMetadata) (result.opt("screen_control_available") as? Boolean)
            ?: (result.opt("lanes") == "l0+l1") else old.screenControlAvailable
        mutable.value = old.copy(configured = true, reachable = true, error = null,
            armed = if (result.has("armed")) result.optBoolean("armed") else old.armed,
            backend = result.optString("backend", old.backend), target = result.optString("target", old.target),
            a11yBound = result.optBoolean("a11y_bound", old.a11yBound),
            screenControlAvailable = screenControlAvailable,
            privacyActive = result.optBoolean("privacy_active", old.privacyActive),
            agentScreen = result.optJSONObject("agent_screen")?.optBoolean("active")
                ?: (result.opt("agent_screen") as? Boolean) ?: old.agentScreen,
            protectedPackages = protected, keepAwakeJson = keepAwake,
            capabilities = result.optJSONArray("capabilities")?.let { rows ->
                (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }.toSet()
            } ?: old.capabilities)
    }

    private suspend fun request(connection: HandsConnection, path: String, body: JSONObject? = null): HandsReply = withContext(Dispatchers.IO) {
        if (!endpointAllowed(connection.endpoint)) return@withContext HandsReply(false, errorCode = "hands_invalid_endpoint")
        try {
            val builder = Request.Builder().url(connection.endpoint.trimEnd('/') + path)
                .header("Authorization", "Bearer ${connection.token}")
                .header("Accept", "application/json")
            if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            http.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val parsed = runCatching { JSONObject(raw) }.getOrNull()
                if (!response.isSuccessful || parsed == null || parsed.opt("ok") !is Boolean) {
                    val code = if (response.code == 401 || response.code == 403) "hands_unauthorized" else "hands_invalid_response"
                    mutable.value = mutable.value.copy(reachable = false, armed = false,
                        screenControlAvailable = false,
                        keepAwakeJson = """{"enabled":false,"available":false}""", error = code)
                    return@withContext HandsReply(false, errorCode = code, message = "Open Hands to reconnect")
                }
                if (parsed.getBoolean("ok")) {
                    val result = parsed.optJSONObject("result") ?: JSONObject()
                    HandsReply(true, result.toString())
                } else {
                    val error = parsed.optJSONObject("error") ?: JSONObject()
                    val code = error.optString("code", "hands_command_failed")
                    mutable.value = mutable.value.copy(error = code)
                    HandsReply(false, errorCode = code, message = error.optString("message"))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(reachable = false, armed = false,
                screenControlAvailable = false,
                keepAwakeJson = """{"enabled":false,"available":false}""", error = "hands_unavailable")
            HandsReply(false, errorCode = "hands_unavailable", message = "Open Hands; external control remains selected")
        }
    }

    companion object {
        private val PROTECTED_RULE = Regex("^[a-z_][a-z0-9_]*(\\.[a-z0-9_]+)+(\\.\\*)?$")
        private val INVALIDATE_OBSERVATION = setOf("device.arm", "device.disarm", "device.click", "device.type", "device.swipe",
            "device.scroll", "device.press", "device.open_app", "device.screenshot", "device.agent_screen.start", "device.agent_screen.stop")
        fun controllerId(origin: String, profile: String): String = MessageDigest.getInstance("SHA-256")
            .digest((origin.trimEnd('/') + "\n" + profile).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun isHandsEndpoint(raw: String): Boolean = runCatching {
            val uri = URI(raw)
            uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 8765 &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/")
        }.getOrDefault(false)
    }
}
