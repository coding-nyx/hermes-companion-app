package app.hermes.hands.engine

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/**
 * Instantiated by Shizuku as shell/root, never registered as an exported Android service.
 * The API contains only typed, allowlisted operations. Every input targets an explicit display.
 */
@Keep
class PrivilegedHandsService @Keep constructor(context: Context) : IHandsService.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private val shellContext = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    private val displays = shellContext.getSystemService(DisplayManager::class.java)
    private val callbacks = HandlerThread("hands-display").apply { start() }
    private val lock = Any()
    private var agentDisplay: VirtualDisplay? = null
    private var agentSurface: Surface? = null
    @Volatile private var activeProcess: java.lang.Process? = null
    private val sessions = EngineSessions()
    private var commandEpoch: Long? = null // Accessed only inside the serialized command lock.
    private val observations = mutableMapOf<Int, Observation>()
    private val observationConnection = ObservationConnection(::openAutomation)
    private var observationTimeout: Runnable? = null
    private val privateValues = PrivateValues()
    @Volatile private var privateTarget: PrivateTarget? = null

    private data class Observation(val id: String, val packageName: String, val windowId: Int, val nodes: JSONArray)
    private class Failure(val code: String, override val message: String) : RuntimeException(message)
    private class AutomationSession(val client: UiAutomation, private val thread: HandlerThread) : AutoCloseable {
        override fun close() {
            runCatching { UiAutomation::class.java.getMethod("disconnect").invoke(client) }
            thread.quitSafely()
        }
    }
    private class PrivateTarget(
        val handle: String, val displayId: Int, val packageName: String, val connection: AutomationSession,
        val node: AccessibilityNodeInfo, val expires: Long,
    ) {
        val windowId = node.windowId
        val viewId = node.viewIdResourceName
        val className = node.className?.toString()
        val inputType = node.inputType
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val closed = AtomicBoolean(false)
        var timeout: Runnable? = null
    }

    override fun status(): String = privileged {
        JSONObject().put("uid", Process.myUid()).put("sdk_int", Build.VERSION.SDK_INT)
            .put("agent_screen_supported", Build.VERSION.SDK_INT >= 34)
            .put("agent_display_id", agentDisplay?.display?.displayId ?: JSONObject.NULL).toString()
    }

    @android.annotation.SuppressLint("WrongConstant") // AOSP hidden flags are intentional inside the privileged UserService.
    override fun createAgentScreen(surface: Surface, width: Int, height: Int, densityDpi: Int): Int = privileged {
        if (Build.VERSION.SDK_INT < 34) throw Failure("agent_screen_unsupported", "Independent agent focus requires Android 14 or newer")
        EnginePolicy.dimensions(width, height, densityDpi)
        val epoch = sessions.requireActive()
        require(surface.isValid) { "invalid_surface" }
        check(agentDisplay == null) { "agent_screen_already_open" }
        // OWN_CONTENT_ONLY prevents mirroring the operator's phone. DESTROY_CONTENT_ON_REMOVAL
        // prevents Android moving agent tasks to the operator display when the screen closes.
        // OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED keep targeted input off the operator's focus.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 6) or (1 shl 8) or (1 shl 10) or (1 shl 14) or (1 shl 16)
        var createdId = -1
        val stopped = object : VirtualDisplay.Callback() {
            override fun onStopped() { synchronized(lock) {
                if (privateTarget?.displayId == createdId) clearPrivateTarget()
                if (observations.containsKey(createdId) || agentDisplay?.display?.displayId == createdId) clearObservationConnection()
                observations.remove(createdId)
                if (agentDisplay?.display?.displayId == createdId) {
                    agentDisplay = null
                    agentSurface?.release(); agentSurface = null
                }
            } }
        }
        val display = displays.createVirtualDisplay("Hands agent", width, height, densityDpi,
            surface, flags, stopped, Handler(callbacks.looper))
            ?: throw Failure("agent_screen_failed", "Android did not create the agent display")
        try { sessions.require(epoch) } catch (error: Exception) { display.release(); surface.release(); throw error }
        val requiredDisplayFlags = (1 shl 11) or (1 shl 12)
        if (display.display.displayId == 0 || display.display.flags and requiredDisplayFlags != requiredDisplayFlags) {
            display.release()
            throw Failure("focus_isolation_unavailable", "This Android build did not preserve independent agent focus")
        }
        agentSurface = surface
        agentDisplay = display
        createdId = display.display.displayId
        display.display.displayId
    }

    override fun destroyAgentScreen() = privileged { releaseDisplay() }

    private fun releaseDisplay() {
        if (privateTarget?.displayId == agentDisplay?.display?.displayId) clearPrivateTarget()
        clearObservationConnection()
        agentDisplay?.display?.displayId?.let { observations.remove(it) }
        agentDisplay?.release()
        agentDisplay = null
        agentSurface?.release()
        agentSurface = null
    }

    override fun destroy() {
        // The reserved lifecycle RPC is also called by Shizuku's own shell/root server.
        val caller = Binder.getCallingUid()
        if (caller != ownerUid && caller != Process.myUid() && caller != 0) throw SecurityException("Untrusted lifecycle caller")
        sessions.cancel { activeProcess?.destroyForcibly() }
        val identity = Binder.clearCallingIdentity()
        try { synchronized(lock) { clearPrivateTarget(); releaseDisplay(); privateValues.clear(); callbacks.quitSafely() } }
        finally { Binder.restoreCallingIdentity(identity) }
        System.exit(0)
    }

    override fun cancel() {
        checkOwner()
        sessions.cancel { activeProcess?.destroyForcibly() }
        // Cancellation must latch before waiting behind observation IPC. A later beginSession
        // closes old observations synchronously; this callback never closes that new session.
        Handler(callbacks.looper).post { synchronized(lock) {
            if (runCatching { sessions.requireActive() }.isFailure) clearObservationConnection()
        } }
    }

    override fun beginSession(): Long = privileged {
        check(privateTarget == null) { "privacy_active" }
        clearObservationConnection()
        sessions.begin { activeProcess?.destroyForcibly() }
    }

    override fun captureSecureTarget(displayId: Int, ref: String, snapshotId: String, packageName: String, sessionEpoch: Long): String = privileged {
        sessions.require(sessionEpoch)
        try { target(displayId) } catch (failure: Failure) { throw IllegalStateException(failure.code) }
        check(privateTarget == null) { "private_target_busy" }
        check(shellContext.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == false) { "screen_locked" }
        val previous = observations[displayId] ?: throw IllegalStateException("stale_ref")
        check(previous.id == snapshotId && previous.packageName == packageName) { "stale_ref" }
        val normalized = ref.removePrefix("@")
        val original = (0 until previous.nodes.length()).asSequence().map { previous.nodes.getJSONObject(it) }
            .firstOrNull { it.optString("ref") == normalized } ?: throw IllegalStateException("stale_ref")
        check(original.optBoolean("editable") && original.optBoolean("enabled")) { "invalid_target" }
        var captured: AccessibilityNodeInfo? = null
        var transferred: PrivateTarget? = null
        commandEpoch = sessionEpoch
        try {
            val lease = acquireObservationConnection()
            val opened = lease.value
            val valid = withRoot(opened.client, displayId) { root ->
                if (root.packageName?.toString() != packageName || root.windowId != previous.windowId) return@withRoot false
                val fresh = JSONArray()
                collect(root, fresh, 0) { freshRef, node ->
                    if (freshRef == normalized) captured = AccessibilityNodeInfo.obtain(node)
                }
                val current = (0 until fresh.length()).asSequence().map { fresh.getJSONObject(it) }
                    .firstOrNull { it.optString("ref") == normalized }
                current != null && current.toString() == original.toString()
            } == true
            val node = checkNotNull(captured) { "stale_ref" }
            check(valid && node.isEditable && node.isEnabled && node.isVisibleToUser && node.packageName?.toString() == packageName) { "stale_ref" }
            sessions.require(sessionEpoch)
            val held = PrivateTarget(UUID.randomUUID().toString(), displayId, packageName, opened, node,
                android.os.SystemClock.elapsedRealtime() + 120_000)
            // Snapshot, validation and captured node all share this same live connection.
            // Only after validation succeeds does the private handle take exclusive ownership.
            observationConnection.transfer(lease)
            transferred = held
            privateTarget = held
            captured = null
            observationTimeout?.let { Handler(callbacks.looper).removeCallbacks(it) }
            observationTimeout = null
            observations.clear()
            held.timeout = Runnable {
                held.closed.set(true)
                synchronized(lock) { clearPrivateTarget(held.handle) }
            }.also { check(Handler(callbacks.looper).postDelayed(it, 120_000)) { "private_target_unavailable" } }
            sessions.require(sessionEpoch)
            transferred = null // Published handle now owns cleanup; failures above roll it back.
            held.handle
        } catch (failure: Exception) {
            val code = when (failure) {
                is Failure -> failure.code
                else -> failure.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: "private_target_unavailable"
            }
            throw IllegalStateException(code)
        } finally {
            try { transferred?.let { clearPrivateTarget(it.handle) }; captured?.recycle() }
            finally { commandEpoch = null }
        }
    }

    override fun fillSecureTarget(handle: String, value: String): Boolean = privileged {
        val held = privateTarget?.takeIf { it.handle == handle } ?: return@privileged false
        if (held.closed.get() || android.os.SystemClock.elapsedRealtime() >= held.expires) {
            clearPrivateTarget(handle); return@privileged false
        }
        if (value.length > PrivateValues.MAX_CHARS) return@privileged false
        try {
            target(held.displayId)
            if (shellContext.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != false) return@privileged false
            if (tasks(held.displayId).firstNotNullOfOrNull { it.topActivity?.packageName } != held.packageName) return@privileged false
            if (!originalPrivateWindowIsActive(held)) return@privileged false
            if (!held.node.refresh()) return@privileged false
            val now = Rect().also { held.node.getBoundsInScreen(it) }
            if (held.closed.get() || held.node.packageName?.toString() != held.packageName || held.node.windowId != held.windowId ||
                held.node.viewIdResourceName != held.viewId || held.node.className?.toString() != held.className ||
                held.node.inputType != held.inputType || now != held.bounds || !held.node.isEditable || !held.node.isEnabled || !held.node.isVisibleToUser)
                return@privileged false
            val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            try {
                if (held.closed.get() || android.os.SystemClock.elapsedRealtime() >= held.expires) {
                    clearPrivateTarget(handle); return@privileged false
                }
                // Even a refused/uncertain platform action must not allow this local value
                // to escape through a later text observation.
                privateValues.remember(value)
                val accepted = held.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (accepted) clearPrivateTarget(handle)
                accepted
            } finally { bundle.clear() }
        } catch (_: Exception) {
            // No exception detail may include the local value or target app's field content.
            if (displays.getDisplay(held.displayId) == null) clearPrivateTarget(handle)
            false
        }
    }

    override fun releaseSecureTarget(handle: String) {
        checkOwner()
        // Invalidate without waiting behind a fill's validation; cleanup waits until the exact
        // node is no longer in use. A wrong handle never releases another local request.
        privateTarget?.takeIf { it.handle == handle }?.closed?.set(true)
        privileged { clearPrivateTarget(handle) }
    }

    private fun clearPrivateTarget(expected: String? = null) {
        val held = privateTarget ?: return
        if (expected != null && held.handle != expected) return
        privateTarget = null
        held.closed.set(true)
        held.timeout?.let { Handler(callbacks.looper).removeCallbacks(it) }
        try { held.node.recycle() } finally { held.connection.close() }
    }

    private fun originalPrivateWindowIsActive(held: PrivateTarget): Boolean {
        val all = held.connection.client.windowsOnAllDisplays
        try {
            val focused = all[held.displayId].orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }
                .sortedByDescending { if (it.isFocused) 1 else 0 }.firstOrNull() ?: return false
            if (focused.id != held.windowId) return false
            val root = focused.root ?: return false
            return try { root.refresh() && root.packageName?.toString() == held.packageName && root.windowId == held.windowId && root.isVisibleToUser }
            finally { root.recycle() }
        } finally { for (i in 0 until all.size()) all.valueAt(i).forEach { it.recycle() } }
    }

    override fun foregroundPackage(displayId: Int): String? = privileged {
        target(displayId)
        // Task metadata stays available for canvas/SurfaceView apps that expose no tree.
        // The host can enforce its package policy without making pixel actions tree-dependent.
        tasks(displayId).firstNotNullOfOrNull { it.topActivity?.packageName }
    }

    override fun execute(action: String, arguments: String, displayId: Int): String = privileged {
        try {
            if (privateTarget != null) throw Failure("privacy_active", "Private entry pauses remote screen access")
            if (arguments.length > 32_768) throw Failure("invalid_arguments", "Arguments exceed the command limit")
            val args = JSONObject(arguments)
            val epoch = args.optLong("_hands_session", -1)
            sessions.require(epoch)
            commandEpoch = epoch
            target(displayId)
            val result = when (action) {
                "device.snapshot" -> snapshot(displayId)
                "device.screenshot" -> screenshot(displayId)
                "device.click" -> {
                    val (width, height) = size(displayId)
                    val xy = if (args.has("ref")) resolveRef(displayId, args) else {
                        val values = args.optJSONArray("xy")
                        (values?.getDouble(0) ?: args.getDouble("x")) to (values?.getDouble(1) ?: args.getDouble("y"))
                    }
                    input(displayId, "tap", EnginePolicy.coordinate(xy.first, width), EnginePolicy.coordinate(xy.second, height))
                    observations.remove(displayId)
                    JSONObject().put("display_id", displayId).put("input_submitted", true).put("delivery_verified", false)
                }
                "device.swipe" -> {
                    val (width, height) = size(displayId)
                    val points = when {
                        listOf("x1", "y1", "x2", "y2").all { args.has(it) } ->
                            listOf(args.getDouble("x1"), args.getDouble("y1"), args.getDouble("x2"), args.getDouble("y2"))
                        args.has("from_ref") || args.has("to_ref") -> {
                            if (!args.has("from_ref") || !args.has("to_ref")) throw Failure("invalid_swipe", "Supply both from_ref and to_ref")
                            val nodes = verifyRefs(displayId, args, listOf("from_ref", "to_ref"))
                            val from = center(nodes.getValue("from_ref"))
                            val to = center(nodes.getValue("to_ref"))
                            listOf(from.first, from.second, to.first, to.second)
                        }
                        args.has("dx") && args.has("dy") -> EngineGesturePolicy.delta(width, height, args.getDouble("dx"), args.getDouble("dy"))
                        else -> throw Failure("invalid_swipe", "Supply coordinates, both snapshot refs, or dx and dy")
                    }
                    require(points[0] != points[2] || points[1] != points[3]) { "invalid_swipe" }
                    input(displayId, "swipe", EnginePolicy.coordinate(points[0], width), EnginePolicy.coordinate(points[1], height),
                        EnginePolicy.coordinate(points[2], width), EnginePolicy.coordinate(points[3], height),
                        EnginePolicy.duration(args.optInt("duration_ms", 350)).toString())
                    observations.remove(displayId)
                    JSONObject().put("display_id", displayId).put("input_submitted", true).put("delivery_verified", false)
                }
                "device.scroll" -> {
                    val (width, height) = size(displayId)
                    val bounds = if (args.has("ref")) verifyRefs(displayId, args, listOf("ref")).getValue("ref")
                        .getJSONArray("bounds").let { values -> List(4) { values.getInt(it) } } else null
                    val points = EngineGesturePolicy.scroll(width, height, args.getString("direction"), bounds)
                    input(displayId, "swipe", *points.map { it.toFloat().toString() }.toTypedArray(), "350")
                    observations.remove(displayId)
                    JSONObject().put("display_id", displayId).put("input_submitted", true).put("delivery_verified", false)
                }
                "device.type" -> {
                    input(displayId, "text", EnginePolicy.text(args.getString("text")))
                    observations.remove(displayId)
                    JSONObject().put("display_id", displayId).put("input_submitted", true).put("delivery_verified", false)
                }
                "device.press" -> {
                    val name = args.getString("key").lowercase()
                    if (displayId != 0 && name in setOf("home", "recents")) throw Failure("unsupported_agent_key",
                        "Home and Recents can affect the phone on some Android builds; use open_app or Back on the agent screen")
                    val key = EnginePolicy.keys[name]
                        ?: throw Failure("invalid_key", "Unsupported key")
                    input(displayId, "keyevent", key.toString())
                    observations.remove(displayId)
                    JSONObject().put("display_id", displayId).put("input_submitted", true).put("delivery_verified", false)
                }
                "device.open_app" -> openApp(args, displayId)
                "device.apps" -> {
                    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    val rows = shellContext.packageManager.queryIntentActivities(intent, 0)
                        .distinctBy { it.activityInfo.packageName }.take(500).map {
                            JSONObject().put("package", it.activityInfo.packageName)
                                .put("label", it.loadLabel(shellContext.packageManager).toString().take(160))
                        }
                    JSONObject().put("apps", JSONArray(rows)).put("display_id", displayId)
                }
                else -> throw Failure("unsupported_action", "Unsupported Shizuku action")
            }
            sessions.require(epoch)
            JSONObject().put("ok", true).put("result", result).toString()
        } catch (error: Exception) {
            val code = when (error) {
                is Failure -> error.code
                is SecurityException -> "permission_denied"
                is IllegalArgumentException -> error.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: "invalid_arguments"
                is IllegalStateException -> error.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: "shizuku_command_failed"
                else -> "shizuku_command_failed"
            }
            JSONObject().put("ok", false).put("error", JSONObject().put("code", code)
                .put("message", when (error) {
                    is Failure -> error.message
                    else -> "The Shizuku action failed; obtain a fresh observation before continuing"
                })).toString()
        } finally { commandEpoch = null }
    }

    private fun target(displayId: Int) {
        if (displayId < 0 || (displayId != 0 && displayId != agentDisplay?.display?.displayId) || displays.getDisplay(displayId) == null)
            throw Failure("invalid_display", "Target display is unavailable; no action was sent to the main display")
    }

    private fun size(displayId: Int): Pair<Int, Int> {
        val point = android.graphics.Point()
        displays.getDisplay(displayId)?.getRealSize(point)
            ?: throw Failure("invalid_display", "Target display was removed")
        return point.x to point.y
    }

    private fun input(displayId: Int, operation: String, vararg argv: String) {
        target(displayId)
        command(listOf("/system/bin/input", "-d", displayId.toString(), operation) + argv)
    }

    private fun openApp(args: JSONObject, displayId: Int): JSONObject {
        val pkg = EnginePolicy.packageName(args.getString("package"))
        val resolved = command(listOf("/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", "0",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg))
            .lineSequence().lastOrNull { it.startsWith("$pkg/") }
            ?: throw Failure("app_not_launchable", "The app has no launchable activity for this user")
        if (displayId != 0) {
            val component = android.content.ComponentName.unflattenFromString(resolved)
                ?: throw Failure("app_not_launchable", "Android returned an invalid launcher activity")
            val launchMode = shellContext.packageManager.getActivityInfo(component, 0).launchMode
            if (launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK ||
                launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                // MULTIPLE_TASK cannot duplicate singleTask/singleInstance activities. Reject
                // before am start can move an operator task from the primary display.
                if (hasPrimaryTask(pkg)) throw Failure("app_in_use_on_phone",
                    "This app uses a single task and already has a phone task; close that task before opening it on the agent screen")
            }
        }
        // MULTIPLE_TASK prevents an existing operator task from being moved to the agent display.
        val output = command(listOf("/system/bin/am", "start", "--user", "0", "--display", displayId.toString(),
            "-f", "0x18000000", "-n", resolved))
        if (output.contains("Error:") || output.contains("Exception")) throw Failure("app_launch_failed", "Android refused to launch the app on the selected display")
        observations.remove(displayId)
        return JSONObject().put("package", pkg).put("display_id", displayId)
    }

    private fun hasPrimaryTask(pkg: String): Boolean {
        return tasks(0).any {
            it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg || it.baseIntent.component?.packageName == pkg
        }
    }

    private fun tasks(displayId: Int): List<android.app.ActivityManager.RunningTaskInfo> {
        val manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val method = manager.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterTypes.size == 4 }
            ?: throw Failure("task_isolation_unavailable", "This Android build cannot verify independent app tasks")
        method.isAccessible = true
        val tasks = method.invoke(manager, 1000, false, false, displayId) as List<*>
        return tasks.filterIsInstance<android.app.ActivityManager.RunningTaskInfo>()
    }

    private fun snapshot(displayId: Int): JSONObject = withAutomation { automation ->
        withRoot(automation, displayId) { root ->
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) throw Failure("observation_unavailable", "The selected display has no readable app window")
            val nodes = JSONArray()
            collect(root, nodes, 0)
            if (nodes.length() == 0) throw Failure("observation_unavailable", "The selected display exposes no readable nodes; use a screenshot")
            val id = UUID.randomUUID().toString()
            observations[displayId] = Observation(id, pkg, root.windowId, nodes)
            val (width, height) = size(displayId)
            JSONObject().put("snapshot_id", id).put("app", pkg).put("display_id", displayId).put("window_id", root.windowId)
                .put("captured_at_ms", System.currentTimeMillis()).put("size", JSONObject().put("w", width).put("h", height))
                .put("nodes", nodes)
                .also { if (displayId != 0) it.put("safe_area", emptyAgentSafeArea()) }
        }
    } ?: throw Failure("observation_unavailable", "The selected display has no readable app window")

    private fun collect(node: AccessibilityNodeInfo, output: JSONArray, depth: Int, onNode: ((String, AccessibilityNodeInfo) -> Unit)? = null) {
        if (output.length() >= 160 || depth > 35 || !node.isVisibleToUser) return
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val password = node.isPassword
        val ref = "e${output.length() + 1}"
        output.put(JSONObject().put("ref", ref)
            .put("role", node.className?.toString()?.substringAfterLast('.').orEmpty())
            .put("text", if (password) "[private]" else privateValues.sanitize(node.text?.toString().orEmpty()).take(240))
            .put("description", if (password) "" else privateValues.sanitize(node.contentDescription?.toString().orEmpty()).take(240))
            .put("clickable", node.isClickable).put("editable", node.isEditable).put("enabled", node.isEnabled)
            .put("focused", node.isFocused).put("password", password).put("scrollable", node.isScrollable)
            .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))))
        onNode?.invoke(ref, node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { collect(child, output, depth + 1, onNode) } finally { child.recycle() }
        }
    }

    private fun resolveRef(displayId: Int, args: JSONObject): Pair<Double, Double> {
        return center(verifyRefs(displayId, args, listOf("ref")).getValue("ref"))
    }

    /** Validate the whole gesture's references against ONE independently acquired tree. */
    private fun verifyRefs(displayId: Int, args: JSONObject, fields: List<String>): Map<String, JSONObject> {
        val previous = observations[displayId] ?: throw Failure("stale_ref", "Obtain a new snapshot before using a reference")
        if (args.optString("snapshot_id") != previous.id) throw Failure("stale_ref", "The reference belongs to an earlier snapshot")
        val nodes = fields.associateWith { field ->
            val ref = args.getString(field).removePrefix("@")
            (0 until previous.nodes.length()).asSequence().map { previous.nodes.getJSONObject(it) }
                .firstOrNull { it.getString("ref") == ref } ?: throw Failure("stale_ref", "Unknown reference")
        }
        // snapshot replaces the stored token only once. Both endpoints are checked against
        // the same old/new observations, so validating the first cannot stale the second.
        val current = snapshot(displayId)
        if (current.getString("app") != previous.packageName || current.getInt("window_id") != previous.windowId)
            throw Failure("stale_ref", "The app window changed")
        val now = current.getJSONArray("nodes")
        return nodes.mapValues { (_, node) ->
            val matched = (0 until now.length()).asSequence().map { now.getJSONObject(it) }.firstOrNull {
                it.getString("ref") == node.getString("ref") && it.toString() == node.toString()
            } ?: throw Failure("stale_ref", "The referenced control changed; use a new snapshot")
            if (matched.optBoolean("password")) throw Failure("private_field", "Private fields require local entry")
            matched
        }
    }

    private fun center(node: JSONObject): Pair<Double, Double> {
        val rect = node.getJSONArray("bounds")
        return (rect.getDouble(0) + rect.getDouble(2)) / 2 to (rect.getDouble(1) + rect.getDouble(3)) / 2
    }

    private fun <T> withAutomation(block: (UiAutomation) -> T): T = block(acquireObservationConnection().value.client)

    /** Keep window IDs stable between RPCs, but never retain an unused client indefinitely. */
    private fun acquireObservationConnection(): ObservationConnection.Lease<AutomationSession> {
        val lease = observationConnection.acquire()
        observationTimeout?.let { Handler(callbacks.looper).removeCallbacks(it) }
        observationTimeout = Runnable { synchronized(lock) {
            if (observationConnection.close(lease.revision)) {
                observations.clear()
                observationTimeout = null
            }
        } }.also { Handler(callbacks.looper).postDelayed(it, 120_000) }
        return lease
    }

    private fun clearObservationConnection() {
        observationTimeout?.let { Handler(callbacks.looper).removeCallbacks(it) }
        observationTimeout = null
        try { observationConnection.close() } finally { observations.clear() }
    }

    private fun openAutomation(): AutomationSession {
        val thread = HandlerThread("hands-observation").apply { start() }
        var automation: UiAutomation? = null
        try {
            val connectionClass = Class.forName("android.app.IUiAutomationConnection")
            val connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
            val client = UiAutomation::class.java.getDeclaredConstructor(Looper::class.java, connectionClass)
                .apply { isAccessible = true }.newInstance(thread.looper, connection)
            automation = client
            UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType)
                .invoke(client, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            // AOSP UiAutomationConnection does not request interactive windows by default.
            // Preserve the other services (the connect flag above), and opt this client into
            // window enumeration explicitly before asking for a selected display's roots.
            client.serviceInfo = client.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            return AutomationSession(client, thread)
        } catch (failure: Exception) {
            if (automation != null) runCatching { UiAutomation::class.java.getMethod("disconnect").invoke(automation) }
            thread.quitSafely()
            throw failure
        }
    }

    private fun <T> withRoot(automation: UiAutomation, displayId: Int, block: (AccessibilityNodeInfo) -> T): T? {
        val deadline = android.os.SystemClock.elapsedRealtime() + 1_500L
        do {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val allWindows = automation.windowsOnAllDisplays
            val windows = allWindows[displayId].orEmpty()
            try {
                val ordered = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { (if (it.isFocused) 2 else 0) + if (it.isActive) 1 else 0 }
                for (window in ordered) {
                    val root = window.root ?: continue
                    try {
                        if (root.refresh() && root.isVisibleToUser) return block(root)
                    } finally { root.recycle() }
                }
            } finally {
                for (i in 0 until allWindows.size()) allWindows.valueAt(i).forEach { it.recycle() }
            }
            if (android.os.SystemClock.elapsedRealtime() >= deadline) break
            sessions.require(checkNotNull(commandEpoch))
            try { Thread.sleep(50L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Failure("observation_cancelled", "The selected-display observation was cancelled")
            }
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        return null
    }

    private fun screenshot(displayId: Int): JSONObject {
        sessions.require(checkNotNull(commandEpoch))
        if (Build.VERSION.SDK_INT < 34) throw Failure("screenshot_unsupported", "Display-specific Shizuku screenshots require Android 14 or newer")
        val capture = Class.forName("android.window.ScreenCapture")
        val listener = capture.getMethod("createSyncCaptureListener").invoke(null)
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "window") as android.os.IBinder
        val window = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        val method = window.javaClass.methods.first { it.name == "captureDisplay" && it.parameterTypes.size == 3 }
        method.isAccessible = true
        // Null args default to captureSecureLayers=false and allowProtected=false.
        method.invoke(window, displayId, null, listener)
        val buffer = Class.forName("android.window.ScreenCapture\$SynchronousScreenCaptureListener").getMethod("getBuffer").invoke(listener)
            ?: throw Failure("screenshot_failed", "Android did not return a screenshot")
        val bufferClass = Class.forName("android.window.ScreenCapture\$ScreenshotHardwareBuffer")
        val hardware = bufferClass.getMethod("getHardwareBuffer").invoke(buffer) as HardwareBuffer
        try {
            if (bufferClass.getMethod("containsSecureLayers").invoke(buffer) == true) throw Failure("screenshot_secure_window", "Android marked the capture as protected")
            val colorSpace = bufferClass.getMethod("getColorSpace").invoke(buffer) as android.graphics.ColorSpace
            val wrapped = Bitmap.wrapHardwareBuffer(hardware, colorSpace)
                ?: throw Failure("screenshot_failed", "Android could not decode the screenshot")
            val originalWidth = wrapped.width
            val originalHeight = wrapped.height
            var bitmap = try { wrapped.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw Failure("screenshot_failed", "Android could not copy the screenshot")
            } finally { wrapped.recycle() }
            try {
                val edge = max(bitmap.width, bitmap.height)
                if (edge > 1080) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width * 1080 / edge, bitmap.height * 1080 / edge, true)
                    bitmap.recycle(); bitmap = scaled
                }
                var bytes: ByteArray
                while (true) {
                    bytes = ByteArrayOutputStream().use {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw Failure("screenshot_failed", "Android could not encode the screenshot")
                        it.toByteArray()
                    }
                    if (bytes.size <= 450_000) break
                    if (max(bitmap.width, bitmap.height) < 320) throw Failure("screenshot_too_large", "Capture exceeds the IPC size limit")
                    val scaled = Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width * 3 / 4), max(1, bitmap.height * 3 / 4), true)
                    bitmap.recycle(); bitmap = scaled
                }
                return JSONObject().put("mime", "image/png").put("png_b64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    .put("w", bitmap.width).put("h", bitmap.height).put("display_id", displayId)
                    .put("screen_size", JSONObject().put("w", originalWidth).put("h", originalHeight))
                    .put("window_bounds", JSONArray(listOf(0, 0, originalWidth, originalHeight)))
                    .put("coordinate_space", "display").put("captured_at_ms", System.currentTimeMillis())
                    .also { if (displayId != 0) {
                        it.put("safe_area", emptyAgentSafeArea())
                        it.put("size", JSONObject().put("w", originalWidth).put("h", originalHeight))
                    } }
            } finally { bitmap.recycle() }
        } finally { hardware.close() }
    }

    private fun command(argv: List<String>): String {
        val process = sessions.withPermit(checkNotNull(commandEpoch)) {
            ProcessBuilder(argv).start().also { activeProcess = it }
        }
        val readers = Executors.newFixedThreadPool(2)
        val tooLarge = AtomicBoolean(false)
        fun drain(stream: java.io.InputStream) = readers.submit<String> {
            val out = ByteArrayOutputStream()
            stream.use { input ->
                val bytes = ByteArray(8192)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    if (out.size() + n > 262_144) { tooLarge.set(true); process.destroyForcibly(); break }
                    out.write(bytes, 0, n)
                }
            }
            out.toString(Charsets.UTF_8.name())
        }
        val output = drain(process.inputStream)
        val errors = drain(process.errorStream)
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw Failure("command_timeout", "The Android command timed out; verify the screen before retrying")
            }
            if (tooLarge.get()) throw Failure("output_limit", "The Android command exceeded its output limit")
            val text = output.get(1, TimeUnit.SECONDS)
            val err = errors.get(1, TimeUnit.SECONDS)
            if (process.exitValue() != 0 || err.contains("Exception")) throw Failure("android_command_failed", "Android refused the targeted command")
            return text
        } finally {
            process.destroy()
            if (activeProcess === process) activeProcess = null
            readers.shutdownNow()
        }
    }

    private fun <T> privileged(block: () -> T): T {
        checkOwner()
        val identity = Binder.clearCallingIdentity()
        return try { synchronized(lock) { block() } } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun checkOwner() {
        if (Binder.getCallingUid() != ownerUid) throw SecurityException("Caller is not the Hermes Hands application")
    }

    /** Independent displays have no primary status/navigation inset to inherit in a relay. */
    private fun emptyAgentSafeArea(): JSONObject = JSONObject()
        .put("top", 0).put("bottom", 0).put("left", 0).put("right", 0)
}
