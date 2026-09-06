package app.hermes.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.hermes.companion.device.StopStreamReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the process alive so operator WS / ntfy / notification stream can wake the phone. */
class StayConnectedService : Service() {
    private var keepJob: Job? = null

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
        if (intent?.action == ACTION_REFRESH) {
            if (keepJob?.isActive == true) startForeground(ID, notice())
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            keepJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(ID, notice())
        keepOperator()
        return START_STICKY
    }

    override fun onDestroy() {
        keepJob?.cancel()
        keepJob = null
        super.onDestroy()
    }

    private fun keepOperator() {
        val app = application as CompanionApp
        keepJob?.cancel()
        keepJob = app.appScope.launch(Dispatchers.IO) {
            while (isActive && (app.sticky.stayConnected || app.sticky.notifStreamEnabled)) {
                delay(KEEP_MS)
                val origin = app.sticky.notifStreamTargetOrigin()
                    ?: app.sticky.lastGoodOrigin
                    ?: app.sticky.origin
                if (!origin.isNullOrBlank()) runCatching { app.clients.existing(origin)?.ping() }
                // Refresh subtitle (stream on/off · profile) periodically.
                startForeground(ID, notice())
            }
            if (!app.sticky.stayConnected && !app.sticky.notifStreamEnabled) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

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
        const val ACTION_STOP = "app.hermes.companion.STAY_STOP"
        const val ACTION_REFRESH = "app.hermes.companion.STAY_REFRESH"

        /** Re-post the notification with the current active host; no-op if the service is not running. */
        fun refresh(context: android.content.Context) {
            val app = context.applicationContext as CompanionApp
            if (!app.sticky.stayConnected && !app.sticky.notifStreamEnabled) return
            runCatching { context.startService(Intent(context, StayConnectedService::class.java).setAction(ACTION_REFRESH)) }
        }

        private const val CHANNEL = "stay"
        private const val ID = 17
        private const val KEEP_MS = 20_000L
    }
}
