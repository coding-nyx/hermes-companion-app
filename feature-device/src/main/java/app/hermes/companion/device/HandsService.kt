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
        // Names of the hosts whose lanes may drive the phone (A8.5); re-posted whenever they change.
        hosts = intent?.getStringArrayListExtra(EXTRA_HOSTS)?.toList() ?: hosts
        startForeground(ID, notice())
        return START_STICKY
    }

    private var hosts: List<String> = emptyList()

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
        val who = hosts.filter { it.isNotBlank() }.joinToString(", ")
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(if (who.isBlank()) "HERMES HAS HANDS" else "HERMES HAS HANDS · $who")
            .setContentText(if (who.isBlank()) "Tap to disarm" else "$who can move this phone · tap to disarm")
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
        const val EXTRA_HOSTS = "hosts"

        /** Start (or re-post) the kill-switch notification naming [hosts]. */
        fun start(context: android.content.Context, hosts: List<String>): Intent =
            Intent(context, HandsService::class.java).putStringArrayListExtra(EXTRA_HOSTS, ArrayList(hosts))
        private const val CHANNEL = "hands"
        private const val ID = 31
    }
}
