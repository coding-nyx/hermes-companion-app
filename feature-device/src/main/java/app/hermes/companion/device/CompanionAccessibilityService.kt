package app.hermes.companion.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.hermes.companion.domain.CompactTree
import app.hermes.companion.domain.DeviceGestures
import app.hermes.companion.domain.SwipeSpec
import app.hermes.companion.domain.VolumeChord
import app.hermes.companion.model.ScreenSafeArea
import app.hermes.companion.model.SnapshotNode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

class CompanionAccessibilityService : AccessibilityService() {
    private var lastVolumeDownMs = 0L
    private val secureTargets = LinkedHashMap<String, AccessibilityNodeInfo>()
    private val observationLock = Any()
    private val uiEpoch = AtomicLong()
    private val observationSequence = AtomicLong()
    @Volatile private var lastUiChangeMs = 0L
    @Volatile private var lastActionCompleteMs = 0L
    @Volatile private var lastObservation: ScreenObservation? = null
    @Volatile private var lastObservationEpoch = -1L
    private var collectingGeneration = 0L
    private var collectingWindowId: Int? = null
    private var collectSecureTargets = true
    private var lastScreenshotAttemptMs = 0L
    private var secureSnapshotId: String? = null

    override fun onCreate() {
        super.onCreate()
        DevicePrivacy.initialize(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (explicitlyOff(this)) {
            runCatching { disableSelf() }
            onConnectionChanged?.invoke()
            return
        }
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        instance = this
        bound = true
        onConnectionChanged?.invoke()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) {
            instance = null
            bound = false
            onConnectionChanged?.invoke()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearSecureTargets()
        if (instance === this) {
            instance = null
            bound = false
            onConnectionChanged?.invoke()
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                markUiChanged()
                if (!pkg.isNullOrBlank()) foregroundApp = pkg
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> markUiChanged()
            // Background services, system clocks and IME suggestions do not invalidate the app's refs.
            else -> if (!pkg.isNullOrBlank() && (pkg == foregroundApp || pkg == lastObservation?.app)) markUiChanged()
        }
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val now = SystemClock.uptimeMillis()
        if (VolumeChord.secondPress(now, lastVolumeDownMs)) {
            lastVolumeDownMs = 0L
            HandsBridge.onDisarm?.invoke()
            return true
        }
        lastVolumeDownMs = now
        return false
    }

    /** A tree is read from a cleared Android cache and checked again before publishing refs. */
    fun snapshot(): ScreenObservation = synchronized(observationLock) {
        check(!DevicePrivacy.active) { "privacy_active" }
        val privacyEpoch = DevicePrivacy.epoch.get()
        lastObservation = null
        clearSecureTargets()
        requireReadableDisplay()
        repeat(3) {
            awaitUiQuiet(privacyEpoch)
            val epoch = uiEpoch.get()
            clearNodeCache()
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    if (root.refresh()) {
                        val windowId = root.windowId
                        val app = root.packageName?.toString().orEmpty()
                        if (windowId >= 0 && app.isNotBlank()) {
                            collectingGeneration = observationSequence.incrementAndGet()
                            collectingWindowId = windowId
                            val nodes = mutableListOf<SnapshotNode>()
                            walk(root, nodes)
                            if (nodes.isEmpty()) throw DeviceObservationException(
                                "screen_unavailable", "The active window exposed no visible accessibility content",
                            )
                            // Window launch/resize animations can move bounds without content events.
                            // Publish only after a second independent read confirms stable geometry.
                            pause(120)
                            requirePrivacyEpoch(privacyEpoch)
                            clearNodeCache()
                            val current = rootInActiveWindow
                            val stableTree = try {
                                if (current != null && current.refresh() && current.windowId == windowId &&
                                    current.packageName?.toString() == app) {
                                    val verifiedNodes = mutableListOf<SnapshotNode>()
                                    collectSecureTargets = false
                                    try { walk(current, verifiedNodes) } finally { collectSecureTargets = true }
                                    verifiedNodes == nodes
                                } else false
                            } finally { current?.recycle() }
                            if (stableTree && uiEpoch.get() == epoch) {
                                foregroundApp = app
                                val snapshotId = UUID.randomUUID().toString()
                                synchronized(secureTargets) { secureSnapshotId = snapshotId }
                                return@synchronized ScreenObservation(
                                    app, nodes, windowId, System.currentTimeMillis(), collectingGeneration, snapshotId,
                                ).also {
                                    lastObservation = it
                                    lastObservationEpoch = epoch
                                }
                            }
                        }
                    }
                } finally { root.recycle(); collectingWindowId = null; collectSecureTargets = true }
            }
            clearSecureTargets()
            pause(100)
        }
        throw DeviceObservationException("screen_unstable", "Could not obtain a current, stable accessibility window")
    }

    /** Compatibility for private-entry callers that bind an opaque token to editable handles. */
    fun snapshotWithToken(): Triple<String, List<SnapshotNode>, String> = snapshot().let {
        Triple(it.app, it.nodes, it.snapshotId)
    }

    /** Re-read the current tree: benign events do not invalidate refs, but actual UI drift does. */
    fun validateObservation(windowId: Int, generation: Long): Boolean = synchronized(observationLock) {
        val previous = lastObservation ?: return@synchronized false
        if (DevicePrivacy.active || previous.generation != generation || previous.windowId != windowId) return@synchronized false
        requireReadableDisplay()
        val epoch = uiEpoch.get()
        clearNodeCache()
        val root = rootInActiveWindow ?: return@synchronized false
        try {
            if (!root.refresh() || root.windowId != windowId || root.packageName?.toString() != previous.app) return@synchronized false
            collectingGeneration = generation
            collectingWindowId = windowId
            collectSecureTargets = false
            val currentNodes = mutableListOf<SnapshotNode>()
            walk(root, currentNodes)
            val unchanged = uiEpoch.get() == epoch && currentNodes == previous.nodes
            if (unchanged) lastObservationEpoch = epoch
            else { lastObservation = null; clearSecureTargets() }
            unchanged
        } finally { root.recycle(); collectingWindowId = null; collectSecureTargets = true }
    }

    private fun markUiChanged() {
        lastUiChangeMs = SystemClock.uptimeMillis()
        uiEpoch.incrementAndGet()
    }

    private fun markActionComplete() {
        lastActionCompleteMs = SystemClock.uptimeMillis()
        markUiChanged()
    }

    private fun clearNodeCache() {
        if (Build.VERSION.SDK_INT >= 33) clearCache()
    }

    private fun requirePrivacyEpoch(epoch: Long) {
        if (DevicePrivacy.active || DevicePrivacy.epoch.get() != epoch) {
            clearSecureTargets()
            throw DeviceObservationException("privacy_active", "privacy_active")
        }
    }

    private fun requireReadableDisplay() {
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) {
            throw DeviceObservationException("screen_off", "The display is off; the operator must wake it before screen control")
        }
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            throw DeviceObservationException("screen_locked", "The operator must unlock the phone before screen control")
        }
    }

    private fun awaitUiQuiet(privacyEpoch: Long) {
        val deadline = SystemClock.uptimeMillis() + 1_500L
        while (SystemClock.uptimeMillis() - lastUiChangeMs < 180L ||
            SystemClock.uptimeMillis() - lastActionCompleteMs < 350L) {
            requirePrivacyEpoch(privacyEpoch)
            if (SystemClock.uptimeMillis() >= deadline) {
                throw DeviceObservationException("screen_unstable", "The screen is still changing; obtain a new snapshot when it settles")
            }
            pause(30)
        }
    }

    private fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DeviceObservationException("observation_cancelled", "Screen observation was cancelled")
        }
    }

    private fun clearSecureTargets() = synchronized(secureTargets) {
        secureTargets.values.forEach { it.recycle() }
        secureTargets.clear()
        secureSnapshotId = null
    }

    /** Opaque local node handle, never serialized to the host or re-resolved by coordinates. */
    class SecureTarget internal constructor(
        private val service: CompanionAccessibilityService,
        private val node: AccessibilityNodeInfo,
    ) {
        private val packageName = node.packageName?.toString()
        private val window = node.windowId
        private val viewId = node.viewIdResourceName
        private val bounds = Rect().also { node.getBoundsInScreen(it) }
        private var closed = false

        @Synchronized fun fill(value: String): Boolean {
            if (closed || !DevicePrivacy.active || instance !== service) return false
            // Wait for the exact original window to return before touching the captured
            // node. A refresh while the private Activity covers it can invalidate a still
            // legitimate node on Android. Never resolve a replacement field in a new tree.
            val root = service.rootInActiveWindow ?: return false
            val originalWindowActive = try {
                root.refresh() && root.packageName?.toString() == packageName && root.windowId == window
            } finally { root.recycle() }
            if (!originalWindowActive || !node.refresh()) return false
            val currentBounds = Rect().also { node.getBoundsInScreen(it) }
            if (node.packageName?.toString() != packageName || node.windowId != window ||
                node.viewIdResourceName != viewId || currentBounds != bounds ||
                !node.isEditable || !node.isEnabled) return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            return try { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            finally { args.clear() }
        }

        @Synchronized fun close() {
            if (!closed) { closed = true; node.recycle() }
        }
    }

    fun captureSecureTarget(ref: String, packageName: String, snapshotId: String): SecureTarget? = synchronized(secureTargets) {
        if (DevicePrivacy.active || snapshotId != secureSnapshotId || uiEpoch.get() != lastObservationEpoch) return@synchronized null
        val node = secureTargets[ref.removePrefix("@")] ?: return@synchronized null
        if (!node.refresh() || !node.isEditable || !node.isEnabled || !node.isVisibleToUser ||
            node.packageName?.toString() != packageName) return@synchronized null
        // Accessibility events can come from an IME or another window after the snapshot;
        // the actual active root is the authority, rather than the last event's package.
        val root = rootInActiveWindow ?: return@synchronized null
        try {
            if (root.packageName?.toString() != packageName || root.windowId != node.windowId) return@synchronized null
            SecureTarget(this, AccessibilityNodeInfo.obtain(node))
        } finally { root.recycle() }
    }

    fun click(node: SnapshotNode): Boolean {
        if (node.bounds.size < 4) return false
        val x = (node.bounds[0] + node.bounds[2]) / 2f
        val y = (node.bounds[1] + node.bounds[3]) / 2f
        return clickAt(x, y)
    }

    fun clickAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        return dispatchAndAwaitGesture(GestureDescription.Builder().addStroke(stroke).build(), 50L)
    }

    fun swipe(spec: SwipeSpec): Boolean {
        val path = Path().apply {
            moveTo(spec.x1, spec.y1)
            lineTo(spec.x2, spec.y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, spec.durationMs)
        return dispatchAndAwaitGesture(GestureDescription.Builder().addStroke(stroke).build(), spec.durationMs)
    }

    private fun dispatchAndAwaitGesture(gesture: GestureDescription, durationMs: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Gestures must be awaited on a worker thread" }
        val privacyEpoch = DevicePrivacy.epoch.get()
        requirePrivacyEpoch(privacyEpoch)
        requireReadableDisplay()
        val completion = GestureCompletion()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { completion.complete() }
            override fun onCancelled(gestureDescription: GestureDescription?) { completion.cancel() }
        }
        if (!dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))) return false
        markUiChanged()
        completion.await(durationMs + 2_000L) { requirePrivacyEpoch(privacyEpoch) }
        markActionComplete()
        return true
    }

    fun displaySize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    fun safeArea(): ScreenSafeArea {
        val wm = getSystemService(WindowManager::class.java) ?: return ScreenSafeArea()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return ScreenSafeArea(
                top = insets.top,
                bottom = insets.bottom,
                left = insets.left,
                right = insets.right,
            )
        }
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val top = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        return ScreenSafeArea(top = top)
    }

    fun openApp(packageName: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(launch); markActionComplete(); true }.getOrDefault(false)
    }

    fun apps(): List<Pair<String, String>> {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .take(80)
    }

    fun screenshotPng(maxEdge: Int = DeviceGestures.SCREENSHOT_MAX_PX): ScreenshotObservation? = synchronized(observationLock) {
        if (DevicePrivacy.active) return@synchronized null
        val privacyEpoch = DevicePrivacy.epoch.get()
        requireReadableDisplay()
        var lastFailure: DeviceObservationException? = null
        repeat(2) {
            awaitUiQuiet(privacyEpoch)
            val sinceLastAttempt = SystemClock.uptimeMillis() - lastScreenshotAttemptMs
            if (lastScreenshotAttemptMs != 0L && sinceLastAttempt < 450L) pause(450L - sinceLastAttempt)
            requirePrivacyEpoch(privacyEpoch)
            val epoch = uiEpoch.get()
            clearNodeCache()
            val root = rootInActiveWindow
                ?: throw DeviceObservationException("screen_unavailable", "Cannot identify the active window for capture")
            var captureBounds = emptyList<Int>()
            val (windowId, app) = try {
                if (!root.refresh() || root.windowId < 0 || root.packageName.isNullOrBlank()) {
                    throw DeviceObservationException("screen_unavailable", "Cannot identify the active window for capture")
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    val window = root.window ?: throw DeviceObservationException(
                        "screen_unavailable", "Cannot identify the active window's screen bounds",
                    )
                    try {
                        val bounds = Rect().also { window.getBoundsInScreen(it) }
                        if (bounds.isEmpty) throw DeviceObservationException(
                            "screen_unavailable", "The active window has no visible bounds",
                        )
                        captureBounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    } finally { window.recycle() }
                } else {
                    val (width, height) = displaySize()
                    captureBounds = listOf(0, 0, width, height)
                }
                root.windowId to root.packageName.toString()
            } finally { root.recycle() }
            val shot = try { capturePng(maxEdge, privacyEpoch, windowId) } catch (failure: DeviceObservationException) {
                lastFailure = failure
                if (failure.code != "screenshot_blank" && failure.code != "screenshot_rate_limited") throw failure
                null
            }
            requirePrivacyEpoch(privacyEpoch)
            val current = rootInActiveWindow
            val sameWindow = try {
                current != null && current.refresh() && current.windowId == windowId &&
                    current.packageName?.toString() == app
            } finally { current?.recycle() }
            if (shot != null && sameWindow && uiEpoch.get() == epoch) {
                foregroundApp = app
                return@synchronized ScreenshotObservation(
                    shot.first, shot.second, shot.third, windowId, System.currentTimeMillis(),
                    observationSequence.incrementAndGet(), app, captureBounds,
                    if (Build.VERSION.SDK_INT >= 34) "window" else "display",
                )
            }
            if (shot != null) lastFailure = DeviceObservationException("screen_unstable", "The screen changed while its screenshot was captured")
            pause(450L)
        }
        throw lastFailure ?: DeviceObservationException("screenshot_failed", "Screenshot capture failed")
    }

    private fun capturePng(maxEdge: Int, privacyEpoch: Long, windowId: Int): Triple<ByteArray, Int, Int> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Triple<ByteArray, Int, Int>>?>()
        val executor = Executors.newSingleThreadExecutor()
        val accepting = AtomicBoolean(true)
        val callbackExecutor = Executor { task ->
            try { executor.execute(task) } catch (rejected: RejectedExecutionException) {
                // A late framework result still owns a HardwareBuffer that must be closed.
                task.run()
            }
        }
        lastScreenshotAttemptMs = SystemClock.uptimeMillis()
        try {
            val callback = object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        if (!accepting.get()) { screenshot.hardwareBuffer.close(); return }
                        result.set(runCatching {
                            if (DevicePrivacy.active || privacyEpoch != DevicePrivacy.epoch.get()) {
                                screenshot.hardwareBuffer.close()
                                throw DeviceObservationException("privacy_active", "privacy_active")
                            }
                            encodePng(screenshot, maxEdge)
                        })
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) {
                        result.set(Result.failure(ScreenCapturePolicy.failure(errorCode)))
                        latch.countDown()
                    }
                }
            if (Build.VERSION.SDK_INT >= 34) {
                // Target the same app window: Android omits accessibility overlays and reports secure-window failures.
                takeScreenshotOfWindow(windowId, callbackExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor, callback)
            }
            if (!latch.await(8, TimeUnit.SECONDS)) {
                throw DeviceObservationException("screenshot_timeout", "Android did not finish screenshot capture in time")
            }
            return result.get()?.getOrThrow()
                ?: throw DeviceObservationException("screenshot_failed", "Android returned no screenshot")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DeviceObservationException("observation_cancelled", "Screenshot capture was cancelled")
        } catch (failure: DeviceObservationException) {
            throw failure
        } catch (failure: Exception) {
            throw DeviceObservationException("screenshot_failed", "Android could not encode or deliver the screenshot")
        } finally { accepting.set(false); executor.shutdownNow() }
    }

    private fun encodePng(screenshot: ScreenshotResult, maxEdge: Int): Triple<ByteArray, Int, Int> {
        val buffer = screenshot.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                ?: throw DeviceObservationException("screenshot_failed", "Android could not read the screenshot buffer")
            val src = try { hardware.copy(Bitmap.Config.ARGB_8888, false) }
                finally { hardware.recycle() }
                ?: throw DeviceObservationException("screenshot_failed", "Android could not read the screenshot buffer")
            if (ScreenCapturePolicy.isBlank(src.width, src.height, src::getPixel)) {
                src.recycle()
                throw DeviceObservationException("screenshot_blank", "Android returned an almost black capture with no readable content; the screen may be protected or still rendering")
            }
            val (w, h) = DeviceGestures.fit(src.width, src.height, maxEdge)
            val scaled = if (w == src.width && h == src.height) {
                src
            } else {
                Bitmap.createScaledBitmap(src, w, h, true)
            }
            if (scaled !== src) src.recycle()
            try {
                val bytes = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                    throw DeviceObservationException("screenshot_failed", "Android could not encode the screenshot")
                }
                return Triple(bytes.toByteArray(), scaled.width, scaled.height)
            } finally { scaled.recycle() }
        } finally {
            buffer.close()
        }
    }

    fun type(text: String): Boolean {
        requireReadableDisplay()
        clearNodeCache()
        val root = rootInActiveWindow ?: return false
        try {
            if (!root.refresh()) return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            try {
                if (!focused.refresh() || !focused.isVisibleToUser || !focused.isEditable) return false
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                return try { focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also { if (it) markActionComplete() } }
                finally { args.clear() }
            } finally { focused.recycle() }
        } finally { root.recycle() }
    }

    fun press(key: String): Boolean {
        requireReadableDisplay()
        return when (key.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }.also { if (it) markActionComplete() }
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<SnapshotNode>) {
        if (out.size >= CompactTree.MAX_NODES) return
        // Before API 33 there is no public cache-clear API; refresh each traversed node instead.
        if (Build.VERSION.SDK_INT < 33 && !node.refresh()) return
        if (!node.isVisibleToUser) return
        if (collectingWindowId != null && node.windowId != collectingWindowId) return
        val text = if (node.isPassword) "[private]" else DevicePrivacy.sanitize(node.text?.toString().orEmpty()
            .ifBlank { node.contentDescription?.toString().orEmpty() }).take(80)
        val clickable = node.isClickable
        if (clickable || node.isEditable || text.isNotBlank()) {
            val box = Rect()
            node.getBoundsInScreen(box)
            val role = node.className?.toString()?.substringAfterLast('.')?.lowercase().orEmpty()
                .ifBlank { "node" }
            val ref = "e${collectingGeneration * CompactTree.MAX_NODES + out.size + 1}"
            if (node.isEditable && collectSecureTargets) synchronized(secureTargets) {
                secureTargets.put(ref, AccessibilityNodeInfo.obtain(node))?.recycle()
            }
            out += SnapshotNode(
                ref = ref,
                role = role,
                text = text,
                clickable = clickable,
                bounds = listOf(box.left, box.top, box.right, box.bottom),
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { walk(child, out) } finally { child.recycle() }
        }
    }

    companion object {
        @Volatile
        var bound: Boolean = false
            private set

        @Volatile
        var instance: CompanionAccessibilityService? = null
            private set

        @Volatile
        var foregroundApp: String = ""

        var onConnectionChanged: (() -> Unit)? = null

        fun explicitlyOff(context: android.content.Context): Boolean =
            context.getSharedPreferences("hermes_accessibility", android.content.Context.MODE_PRIVATE)
                .getBoolean("explicitly_off", false)

        fun setExplicitlyOff(context: android.content.Context, off: Boolean) {
            context.getSharedPreferences("hermes_accessibility", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("explicitly_off", off).apply()
        }

        fun listed(context: android.content.Context): Boolean? = AccessibilitySettingsToggle.forContext(context).isListed()

        fun requestOff(context: android.content.Context): Boolean {
            if (!bound && listed(context) == false) return true
            return HermesAccessibilitySwitch(
                { instance != null && bound },
                { checkNotNull(instance).disableSelf() },
                { disable(context) },
            ).requestOff()
        }

        /** WRITE_SECURE_SETTINGS is adb-granted only (`pm grant … WRITE_SECURE_SETTINGS`). */
        fun canWriteSecureSettings(context: android.content.Context): Boolean =
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        /** Callers must gate recovery on an active armed session. */
        fun ensureEnabled(context: android.content.Context): Boolean =
            !explicitlyOff(context) && AccessibilitySettingsToggle.forContext(context).setEnabled(true)

        fun disable(context: android.content.Context): Boolean =
            AccessibilitySettingsToggle.forContext(context).setEnabled(false)
    }
}
