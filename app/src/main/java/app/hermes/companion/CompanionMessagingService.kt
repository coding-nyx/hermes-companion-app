package app.hermes.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.security.MessageDigest

/** FCM delivery is configured; production invitations require authenticated broker detail lookup. */
class CompanionMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // A debug-only, nonce-bound transport fixture cannot launch UI, capture audio or arm Hands.
        if (!BuildConfig.DEBUG || message.data["type"] != "companion_transport_test") return
        val nonce = message.data["nonce"] ?: return
        val prefs = getSharedPreferences("companion.push-device-test", MODE_PRIVATE)
        val expected = prefs.getString("expected", null) ?: return
        if (System.currentTimeMillis() > prefs.getLong("expires", 0)) return
        if (!MessageDigest.isEqual(nonce.toByteArray(), expected.toByteArray())) return
        prefs.edit().putString("received", nonce).putLong("received_at", System.currentTimeMillis()).apply()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("companion.tests", "Companion test results", NotificationManager.IMPORTANCE_LOW))
        if (manager.areNotificationsEnabled()) manager.notify(9821, NotificationCompat.Builder(this, "companion.tests")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Companion push test")
            .setContentText("Cloud delivery received successfully.").setAutoCancel(true).build())
    }
}
