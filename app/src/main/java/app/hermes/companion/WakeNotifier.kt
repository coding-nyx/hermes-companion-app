package app.hermes.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.hermes.companion.domain.WakePing
import app.hermes.companion.domain.WakePolicy

object WakeNotifier {
    private const val CHANNEL = "wake"
    private const val ID = 19

    fun show(context: Context, ping: WakePing) {
        val app = context.applicationContext as CompanionApp
        val hostName = app.hostName(ping.origin)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "wake", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            (ping.origin + ping.sessionId).hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(WakePolicy.deepLink(ping.sessionId, ping.profile, ping.origin)))
                .putExtra(MainActivity.EXTRA_NONCE, (context.applicationContext as CompanionApp).launchNonce)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // One notification per host+session, grouped per host (A8.5).
        nm.notify(
            (ping.origin + ping.sessionId).hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (hostName.isBlank()) ping.type else "${ping.type} · $hostName")
                .setContentText("${ping.profile} · ${ping.sessionId}")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(ping.origin.ifBlank { "hermes" })
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build(),
        )
    }
}
