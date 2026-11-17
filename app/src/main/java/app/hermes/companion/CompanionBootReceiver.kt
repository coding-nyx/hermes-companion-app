package app.hermes.companion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.hermes.companion.data.local.StickyStore

/** Restores saved connection consent after unlock/update, never device-control consent. */
class CompanionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Pairing credentials live in credential-protected storage. There is deliberately no
        // LOCKED_BOOT_COMPLETED registration, no direct-boot context and no screen wake here.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        val sticky = StickyStore(context)
        if (!sticky.stayConnected && !sticky.notifStreamEnabled) return
        try {
            // This is the declared specialUse paired-agent bridge, not dataSync. Android 15+
            // forbids starting dataSync/microphone/etc. from BOOT_COMPLETED.
            ContextCompat.startForegroundService(context, Intent(context, StayConnectedService::class.java))
            BackgroundConnectionResume.clear(context)
        } catch (_: RuntimeException) {
            // Background restrictions (including OEM policy) can still reject a legal request.
            // A user tap is a fresh foreground launch; never schedule a restart loop around it.
            Log.w("CompanionBoot", "Background connection restore needs a foreground launch")
            BackgroundConnectionResume.show(context)
        }
    }
}

/** A rejected background start can be resumed explicitly without opening or arming the phone. */
internal object BackgroundConnectionResume {
    internal const val ID = 19
    private const val CHANNEL = "background_connection_resume"

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Background connection",
            NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Resume Companion connection")
            .setContentText("Tap to restore your enabled background connection.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { manager.notify(ID, notification) }
    }

    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
