package app.hermes.hands

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns the loopback API and preview lifetime independently of any chat application. */
class HandsHostService : Service(), AgentScreenHost {
    private val runtime get() = (application as HandsApplication).runtime
    private val main = Handler(Looper.getMainLooper())
    private val displayWorker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val sessions = Any()
    @Volatile private var currentScreen: ScreenSession? = null
    @Volatile private var destroying = false
    private class ScreenSession(val generation: Long, val width: Int, val height: Int, val density: Int) {
        val ready = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val cleanupQueued = AtomicBoolean(false)
        @Volatile var displayId = -1
        @Volatile var failure: Throwable? = null
        var root: LinearLayout? = null
        var manager: WindowManager? = null
        var surface: Surface? = null
        var buffer: SurfaceTexture? = null
        var images: ImageReader? = null
        var resourcesReleased = false
        var collapsed = false
    }
    private var wake: PowerManager.WakeLock? = null
    private val tick = object : Runnable {
        override fun run() {
            runtime.refresh()
            runtime.shizuku.refresh()
            val state = runtime.state.value
            val accessibilityReady = app.hermes.companion.device.CompanionAccessibilityService.bound &&
                !app.hermes.companion.device.CompanionAccessibilityService.explicitlyOff(this@HandsHostService) &&
                app.hermes.companion.device.CompanionAccessibilityService.listed(this@HandsHostService) == true
            val needsShizuku = state.target == "agent" || state.backend == "shizuku" ||
                (state.backend == "hybrid" && !accessibilityReady)
            val engine = runtime.shizuku.status.value
            if (state.consent && (runtime.screenControlExpected() || state.displayId > 0) && needsShizuku && (!engine.bound || !engine.permissionGranted || !engine.running)) runtime.stopSession()
            if (state.consent && (((runtime.screenControlExpected() && state.backend == "accessibility") && !accessibilityReady) ||
                    !getSystemService(NotificationManager::class.java).areNotificationsEnabled() ||
                    (state.target == "primary" && !android.provider.Settings.canDrawOverlays(this@HandsHostService)))) runtime.stopSession()
            val serving = runtime.state.value.serving
            if (serving && wake?.isHeld != true) {
                wake = runCatching {
                    getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:hands-service")
                        .apply { setReferenceCounted(false); acquire(24 * 60 * 60_000L) }
                }.getOrNull()
            } else if (!serving && wake?.isHeld == true) {
                runCatching { wake?.release() }
                wake = null
            }
            getSystemService(NotificationManager::class.java).notify(ID, notification())
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Hands control", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification())
        runtime.screenHost = this
        try { runtime.startServer() }
        catch (_: Exception) { runtime.message("Could not open the local API. Another controller may already be using port 8765."); stopSelf(); return }
        main.post(tick)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { runtime.stopServer(); stopSelf() }
            DISARM -> runtime.disarm()
            "hands.STOP_NOTIFICATIONS" -> runtime.notifications.close()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        destroying = true
        main.removeCallbacks(tick)
        runtime.stopServer()
        closeScreen()
        runtime.screenHost = null
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null
        displayWorker.shutdown()
        super.onDestroy()
    }
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, HandsHostService::class.java).setAction(DISARM), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopSharing = PendingIntent.getService(this, 3, Intent(this, HandsHostService::class.java).setAction("hands.STOP_NOTIFICATIONS"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val st = runtime.state.value
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_hands)
            .setContentTitle(if (st.consent) "HANDS · ${if (st.target == "agent") "AGENT SCREEN" else "PHONE SCREEN"}" else "Hands · control off")
            .setContentText(if (st.armed) st.client else if (st.consent) "Waiting for a controller · tap Stop to revoke"
                else if (runtime.notifications.state.value.enabled) "Notification sharing on · tap Stop sharing to pause"
                else if (runtime.remoteAccess.value.enabled) "Local and network API running · ready for a controller" else "Local API running · ready for a controller")
            .setOngoing(true).setContentIntent(open).addAction(0, "STOP CONTROL", stop)
            .also { if (runtime.notifications.state.value.enabled) it.addAction(0, "STOP SHARING", stopSharing) }.build()
    }

