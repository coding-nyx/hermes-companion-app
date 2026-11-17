package app.hermes.companion.device

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import app.hermes.companion.design.CompanionColor
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Visible WindowManager-owned screen hold; the bounded CPU-only lock never controls screen power. */
internal class KeepAwakeWindow private constructor(private val context: Context) {
    internal class Owner {
        val revision = AtomicLong()
        @Volatile var revoked = false
        @Synchronized fun revoke(): Long { revoked = true; return revision.incrementAndGet() }
        @Synchronized fun beginSession() { revision.incrementAndGet(); revoked = false }
    }
    private data class Lease(val label: String, val deadline: Long, val revision: Long)
    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(WindowManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val owners = ConcurrentHashMap<Owner, Lease>()
    private val stopEpoch = AtomicLong()
    @Volatile private var view: View? = null
    @Volatile private var observedAttachment = false
    private var expectedRemoval = false
    private var registered = false
    private var cpu: PowerManager.WakeLock? = null
    private val expiry = Runnable { refresh() }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopAll(notify = true)
        }
    }

    fun isHeld(owner: Owner): Boolean {
        val lease = owners[owner] ?: return false
        val indicator = view ?: return false
        return !owner.revoked && lease.revision == owner.revision.get() &&
            lease.deadline > SystemClock.elapsedRealtime() && Settings.canDrawOverlays(context) &&
            power.isInteractive && !keyguard.isKeyguardLocked &&
            indicator.visibility == View.VISIBLE && (!observedAttachment || indicator.windowVisibility == View.VISIBLE)
    }

    fun acquire(owner: Owner, label: String, timeoutMs: Long) {
        val revision = owner.revision.get()
        val epoch = stopEpoch.get()
        check(!owner.revoked) { "hold_cancelled" }
        onMain { cancelled ->
            check(!owner.revoked && owner.revision.get() == revision && stopEpoch.get() == epoch) { "hold_cancelled" }
            if (!Settings.canDrawOverlays(context)) {
                stopAll(notify = true)
                throw SecurityException("overlay_permission_required")
            }
            if (!power.isInteractive) {
                stopAll(notify = true)
                throw IllegalStateException("screen_off")
            }
            if (keyguard.isKeyguardLocked) {
                stopAll(notify = true)
                throw IllegalStateException("screen_locked")
            }
            if (cancelled.get()) throw IllegalStateException("overlay_unavailable")
            val previous = owners[owner]
            owners[owner] = Lease(label, SystemClock.elapsedRealtime() + timeoutMs, revision)
            try {
                if (view == null) attach()
                if (cancelled.get()) throw IllegalStateException("overlay_unavailable")
                refresh()
                check(owners.containsKey(owner) && view != null) { "overlay_unavailable" }
            } catch (failure: Exception) {
                if (previous != null && !owner.revoked && stopEpoch.get() == epoch &&
                    previous.revision == owner.revision.get() && previous.deadline > SystemClock.elapsedRealtime()) owners[owner] = previous
                else owners.remove(owner)
                if (owners.isEmpty()) stopAll(notify = false) else refresh()
                if (failure is SecurityException) throw failure
                throw IllegalStateException("overlay_unavailable", failure)
            }
        }
    }

    fun release(owner: Owner) {
        // Invalidate renewals already queued on the main thread, even when no window
        // has been attached yet. A later explicit session has a newer revision.
        val retired = owner.revoke()
        if (!owners.containsKey(owner)) return
        onMain { _ ->
            owners.computeIfPresent(owner) { _, lease -> if (lease.revision < retired) null else lease }
            refresh()
        }
    }

    private fun attach() {
        val next = indicator(context) { stopAll(notify = true) }
        next.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { observedAttachment = true }
            override fun onViewDetachedFromWindow(v: View) {
                if (!expectedRemoval && view === v) stopAll(notify = true)
            }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(context, 12)
            y = dp(context, 8)
            setTitle("Screen hold")
            setFitInsetsTypes(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        }
        makeMovable(next, params)
        view = next
        try {
            wm.addView(next, params)
            ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        } catch (failure: Exception) {
            removeWindow()
            throw failure
        }
    }

    private fun makeMovable(indicator: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var cancelled = false
        var corner = 0
        fun moveTo(x: Int, y: Int) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val margin = dp(context, 8)
            val left = insets.left + margin
            val top = insets.top + margin
            val right = (metrics.bounds.width() - insets.right - indicator.width - margin).coerceAtLeast(left)
            val bottom = (metrics.bounds.height() - insets.bottom - indicator.height - margin).coerceAtLeast(top)
            val nextX = x.coerceIn(left, right) - insets.left
            val nextY = y.coerceIn(top, bottom) - insets.top
            if (params.gravity != Gravity.TOP or Gravity.LEFT || params.x != nextX || params.y != nextY) {
                params.gravity = Gravity.TOP or Gravity.LEFT
                params.x = nextX
                params.y = nextY
                wm.updateViewLayout(indicator, params)
            }
        }
        indicator.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    val location = IntArray(2)
                    v.getLocationOnScreen(location)
                    startX = location[0]
                    startY = location[1]
                    dragging = false
                    cancelled = false
                    false // Native Button retains click, long-click and accessibility behavior.
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelled = true
                    v.cancelLongPress()
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    try { v.onTouchEvent(cancel) } finally { cancel.recycle() }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (cancelled) true else {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && (dx * dx + dy * dy) > slop * slop) {
                            dragging = true
                            v.cancelLongPress()
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            try { v.onTouchEvent(cancel) } finally { cancel.recycle() }
                        }
                        if (dragging) moveTo(startX + dx.toInt(), startY + dy.toInt())
                        dragging
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> (dragging || cancelled).also {
                    if (it) { v.isPressed = false; v.cancelLongPress() }
                    dragging = false
                    cancelled = false
                }
                else -> dragging || cancelled
            }
        }
        indicator.setOnLongClickListener {
            corner = (corner + 1) % 4
            params.gravity = when (corner) {
                1 -> Gravity.BOTTOM or Gravity.END
                2 -> Gravity.BOTTOM or Gravity.START
                3 -> Gravity.TOP or Gravity.START
                else -> Gravity.TOP or Gravity.END
            }
            params.x = dp(context, 12)
            params.y = dp(context, 8)
            wm.updateViewLayout(indicator, params)
            true
        }
        indicator.tooltipText = "Drag or long press to move. Tap to stop."
        indicator.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (params.gravity == Gravity.TOP or Gravity.LEFT && view === v) {
                val location = IntArray(2)
                v.getLocationOnScreen(location)
                moveTo(location[0], location[1])
            }
        }
    }

    private fun refresh() {
        main.removeCallbacks(expiry)
        val now = SystemClock.elapsedRealtime()
        owners.entries.removeAll { it.value.deadline <= now || it.key.revoked || it.key.revision.get() != it.value.revision }
        if (owners.isEmpty()) {
            stopAll(notify = false)
            return
        }
        if (!Settings.canDrawOverlays(context) || !power.isInteractive || keyguard.isKeyguardLocked) {
            stopAll(notify = true)
            return
        }
        val longest = owners.values.maxOf { it.deadline } - now
        val hold = cpu ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:screen-hold-cpu")
            .apply { setReferenceCounted(false) }.also { cpu = it }
        hold.acquire(longest.coerceAtLeast(1))
        main.postDelayed(expiry, (owners.values.minOf { it.deadline } - now).coerceAtLeast(1))
    }

    private fun stopAll(notify: Boolean) {
        val hadOwners = owners.isNotEmpty()
        if (notify) {
            stopEpoch.incrementAndGet()
            owners.keys.forEach { it.revoke() }
        }
        owners.clear()
        main.removeCallbacks(expiry)
        cpu?.let { if (it.isHeld) it.release() }
        cpu = null
        removeWindow()
        if (notify && hadOwners) {
            // Revocation is unconditional. A queued renewal/new window must never suppress STOP.
            main.post { HandsBridge.onDisarm?.invoke() }
        }
    }

    private fun removeWindow() {
        val old = view
        view = null
        observedAttachment = false
        expectedRemoval = true
        try { if (old != null) runCatching { wm.removeViewImmediate(old) } }
        finally { expectedRemoval = false }
        if (registered) {
            registered = false
            runCatching { context.unregisterReceiver(screenOff) }
        }
    }

    private fun onMain(block: (AtomicBoolean) -> Unit) {
        val cancelled = AtomicBoolean(false)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(cancelled)
            return
        }
        val task = FutureTask { block(cancelled) }
        main.post(task)
        try {
            task.get(2, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            cancelled.set(true)
            task.cancel(false)
            main.removeCallbacks(task)
            throw IllegalStateException("overlay_unavailable")
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        } catch (failure: InterruptedException) {
            cancelled.set(true)
            task.cancel(false)
            main.removeCallbacks(task)
            Thread.currentThread().interrupt()
            throw IllegalStateException("overlay_unavailable", failure)
        }
    }

    companion object {
        private val windows = WeakHashMap<Context, WeakReference<KeepAwakeWindow>>()
        @Synchronized fun forContext(context: Context): KeepAwakeWindow {
            val app = context.applicationContext
            return windows[app]?.get() ?: KeepAwakeWindow(app).also { windows[app] = WeakReference(it) }
        }

        @Synchronized fun indicatorHeight(context: Context): Int =
            windows[context.applicationContext]?.get()?.view?.let { it.height.takeIf { h -> h > 0 } ?: dp(context, 48) } ?: 0

        internal fun indicator(context: Context, onStop: () -> Unit): View = object : Button(context) {
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                applyAppearance()
            }
            fun applyAppearance() {
                textSize = 13f
                minimumHeight = dp(context, 48)
                minHeight = dp(context, 48)
                setTextColor(CompanionColor.Signal.toArgb())
                background = object : Drawable() {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    override fun isStateful(): Boolean = true
                    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
                    override fun draw(canvas: Canvas) {
                        val active = state.any { it == android.R.attr.state_pressed || it == android.R.attr.state_focused }
                        val stroke = dp(context, if (active) 2 else 1).toFloat()
                        val cut = dp(context, 8).toFloat()
                        val w = bounds.width().toFloat() - stroke / 2
                        val h = bounds.height().toFloat() - stroke / 2
                        val path = Path().apply {
                            moveTo(stroke / 2, stroke / 2); lineTo(w - cut, stroke / 2)
                            lineTo(w, cut); lineTo(w, h); lineTo(cut, h)
                            lineTo(stroke / 2, h - cut); close()
                        }
                        paint.style = Paint.Style.FILL
                        paint.color = (if (active) CompanionColor.SignalDim else CompanionColor.VoidElevated).toArgb()
                        canvas.drawPath(path, paint)
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = stroke
                        paint.color = CompanionColor.Signal.toArgb()
                        canvas.drawPath(path, paint)
                    }
                    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
                    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
                }
                maxWidth = (resources.displayMetrics.widthPixels - dp(context, 24)).coerceAtLeast(dp(context, 48))
            }
            init {
                text = "AWAKE · STOP"
                contentDescription = "Awake. Stop keeping the screen awake and stop control"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAllCaps = false
                minHeight = dp(context, 48)
                minimumHeight = dp(context, 48)
                minWidth = dp(context, 48)
                minimumWidth = dp(context, 48)
                setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                maxLines = 2
                setOnClickListener { onStop() }
                applyAppearance()
            }
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
