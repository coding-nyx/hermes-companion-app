package app.hermes.companion.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification action: stop live notification stream (does not disarm hands). */
class StopStreamReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        onStopStream?.invoke()
    }

    companion object {
        const val ACTION = "app.hermes.companion.STOP_NOTIF_STREAM"
        @Volatile var onStopStream: (() -> Unit)? = null

        fun intent(context: Context): Intent =
            Intent(context, StopStreamReceiver::class.java).setAction(ACTION)
    }
}