    override fun start(width: Int, height: Int, density: Int): Int {
        check(Looper.myLooper() != Looper.getMainLooper())
        var fresh = false
        val screen = synchronized(sessions) {
            check(!destroying) { "service_unavailable" }
            currentScreen?.takeIf { !it.closed.get() }?.also {
                check(it.width == width && it.height == height && it.density == density) { "agent_screen_already_open" }
            } ?: ScreenSession(generation.incrementAndGet(), width, height, density).also {
                currentScreen = it
                fresh = true
            }
        }
        if (fresh) {
            val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
            val canOverlay = android.provider.Settings.canDrawOverlays(this)
            if (interactive && canOverlay) {
                main.post { showScreen(screen) }
            } else {
                startHeadlessScreen(screen)
            }
        }
        if (!screen.ready.await(8, TimeUnit.SECONDS) || screen.failure != null || screen.displayId <= 0 || screen.closed.get()) {
            closeSession(screen)
            throw IllegalStateException("agent_screen_failed", screen.failure)
        }
        return screen.displayId
    }

    private fun startHeadlessScreen(screen: ScreenSession) {
        displayWorker.execute {
            synchronized(sessions) {
                if (screen.closed.get() || currentScreen !== screen || destroying) {
                    screen.ready.countDown()
                    return@execute
                }
                try {
                    val images = ImageReader.newInstance(screen.width, screen.height, PixelFormat.RGBA_8888, 2)
                    images.setOnImageAvailableListener({ reader ->
                        runCatching { reader.acquireLatestImage()?.close() }
                    }, Handler(Looper.getMainLooper()))
                    screen.images = images
                    val surface = images.surface
                    screen.surface = surface
                    val id = runtime.shizuku.createAgentScreen(surface, screen.width, screen.height, screen.density)
                    screen.displayId = id
                    if (!screen.closed.get() && currentScreen === screen && !destroying) {
                        runtime.screenOpened(id)
                    }
                } catch (error: Throwable) {
                    screen.failure = error
                    closeSession(screen)
                } finally {
                    screen.ready.countDown()
                }
            }
        }
    }

