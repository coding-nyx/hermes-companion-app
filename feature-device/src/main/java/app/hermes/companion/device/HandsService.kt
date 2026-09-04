package app.hermes.companion.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Persistent kill switch while ARMED. */
class HandsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "hands", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISARM) {
            HandsBridge.onDisarm?.invoke()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(ID, notice())
        return START_STICKY
    }

    override fun onDestroy() {
        HandsBridge.onDisarm?.invoke()
        super.onDestroy()
    }

    private fun notice(): Notification {
        // Body tap and the DISARM action both go through a broadcast receiver: no activity is
        // launched from the notification, so Android 12+ trampoline rules are satisfied and the
        // disarm is immediate even when the app UI is gone.
        val disarm = PendingIntent.getBroadcast(
            this,
            0,
            DisarmReceiver.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                1,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("HERMES HAS HANDS")
            .setContentText("Tap to disarm")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(disarm)
            .addAction(0, "DISARM", disarm)
            .apply {
                if (openApp != null) {
                    addAction(0, "OPEN", openApp)
                }
            }
            .build()
    }

    companion object {
        const val ACTION_DISARM = "app.hermes.companion.device.DISARM"
        private const val CHANNEL = "hands"
        private const val ID = 31
    }
}
