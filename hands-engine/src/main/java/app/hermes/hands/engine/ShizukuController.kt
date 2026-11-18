package app.hermes.hands.engine

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku

data class ShizukuStatus(
    val installed: Boolean = false,
    val running: Boolean = false,
    val permissionGranted: Boolean = false,
    val bound: Boolean = false,
    val message: String = "Shizuku is optional",
)

/** Application-scoped client. Permission requests are local UI actions only. */
class ShizukuController(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val mutableStatus = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = mutableStatus
    @Volatile private var service: IHandsService? = null
    @Volatile private var connecting = false
    // Debug APKs can retain versionCode=1. Change the UserService version on EVERY install
    // so a shell-owned helper from an older APK never keeps running stale control code.
    private val installedVersion = runCatching {
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        (updated xor (updated ushr 32)).toInt().and(Int.MAX_VALUE).coerceAtLeast(1)
    }.getOrDefault(1)
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, PrivilegedHandsService::class.java.name))
        .tag("hermes-hands-engine").processNameSuffix("hands_engine").version(installedVersion).daemon(false)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IHandsService.Stub.asInterface(binder)
            connecting = false
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connecting = false
            refresh()
        }
    }
    private val received = Shizuku.OnBinderReceivedListener {
        refresh()
        autoConnectIfPermitted()
    }
    private val dead = Shizuku.OnBinderDeadListener {
        service = null
        connecting = false
        refresh()
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == REQUEST_CODE) { refresh(); if (mutableStatus.value.permissionGranted) connect() }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        refresh()
        autoConnectIfPermitted()
    }

    fun autoConnectIfPermitted() {
        if (mutableStatus.value.running && mutableStatus.value.permissionGranted && service?.asBinder()?.isBinderAlive != true && !connecting) {
            runCatching { connect() }
        }
    }

    fun refresh() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val installed = running || runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)
        val granted = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val supported = running && runCatching { Shizuku.getVersion() >= 13 }.getOrDefault(false)
        mutableStatus.value = ShizukuStatus(installed, running, granted, service?.asBinder()?.isBinderAlive == true,
            when {
                !installed -> "Install Shizuku to enable the privileged backend"
                !running -> "Start Shizuku using Wireless debugging"
                !supported -> "Update Shizuku to version 13 or newer"
                !granted -> "Allow Hands in Shizuku"
                service != null -> "Shizuku backend connected"
                connecting -> "Connecting to Shizuku"
                else -> "Shizuku is ready to connect"
            })
    }

    fun requestPermission() {
        refresh()
        check(status.value.running) { "shizuku_not_running" }
        check(Shizuku.getVersion() >= 13) { "shizuku_version_unsupported" }
        if (status.value.permissionGranted) connect() else Shizuku.requestPermission(REQUEST_CODE)
    }

    fun connect(waitMs: Long = 0) {
        refresh()
        check(status.value.running) { "shizuku_not_running" }
        check(status.value.permissionGranted) { "shizuku_permission_denied" }
        check(Shizuku.getVersion() >= 13) { "shizuku_version_unsupported" }
        if (service?.asBinder()?.isBinderAlive == true) return
        if (!connecting) {
            connecting = true
            try { Shizuku.bindUserService(args, connection) } catch (error: RuntimeException) {
                connecting = false
                refresh()
                throw error
            }
        }
        if (waitMs > 0 && (service == null || service?.asBinder()?.isBinderAlive != true) && Looper.myLooper() != Looper.getMainLooper()) {
            val deadline = System.currentTimeMillis() + waitMs
            while ((service == null || service?.asBinder()?.isBinderAlive != true) && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }
        refresh()
    }

    /** The surface must remain alive until destroyAgentScreen has returned. API 34+ only. */
    fun createAgentScreen(surface: Surface, width: Int, height: Int, densityDpi: Int): Int {
        workerOnly()
        EnginePolicy.dimensions(width, height, densityDpi)
        return requireService().createAgentScreen(surface, width, height, densityDpi)
    }

    fun destroyAgentScreen() { workerOnly(); service?.destroyAgentScreen() }

    /** Kill an in-flight Android input process when the operator disarms. */
    fun cancel() { workerOnly(); cancelAsync() }

    /** One-way binder revocation: safe to send directly from the local Stop button. */
    fun cancelAsync() { runCatching { service?.cancel() } }

    /** Called only after local approval/lease acquisition, never as an action retry. */
    fun beginSession(): Long { workerOnly(); return requireService().beginSession() }

    /** Phone-local only: the opaque handle and entered value must never enter remote JSON. */
    fun captureSecureTarget(displayId: Int, ref: String, snapshotId: String, packageName: String, sessionEpoch: Long): String {
        workerOnly()
        return requireService().captureSecureTarget(displayId, ref, snapshotId, packageName, sessionEpoch)
    }

    fun fillSecureTarget(handle: String, value: String): Boolean {
        workerOnly()
        return requireService().fillSecureTarget(handle, value)
    }

    /** Idempotent during disarm, timeout, service loss or controller shutdown. */
    fun releaseSecureTarget(handle: String) {
        workerOnly()
        runCatching { service?.releaseSecureTarget(handle) }
    }

    /** A returned error never retries on display zero. All calls must run on a worker. */
    fun execute(action: String, arguments: JSONObject, displayId: Int): JSONObject {
        workerOnly()
        return try { JSONObject(requireService().execute(action, arguments.toString(), displayId)) }
        catch (error: Exception) { JSONObject().put("ok", false).put("error", JSONObject()
            .put("code", "shizuku_unavailable").put("message", "Shizuku backend disconnected; obtain a fresh observation before continuing")) }
    }

    fun foregroundPackage(displayId: Int): String? {
        workerOnly()
        return requireService().foregroundPackage(displayId).takeIf { !it.isNullOrBlank() }
    }

    private fun requireService(): IHandsService = checkNotNull(service) { "shizuku_not_connected" }
    private fun workerOnly() { check(Looper.myLooper() != Looper.getMainLooper()) { "Run Shizuku calls on a worker thread" } }

    override fun close() {
        service = null
        connecting = false
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        refresh()
    }

    companion object { const val REQUEST_CODE = 4801 }
}