    private fun showScreen(screen: ScreenSession) {
        if (screen.closed.get() || currentScreen !== screen || destroying) { screen.ready.countDown(); return }
        try {
            val manager = getSystemService(WindowManager::class.java)
            screen.manager = manager
            val scale = resources.displayMetrics.density
            val metrics = resources.displayMetrics
            val maxPreviewWidth = minOf((190 * scale).toInt(), metrics.widthPixels * 2 / 3).coerceAtLeast(1)
            val controlsHeight = (96 * scale).toInt() + 4
            val maxPreviewHeight = (metrics.heightPixels - controlsHeight - (24 * scale).toInt()).coerceAtLeast(1)
            // Constrain both axes first, then derive one scale. A short/landscape window
            // letterboxes the preview inside the control chrome instead of stretching apps.
            val previewWidth = minOf(maxPreviewWidth,
                (maxPreviewHeight.toDouble() * screen.width / screen.height).toInt().coerceAtLeast(1))
            val previewHeight = (previewWidth.toDouble() * screen.height / screen.width).toInt().coerceAtLeast(1)
            val shellWidth = maxOf(previewWidth, (112 * scale).toInt())
                .coerceAtMost((metrics.widthPixels - (16 * scale).toInt()).coerceAtLeast(1))
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(7, 8, 10))
                setPadding(2, 2, 2, 2)
                contentDescription = "Independent agent screen"
            }
            screen.root = root
            val layout = WindowManager.LayoutParams(shellWidth, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.OPAQUE).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (8 * scale).toInt()
                y = (100 * scale).toInt().coerceAtMost((metrics.heightPixels - previewHeight - controlsHeight).coerceAtLeast(0))
            }
            val title = TextView(this).apply {
                text = "AGENT SCREEN · DRAG"
                setTextColor(Color.rgb(0, 229, 195)); textSize = 11f; typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER; minHeight = (48 * scale).toInt()
            }
            var initialY = 0; var downY = 0f; var initialX = 0; var downX = 0f
            title.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { initialY = layout.y; downY = event.rawY; initialX = layout.x; downX = event.rawX }
                    MotionEvent.ACTION_MOVE -> {
                        layout.y = (initialY + event.rawY - downY).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - (96 * scale).toInt()).coerceAtLeast(0))
                        layout.x = (initialX - event.rawX + downX).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - (64 * scale).toInt()).coerceAtLeast(0))
                        runCatching { manager.updateViewLayout(root, layout) }
                    }
                }; true
            }
            root.addView(title)
            val texture = TextureView(this)
            root.addView(texture, LinearLayout.LayoutParams(previewWidth, previewHeight).apply { gravity = Gravity.CENTER_HORIZONTAL })
            val buttons = LinearLayout(this)
            fun button(label: String, onClick: (Button) -> Unit) = Button(this).apply {
                text = label; textSize = 11f; isAllCaps = false
                setTextColor(Color.rgb(0, 229, 195)); setBackgroundColor(Color.rgb(20, 24, 30))
                minWidth = (48 * scale).toInt(); minimumHeight = (48 * scale).toInt()
                setPadding(2, 0, 2, 0); setOnClickListener { onClick(this) }
            }
            buttons.addView(button("Hide") { toggle ->
                screen.collapsed = !screen.collapsed
                // Keep the Surface attached while the non-focusable window becomes a bubble.
                layout.width = if (screen.collapsed) minOf((112 * scale).toInt(), shellWidth) else shellWidth
                layout.height = if (screen.collapsed) controlsHeight else WindowManager.LayoutParams.WRAP_CONTENT
                texture.alpha = if (screen.collapsed) 0f else 1f
                title.text = if (screen.collapsed) "AGENT" else "AGENT SCREEN · DRAG"
                toggle.text = if (screen.collapsed) "Show" else "Hide"
                runCatching { manager.updateViewLayout(root, layout) }
            }, LinearLayout.LayoutParams(0, (48 * scale).toInt(), 1f))
            buttons.addView(button("Stop") { runtime.disarm() }, LinearLayout.LayoutParams(0, (48 * scale).toInt(), 1f))
            root.addView(buttons, 1)
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(buffer: SurfaceTexture, w: Int, h: Int) {
                    synchronized(sessions) {
                        if (screen.closed.get() || currentScreen !== screen || destroying) {
                            buffer.release()
                            screen.ready.countDown()
                            return
                        }
                        buffer.setDefaultBufferSize(screen.width, screen.height)
                        screen.buffer = buffer
                        val surface = Surface(buffer)
                        screen.surface = surface
                        // Enqueue while holding the session lock. Teardown always lands behind
                        // this create and ahead of the next session's create on the same worker.
                        displayWorker.execute {
                            try {
                                if (!screen.closed.get()) {
                                    val id = runtime.shizuku.createAgentScreen(surface, screen.width, screen.height, screen.density)
                                    synchronized(sessions) {
                                        screen.displayId = id
                                        if (!screen.closed.get() && currentScreen === screen && !destroying) runtime.screenOpened(id)
                                    }
                                }
                            } catch (error: Throwable) {
                                screen.failure = error
                                closeSession(screen)
                            } finally { screen.ready.countDown() }
                        }
                    }
                }
                override fun onSurfaceTextureSizeChanged(buffer: SurfaceTexture, w: Int, h: Int) {
                    if (!screen.closed.get()) buffer.setDefaultBufferSize(screen.width, screen.height)
                }
                override fun onSurfaceTextureUpdated(buffer: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(buffer: SurfaceTexture): Boolean {
                    // Returning false transfers ownership to this session. Its display is
                    // destroyed on the worker before Surface and SurfaceTexture are released.
                    closeSession(screen)
                    return false
                }
            }
            manager.addView(root, layout)
        } catch (error: Throwable) {
            screen.failure = error
            closeSession(screen)
            screen.ready.countDown()
        }
    }

    private fun closeSession(screen: ScreenSession) {
        synchronized(sessions) {
            screen.closed.set(true)
            screen.ready.countDown()
            if (currentScreen === screen) {
                currentScreen = null
                runtime.screenClosed()
            }
            if (screen.cleanupQueued.compareAndSet(false, true)) {
                // One-way revocation does not wait behind an in-flight input command.
                runtime.shizuku.cancelAsync()
                if (!displayWorker.isShutdown) displayWorker.execute {
                    try { runCatching { runtime.shizuku.destroyAgentScreen() } }
                    finally {
                        synchronized(sessions) {
                            if (!screen.resourcesReleased) {
                                screen.surface?.release()
                                screen.buffer?.release()
                                runCatching { screen.images?.close() }
                                screen.resourcesReleased = true
                            }
                        }
                    }
                }
            }
        }
        val remove = Runnable {
            screen.root?.let { root -> runCatching { screen.manager?.removeViewImmediate(root) } }
            screen.root = null
        }
        // onDestroy already runs on main: remove synchronously so its TextureView callbacks
        // finish before executor shutdown. There is no binder/privileged work on this thread.
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run() else main.post(remove)
    }

    override fun closeScreen() {
        val screen = synchronized(sessions) { currentScreen }
        if (screen != null) closeSession(screen)
        else runtime.shizuku.cancelAsync()
    }

    companion object {
        const val STOP = "app.hermes.hands.STOP"
        const val DISARM = "app.hermes.hands.DISARM"
        private const val CHANNEL = "hands.control"
        private const val ID = 4801
    }
}
