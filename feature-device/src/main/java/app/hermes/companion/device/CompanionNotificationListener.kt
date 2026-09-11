package app.hermes.companion.device

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.hermes.companion.domain.NotificationStreamPolicy
import java.security.MessageDigest

/**
 * Opt-in Notification Listener → filtered live stream events (A13.2).
 * System settings grant required; Companion cannot auto-enable.
 * Reads enable/denylist from [NotifStreamBus] (configured by the app module).
 */
class CompanionNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotifStreamBus.listenerBound = true
        // The action tools need an instance to call cancelNotification / snoozeNotification /
        // getActiveNotifications; those are instance methods on NotificationListenerService.
        NotifStreamBus.listener = this
        Log.i(TAG, "nls connected")
    }

    override fun onListenerDisconnected() {
        NotifStreamBus.listenerBound = false
        NotifStreamBus.listener = null
        Log.i(TAG, "nls disconnected")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Keep the key registry from holding handles to notifications that are gone.
        sbn?.key?.let { NotifStreamBus.forgetKey(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!NotifStreamBus.streamEnabled) return
        val pkg = sbn.packageName.orEmpty()
        val n = sbn.notification ?: return
        val channelId = n.channelId
        if (!NotificationStreamPolicy.shouldForward(
                packageName = pkg,
                extraProtected = NotifStreamBus.extraProtected,
                selfPackage = NotifStreamBus.selfPackage.ifBlank { packageName },
                channelId = channelId,
                ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            )
        ) {
            return
        }
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(240)
        val text = sequenceOf(
            extras?.getCharSequence(Notification.EXTRA_TEXT),
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras?.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }.firstOrNull().orEmpty().take(480)
        val category = n.category.orEmpty()
        val rawKey = sbn.key ?: "$pkg:${sbn.id}:${sbn.postTime}"
        val key = hashKey(rawKey)
        // Register only what we actually stream, so the action tools inherit the same
        // protected-package and denylist filtering the stream applies above.
        sbn.key?.let { NotifStreamBus.rememberKey(key, it) }
        NotifStreamBus.emit(
            NotifStreamEvent(
                key = key,
                packageName = pkg,
                title = title,
                text = text,
                category = category,
                ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
                clearable = (n.flags and Notification.FLAG_NO_CLEAR) == 0,
                postTimeMs = sbn.postTime,
            ),
        )
    }

    companion object {
        private const val TAG = "CompanionNLS"

        fun hashKey(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}
