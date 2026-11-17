package app.hermes.hands

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import app.hermes.companion.device.DevicePrivacy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID

internal interface PrivateFieldTarget {
    fun fill(value: String): Boolean
    fun close()
}

data class SecureEntryState(
    val requestId: String? = null,
    val status: String = "idle",
    val packageName: String = "",
    val label: String = "",
    val privacyActive: Boolean = false,
)

/** Local values never enter HTTP, MCP, Intents, saved state, or this metadata flow. */
class HandsSecureEntryController(private val context: Context, private val runtime: HandsRuntime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SecureEntryState(
        status = if (DevicePrivacy.active) "expired" else "idle", privacyActive = DevicePrivacy.active))
    val state: StateFlow<SecureEntryState> = mutable
    private var owner: String? = null
    private var target: PrivateFieldTarget? = null
    private var permit: (() -> Unit)? = null
    private var timeout: Job? = null
    private var filling: Job? = null

    @Synchronized fun command(controller: String, args: JSONObject, requirePermit: () -> Unit): JSONObject {
        val action = args.optString("action")
        val fields = when (action) {
            "request" -> setOf("action", "package", "ref", "snapshot_id", "label")
            "status", "cancel" -> setOf("action", "request_id")
            else -> error("invalid_args")
        }
        require(args.keys().asSequence().all { it in fields }) { "invalid_args" }
        if (action != "request") {
            val id = args.getString("request_id")
            require(validId(id)) { "invalid_args" }
            check(owner == controller && mutable.value.requestId == id) { "request_not_found" }
            if (action == "cancel") cancel(id)
            return response(id)
        }
        requirePermit()
        check(!DevicePrivacy.active) { "privacy_active" }
        val pkg = args.getString("package")
        val snapshot = args.getString("snapshot_id")
        val ref = args.getString("ref").removePrefix("@")
        val label = args.optString("label", "Sensitive value")
        require(validId(snapshot) && pkg.isNotBlank() && ref.isNotBlank() &&
            label.length in 1..80 && label.none { it.isISOControl() }) { "invalid_args" }
        val captured = runtime.capturePrivateTarget(pkg, ref, snapshot)
        try {
            requirePermit()
            if (!DevicePrivacy.begin(context)) {
                runtime.pauseForPrivateEntry()
                mutable.value = SecureEntryState(status = "failed", privacyActive = true)
                error("privacy_unavailable")
            }
            target = captured
            permit = requirePermit
            owner = controller
            val id = UUID.randomUUID().toString()
            mutable.value = SecureEntryState(id, "pending", pkg, label, true)
            runtime.pauseForPrivateEntry()
            timeout?.cancel()
            timeout = scope.launch { delay(120_000); synchronized(this@HandsSecureEntryController) {
                if (mutable.value.requestId == id && mutable.value.status == "pending") finish("expired")
            } }
            runCatching { open() }
            return response(id)
        } catch (failure: Exception) {
            if (target !== captured) captured.close()
            throw failure
        }
    }

    @Synchronized fun submit(id: String, value: String): Boolean {
        if (mutable.value.requestId != id || mutable.value.status != "pending" || !DevicePrivacy.active) return false
        if (value.isEmpty() || value.length > 16_384 || !stillAllowed()) return false
        DevicePrivacy.rememberValue(value)
        timeout?.cancel(); timeout = null
        mutable.value = mutable.value.copy(status = "filling")
        filling = scope.launch {
            repeat(30) {
                delay(100)
                val done = withContext(Dispatchers.IO) {
                    synchronized(this@HandsSecureEntryController) {
                        if (mutable.value.requestId != id || mutable.value.status != "filling" || !stillAllowed()) {
                            finish("cancelled"); return@synchronized true
                        }
                        if (runCatching { target?.fill(value) == true }.getOrDefault(false)) {
                            finish("filled"); true
                        } else false
                    }
                }
                if (done) return@launch
            }
            synchronized(this@HandsSecureEntryController) { finish("failed") }
        }
        return true
    }

    private fun stillAllowed(): Boolean = DevicePrivacy.active &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        !app.hermes.companion.domain.DeviceLanePolicy.isProtected(mutable.value.packageName, runtime.state.value.protectedPackages + context.packageName) &&
        runCatching { checkNotNull(permit).invoke(); true }.getOrDefault(false)

    @Synchronized fun cancel(id: String) {
        if (mutable.value.requestId == id && mutable.value.status in setOf("pending", "filling")) finish("cancelled")
    }

    @Synchronized fun cancelPending() {
        if (mutable.value.status in setOf("pending", "filling")) finish("cancelled")
    }

    private fun finish(status: String) {
        timeout?.cancel(); timeout = null
        filling?.cancel(); filling = null
        target?.close(); target = null; permit = null
        mutable.value = mutable.value.copy(status = status, privacyActive = DevicePrivacy.active)
    }

    /** Only the physical phone's recovery UI may release the durable shield. */
    @Synchronized fun releaseLocally(): Boolean {
        runtime.disarm()
        cancelPending()
        if (!DevicePrivacy.releaseLocally(context)) return false
        mutable.value = mutable.value.copy(status = "released", privacyActive = false)
        return true
    }

    fun open() { context.startActivity(Intent(context, SecureEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun response(id: String) = JSONObject().put("request_id", id).put("status", mutable.value.status)
        .put("privacy_active", DevicePrivacy.active)
    private fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
}
