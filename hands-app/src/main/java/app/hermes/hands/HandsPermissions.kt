package app.hermes.hands

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.hermes.companion.device.DirectToolbox
import app.hermes.companion.device.CompanionAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class HandsPermission(val id: String, val title: String, val summary: String,
    val granted: Boolean, val special: Boolean = false)

object MobilePermissions {
    val runtime = linkedMapOf(
        "sms_read" to arrayOf(Manifest.permission.READ_SMS),
        "sms_send" to arrayOf(Manifest.permission.SEND_SMS),
        "contacts" to arrayOf(Manifest.permission.READ_CONTACTS),
        "call_log" to arrayOf(Manifest.permission.READ_CALL_LOG),
        "calls" to arrayOf(Manifest.permission.CALL_PHONE),
        "calendar" to arrayOf(Manifest.permission.READ_CALENDAR),
        "location" to arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
    ).apply {
        if (android.os.Build.VERSION.SDK_INT >= 33) put("service_notifications", arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    fun states(context: Context): List<HandsPermission> {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val ops = context.getSystemService(AppOpsManager::class.java)
        val usage = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        val nls = DirectToolbox.nlsStatus(context)["nls_bound"] == true
        return listOf(
            HandsPermission("sms_read", "Read SMS", "Read message history only during an approved armed session.", granted(Manifest.permission.READ_SMS)),
            HandsPermission("sms_send", "Send SMS", "Allow an armed agent to send messages. Sending is separate from reading.", granted(Manifest.permission.SEND_SMS)),
            HandsPermission("contacts", "Contacts", "Search names and phone numbers.", granted(Manifest.permission.READ_CONTACTS)),
            HandsPermission("call_log", "Call history", "Read recent call records.", granted(Manifest.permission.READ_CALL_LOG)),
            HandsPermission("calls", "Place calls", "Dial directly; opening the dialer needs no grant.", granted(Manifest.permission.CALL_PHONE)),
            HandsPermission("calendar", "Read calendar", "Read events; adding an event opens your calendar app.", granted(Manifest.permission.READ_CALENDAR)),
            HandsPermission("location", "Last known location", "Read an existing location fix; never starts location tracking.", granted(Manifest.permission.ACCESS_COARSE_LOCATION)),
            HandsPermission("files", "Shared files", "Manage shared internal and removable storage. App-private and protected paths remain blocked.", Environment.isExternalStorageManager(), true),
            HandsPermission("notifications", "Notification access", "Read and act only on apps you explicitly select in Alerts.", nls, true),
            HandsPermission("usage", "App usage", "Read recent app usage statistics.", usage, true),
            HandsPermission("dnd", "Do Not Disturb", "Read and change notification interruption mode.", context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted, true),
            HandsPermission("brightness", "Modify system settings", "Allow brightness changes.", Settings.System.canWrite(context), true),
            HandsPermission("overlay", "Floating screen permission", "Keep the visible Stop control and separate agent preview available.", Settings.canDrawOverlays(context), true),
            HandsPermission("accessibility", "Accessibility", "Optional phone-screen engine. Shizuku can work without it.",
                app.hermes.companion.device.CompanionAccessibilityService.listed(context) == true &&
                    !app.hermes.companion.device.CompanionAccessibilityService.explicitlyOff(context), true),
            HandsPermission("secure_settings", "Secure settings access", "Allows an armed agent to manage supported settings after you grant access using ADB.",
                granted(Manifest.permission.WRITE_SECURE_SETTINGS), true),
            HandsPermission("service_notifications", "Hands notifications", "Keep the foreground-service status and Stop actions visible.",
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(), true),
        )
    }
    fun json(context: Context) = JSONObject().also { out -> states(context).forEach { out.put(it.id, it.granted) } }
    // Accessibility is an explicitly controllable setting, not an Android permission grant.
    // The actual secure-settings grant and every other grant remain part of the session gate.
    fun signature(context: Context) = states(context).filterNot { it.id == "accessibility" }.joinToString("|") { "${it.id}:${it.granted}" } +
        runtime.values.flatMap { it.toList() }.distinct().joinToString("|") { permission ->
            "$permission:${ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED}"
        }
    fun settings(context: Context, id: String): Intent = when (id) {
        "files" -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        "brightness" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        "service_notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
}

/** Local settings may bind the service only while its prior OFF state is provisional. */
internal class LocalAccessibilityApproval(
    private val isOff: () -> Boolean,
    private val isListed: () -> Boolean?,
    private val clearOff: () -> Unit,
    private val restoreOff: () -> Unit,
) {
    private data class Pending(val listedBefore: Boolean?)
    private var pending: Pending? = null

    fun begin() {
        check(pending == null)
        if (!isOff()) return
        pending = Pending(runCatching(isListed).getOrNull())
        clearOff()
    }

    fun finish() {
        val previous = pending ?: return
        pending = null
        val now = runCatching(isListed).getOrNull()
        // Only an observed absent -> present transition proves a new local enable.
        if (previous.listedBefore != false || now != true) restoreOff()
    }
}

/** Every permission prompt is preceded by the same local-only pause/drain as client approval. */
class HandsPermissionController(private val activity: ComponentActivity, private val runtime: HandsRuntime) {
    private val mutable = MutableStateFlow(emptyList<HandsPermission>())
    val state: StateFlow<List<HandsPermission>> = mutable
    private var scopeId: String? = null
    private var preparing = false
    private var externalSettings = false
    private val accessibilityApproval = LocalAccessibilityApproval(
        { CompanionAccessibilityService.explicitlyOff(activity) },
        { CompanionAccessibilityService.listed(activity) },
        { CompanionAccessibilityService.setExplicitlyOff(activity, false) },
        {
            CompanionAccessibilityService.setExplicitlyOff(activity, true)
            CompanionAccessibilityService.requestOff(activity)
        },
    )
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        close(); refresh()
    }
    fun refresh() { mutable.value = MobilePermissions.states(activity) }
    fun returned() { if (externalSettings) close(); refresh() }
    fun request(id: String) {
        if (preparing || scopeId != null) return
        preparing = true
        activity.lifecycleScope.launch {
            var prepared: String? = null
            try {
                scopeId = withContext(Dispatchers.IO) { runtime.beginLocalAuthorization().also { prepared = it } }
                if (id == "accessibility") {
                    accessibilityApproval.begin()
                }
                MobilePermissions.runtime[id]?.takeIf { permissions -> permissions.any {
                    ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
                } }?.let { launcher.launch(it) } ?: run {
                    externalSettings = true
                    activity.startActivity(MobilePermissions.settings(activity, id))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                prepared?.let(runtime::endLocalAuthorization)
                throw cancelled
            } catch (failure: Exception) { close(); runtime.message(failure.message ?: "Open Android app settings to check access.") }
            finally { preparing = false }
        }
    }
    fun external(action: () -> Unit) {
        if (preparing || scopeId != null) return
        preparing = true
        activity.lifecycleScope.launch {
            var prepared: String? = null
            try {
                scopeId = withContext(Dispatchers.IO) { runtime.beginLocalAuthorization().also { prepared = it } }
                externalSettings = true
                action()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                prepared?.let(runtime::endLocalAuthorization)
                throw cancelled
            } catch (failure: Exception) { close(); runtime.message(failure.message ?: "Could not open local permission settings.") }
            finally { preparing = false }
        }
    }
    fun close() {
        val owned = scopeId
        scopeId = null; externalSettings = false
        try { accessibilityApproval.finish() }
        finally { if (owned != null) runtime.endLocalAuthorization(owned) }
    }
}
