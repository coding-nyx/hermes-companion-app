package app.hermes.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the process alive so operator WS / ntfy can wake the phone. */
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
            while (isActive && app.sticky.stayConnected) {
                delay(KEEP_MS)
                runCatching { app.dashboard.ping() }
            }
        }
    }

    private fun notice(): Notification {
        val app = application as CompanionApp
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ORIGIN, app.sticky.origin)
                .putExtra(MainActivity.EXTRA_AUTOCONNECT, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("HERMES CONNECTED")
            .setContentText("stay connected")
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.hermes.companion.STAY_STOP"
        private const val CHANNEL = "stay"
        private const val ID = 17
        private const val KEEP_MS = 20_000L
    }
}
