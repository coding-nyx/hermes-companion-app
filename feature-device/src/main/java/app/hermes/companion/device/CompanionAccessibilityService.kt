package app.hermes.companion.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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
import java.util.concurrent.TimeUnit

class CompanionAccessibilityService : AccessibilityService() {
    private var lastVolumeDownMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        bound = true
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        bound = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        bound = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) foregroundApp = pkg
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

    fun snapshot(): Pair<String, List<SnapshotNode>> {
        val root = rootInActiveWindow ?: return foregroundApp to emptyList()
        val app = root.packageName?.toString().orEmpty().ifBlank { foregroundApp }
        val nodes = mutableListOf<SnapshotNode>()
        walk(root, nodes)
        return app to nodes
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
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(spec: SwipeSpec): Boolean {
        val path = Path().apply {
            moveTo(spec.x1, spec.y1)
            lineTo(spec.x2, spec.y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, spec.durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
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
        return runCatching { startActivity(launch); true }.getOrDefault(false)
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

    fun screenshotPng(maxEdge: Int = DeviceGestures.SCREENSHOT_MAX_PX): Triple<ByteArray, Int, Int>? {
        val latch = CountDownLatch(1)
        var out: Triple<ByteArray, Int, Int>? = null
        val executor = Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        out = runCatching { encodePng(screenshot, maxEdge) }.getOrNull()
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
            latch.await(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        return out
    }

    private fun encodePng(screenshot: ScreenshotResult, maxEdge: Int): Triple<ByteArray, Int, Int> {
        val buffer = screenshot.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                ?: error("wrap")
            val src = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            val (w, h) = DeviceGestures.fit(src.width, src.height, maxEdge)
            val scaled = if (w == src.width && h == src.height) {
                src
            } else {
                Bitmap.createScaledBitmap(src, w, h, true)
            }
            if (scaled !== src) src.recycle()
            val bytes = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            val width = scaled.width
            val height = scaled.height
            scaled.recycle()
            return Triple(bytes.toByteArray(), width, height)
        } finally {
            buffer.close()
        }
    }

    fun type(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun press(key: String): Boolean = when (key.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<SnapshotNode>) {
        if (out.size >= CompactTree.MAX_NODES) return
        val text = node.text?.toString().orEmpty()
            .ifBlank { node.contentDescription?.toString().orEmpty() }
            .take(80)
        val clickable = node.isClickable
        if (clickable || text.isNotBlank()) {
            val box = Rect()
            node.getBoundsInScreen(box)
            val role = node.className?.toString()?.substringAfterLast('.')?.lowercase().orEmpty()
                .ifBlank { "node" }
            out += SnapshotNode(
                ref = CompactTree.ref(out.size),
                role = role,
                text = text,
                clickable = clickable,
                bounds = listOf(box.left, box.top, box.right, box.bottom),
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out)
            child.recycle()
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
    }
}
