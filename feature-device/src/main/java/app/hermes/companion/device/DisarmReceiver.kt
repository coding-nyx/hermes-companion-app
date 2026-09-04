package app.hermes.companion.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notification tap → disarm. Android 12+ forbids activity launches from notification
 * trampolines (services / receivers), so this receiver never starts an activity: it drops the
 * arm state through [HandsBridge] and tears the kill-switch foreground service down.
 */
class DisarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != HandsService.ACTION_DISARM) return
        HandsBridge.onDisarm?.invoke()
        context.stopService(Intent(context, HandsService::class.java))
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, DisarmReceiver::class.java).setAction(HandsService.ACTION_DISARM)
    }
}
