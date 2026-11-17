package app.hermes.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.hermes.companion.device.StopStreamReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User-enabled continuous paired-agent bridge: authenticated device/operator WebSockets, wake
 * subscriptions and the selected-app notification stream stay available until the user opts out.
 * This is an interactive connection, not a finite data-sync transfer. It never arms or wakes a phone.
 */
class StayConnectedService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepJob: Job? = null
    private var refreshJob: Job? = null
    @Volatile private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "connected", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (backgroundTimedOut || intent?.action == ACTION_STOP) {
            stopBridge()
            return START_NOT_STICKY
        }
        // A refresh can create a cold service, including while an earlier foreground start is
        // pending. Satisfy Android's promotion deadline on EVERY continuing path, before looking
        // up host names, encrypted credentials or network state.
        try {
            startForeground(ID, initialNotice())
            foreground = true
        } catch (_: RuntimeException) {
            Log.w(TAG, "Android rejected the connection foreground service")
            stopBridge()
            val wanted = runCatching {
                val app = application as? CompanionApp
                app != null && (app.sticky.stayConnected || app.sticky.notifStreamEnabled)
            }.getOrDefault(false)
            if (wanted) runCatching { BackgroundConnectionResume.show(this) }
            return START_NOT_STICKY
        }
        val app = application as? CompanionApp
        val enabled = app != null && runCatching {
            app.sticky.stayConnected || app.sticky.notifStreamEnabled
        }.getOrDefault(false)
        if (!enabled || app == null) {
            stopBridge()
            return START_NOT_STICKY
        }
        BackgroundConnectionResume.clear(this)
        keepOperator(app)
        refreshJob?.cancel()
        refreshJob = serviceScope.launch { refreshNotice() }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Honor any platform timeout even though this ongoing bridge uses specialUse rather
        // than a finite dataSync task. Never restart around a system-imposed stop.
        backgroundTimedOut = true
        try {
            runCatching { (application as? CompanionApp)?.deviceNode?.pauseForBackgroundLimit() }
        } finally {
            stopBridge()
        }
    }

    override fun onDestroy() {
        foreground = false
        serviceScope.cancel()
        keepJob = null
        refreshJob = null
        super.onDestroy()
    }

    private fun stopBridge() {
        foreground = false
        keepJob?.cancel()
        keepJob = null
        refreshJob?.cancel()
        refreshJob = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun keepOperator(app: CompanionApp) {
        if (keepJob?.isActive == true) return
        keepJob = serviceScope.launch {
            while (isActive && foreground) {
                delay(KEEP_MS)
                val enabled = runCatching { app.sticky.stayConnected || app.sticky.notifStreamEnabled }
                    .getOrDefault(false)
                if (!enabled) {
                    withContext(Dispatchers.Main.immediate) { stopBridge() }
                    return@launch
                }
                maintainConnection(app)
            }
        }
    }

    internal suspend fun maintainConnection(app: CompanionApp) {
        try {
            val origin = app.sticky.notifStreamTargetOrigin() ?: app.sticky.lastGoodOrigin ?: app.sticky.origin
            if (!origin.isNullOrBlank()) app.clients.forOrigin(origin).ping()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w(TAG, "Connection heartbeat unavailable; the connection lane will retry")
        }
        runCatching { app.deviceNode.recoverAccessibility() }
            .onFailure { Log.w(TAG, "Could not refresh accessibility availability") }
        refreshNotice()
    }

    private suspend fun refreshNotice() {
        val updated = runCatching { notice() }.getOrElse {
            Log.w(TAG, "Could not refresh connection notification; retaining the current notice")
            return
        }
        withContext(Dispatchers.Main.immediate) {
            if (foreground) runCatching { getSystemService(NotificationManager::class.java).notify(ID, updated) }
                .onFailure { Log.w(TAG, "Could not update the connection notification") }
        }
    }

    private fun initialNotice(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setContentTitle("HERMES CONNECTED")
        .setContentText("Paired agent connection enabled")
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun notice(): Notification {
        val app = application as CompanionApp
        val streamOn = app.sticky.notifStreamEnabled
        val streamOrigin = app.sticky.notifStreamTargetOrigin()
        val origin = streamOrigin ?: app.sticky.lastGoodOrigin ?: app.sticky.origin
        val hostName = app.hostName(origin)
        val profile = when {
            streamOn && origin != null -> app.sticky.notifStreamProfileFor(origin).orEmpty()
            origin != null -> app.sticky.profileFor(origin).orEmpty()
            else -> ""
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ORIGIN, origin)
                .putExtra(MainActivity.EXTRA_AUTOCONNECT, true)
                .putExtra(MainActivity.EXTRA_NONCE, app.launchNonce),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopStream = PendingIntent.getBroadcast(
            this,
            2,
            StopStreamReceiver.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (hostName.isBlank()) "HERMES CONNECTED" else "HERMES CONNECTED · $hostName"
        val subtitle = buildList {
            if (streamOn) add("stream → ${profile.ifBlank { "default" }}")
            else add("stream off")
            origin?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ").ifBlank { "stay connected" }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (streamOn) {
            builder.addAction(0, "STOP STREAM", stopStream)
        }
        return builder.build()
    }

    companion object {
        @Volatile internal var backgroundTimedOut = false
        const val ACTION_STOP = "app.hermes.companion.STAY_STOP"
        const val ACTION_REFRESH = "app.hermes.companion.STAY_REFRESH"

        /** Refresh (or restore) the opted-in connection service; never enable it implicitly. */
        fun refresh(context: android.content.Context) {
            val app = context.applicationContext as CompanionApp
            if (backgroundTimedOut || (!app.sticky.stayConnected && !app.sticky.notifStreamEnabled)) return
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, StayConnectedService::class.java).setAction(ACTION_REFRESH)) }
                .onFailure { runCatching { BackgroundConnectionResume.show(context) } }
        }

        private const val CHANNEL = "stay"
        private const val ID = 17
        private const val KEEP_MS = 20_000L
        private const val TAG = "StayConnectedService"
    }
}
